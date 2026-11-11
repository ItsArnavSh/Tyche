SHELL := /bin/bash
PYTHON ?= python3
SYMBOLS ?= RELIANCE.NS,TCS.NS,HDFCBANK.NS,INFY.NS,ICICIBANK.NS
PERIOD ?= 2y
STRATEGY ?= sma
CASH ?= 100000
UNIVERSE ?= nifty500

# Local development tmux session.
TMUX_BIN ?= tmux
TMUX_SESSION ?= tyche
TMUX_ATTACH ?= 1
ENV_FILE ?= .env
ENV_PATH := $(abspath $(ENV_FILE))

.PHONY: help build build-core build-gateway dashboard tmux tmux-attach tmux-kill \
	test test-java test-go test-python test-dashboard proto backtest run up down \
	universe logs clean

help:
	@echo "Tyche targets:"
	@echo "  make build        Build the Java core distribution and Go gateway"
	@echo "  make test         Run Java, Go (race) and Python test suites"
	@echo "  make test-dashboard  Type-check and build the Svelte dashboard"
	@echo "  make proto        Regenerate Go and Java stubs from gateway/proto/stockrec.proto"
	@echo "  make dashboard    Run the dashboard dev server (expects status API on :8080)"
	@echo "  make tmux         Start Redis, core, gateway and dashboard in tmux"
	@echo "  make tmux-attach  Attach to the Tyche tmux session"
	@echo "  make tmux-kill    Stop the Tyche tmux session"
	@echo "  make backtest     Run a real-data backtest (SYMBOLS/PERIOD/STRATEGY/CASH)"
	@echo "  make universe     Print the resolved NSE universe (UNIVERSE=nifty500)"
	@echo "  make run          Run core + gateway locally (needs Redis on localhost)"
	@echo "  make up / down    Start or stop the docker compose stack"
	@echo "  make clean        Remove build artifacts"

build: build-core build-gateway

build-core:
	cd core && ./gradlew :app:installDist --no-daemon

build-gateway:
	cd gateway && mkdir -p bin && go build -o bin/tyche-gateway .

dashboard:
	cd monitoring/client && npm run dev

# Start all local services in one tmux session. Redis is skipped when a local
# instance is already listening; the gateway waits for it before starting.
tmux:
	@command -v $(TMUX_BIN) >/dev/null || { echo "tmux is required"; exit 1; }
	@if $(TMUX_BIN) has-session -t "$(TMUX_SESSION)" 2>/dev/null; then \
		echo "tmux session '$(TMUX_SESSION)' is already running."; \
	else \
		$(TMUX_BIN) new-session -d -s "$(TMUX_SESSION)" -n redis -c "$(CURDIR)" \
			bash -c 'set -a; [ ! -f "$(ENV_PATH)" ] || . "$(ENV_PATH)"; set +a; export PATH="$(PATH)"; if redis-cli -h 127.0.0.1 -p 6379 ping >/dev/null 2>&1; then echo "Redis already running on 127.0.0.1:6379"; exec bash; else exec redis-server --save "" --appendonly no; fi'; \
		$(TMUX_BIN) new-window -t "$(TMUX_SESSION):1" -n core -c "$(CURDIR)/core" \
			bash -c 'set -a; [ ! -f "$(ENV_PATH)" ] || . "$(ENV_PATH)"; set +a; export PATH="$(PATH)"; exec ./gradlew :app:run --no-daemon'; \
		$(TMUX_BIN) new-window -t "$(TMUX_SESSION):2" -n gateway -c "$(CURDIR)/gateway" \
			bash -c 'set -a; [ ! -f "$(ENV_PATH)" ] || . "$(ENV_PATH)"; set +a; export PATH="$(PATH)"; until redis-cli -h 127.0.0.1 -p 6379 ping >/dev/null 2>&1; do echo "Waiting for Redis on 127.0.0.1:6379..."; sleep 1; done; exec go run .'; \
		$(TMUX_BIN) new-window -t "$(TMUX_SESSION):3" -n dashboard -c "$(CURDIR)/monitoring/client" \
			bash -c 'set -a; [ ! -f "$(ENV_PATH)" ] || . "$(ENV_PATH)"; set +a; export PATH="$(PATH)"; [ -d node_modules ] || npm ci; exec npm run dev -- --host'; \
		for win in redis core gateway dashboard; do \
			$(TMUX_BIN) set-option -w -t "$(TMUX_SESSION):$$win" remain-on-exit on; \
		done; \
		$(TMUX_BIN) select-window -t "$(TMUX_SESSION):gateway"; \
		echo "Started tmux session '$(TMUX_SESSION)'."; \
	fi
	@if [ "$(TMUX_ATTACH)" = "1" ]; then \
		if [ -n "$$TMUX" ]; then \
			$(TMUX_BIN) switch-client -t "$(TMUX_SESSION)"; \
		else \
			$(TMUX_BIN) attach -t "$(TMUX_SESSION)"; \
		fi; \
	else \
		echo "Skipping attach (TMUX_ATTACH=$(TMUX_ATTACH))."; \
	fi

tmux-attach:
	@$(TMUX_BIN) has-session -t "$(TMUX_SESSION)" 2>/dev/null || { \
		echo "No tmux session '$(TMUX_SESSION)'; run 'make tmux' first."; \
		exit 1; \
	}
	@if [ -n "$$TMUX" ]; then \
		$(TMUX_BIN) switch-client -t "$(TMUX_SESSION)"; \
	else \
		$(TMUX_BIN) attach -t "$(TMUX_SESSION)"; \
	fi

tmux-kill:
	@if $(TMUX_BIN) has-session -t "$(TMUX_SESSION)" 2>/dev/null; then \
		$(TMUX_BIN) kill-session -t "$(TMUX_SESSION)"; \
		echo "Stopped tmux session '$(TMUX_SESSION)'."; \
	else \
		echo "No tmux session '$(TMUX_SESSION)'."; \
	fi

test: test-java test-go test-python

test-java:
	cd core && ./gradlew :app:test --no-daemon

test-go:
	cd gateway && go vet ./... && go test -race ./...

test-python:
	$(PYTHON) -m unittest discover -s backtest/tests -v

test-dashboard:
	cd monitoring/client && npm run check && npm run build

proto:
	command -v buf >/dev/null || { echo "buf is required: https://buf.build/docs/installation"; exit 1; }
	cd gateway && buf generate
	cd gateway && buf generate --template buf.gen.java.yaml -o ../core/app/src/generated/java

backtest:
	$(PYTHON) -m backtest --symbols "$(SYMBOLS)" --period "$(PERIOD)" \
		--strategy "$(STRATEGY)" --cash "$(CASH)"

universe:
	cd gateway && go run . universe "$(UNIVERSE)"

run:
	@set -e; \
	core_pid=0; \
	cleanup() { kill "$$core_pid" 2>/dev/null || true; }; \
	trap cleanup INT TERM EXIT; \
	(cd core && exec ./gradlew :app:run --no-daemon) & core_pid=$$!; \
	for i in {1..60}; do \
		(echo > /dev/tcp/127.0.0.1/50051) >/dev/null 2>&1 && break; \
		sleep 1; \
	done; \
	(cd gateway && go run .)

up:
	docker compose up --build -d

down:
	docker compose down

logs:
	docker compose logs -f --tail=100

clean:
	cd core && ./gradlew clean --no-daemon || true
	rm -rf gateway/bin backtest/__pycache__ backtest/tests/__pycache__
	rm -rf monitoring/client/.svelte-kit monitoring/client/build
