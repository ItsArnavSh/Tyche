SHELL := /bin/bash
PYTHON ?= python3
SYMBOLS ?= RELIANCE.NS,TCS.NS,HDFCBANK.NS,INFY.NS,ICICIBANK.NS
PERIOD ?= 2y
STRATEGY ?= sma
CASH ?= 100000

.PHONY: help build build-core build-gateway dashboard test test-java test-go \
	test-python test-dashboard proto backtest run up down logs clean

help:
	@echo "Tyche targets:"
	@echo "  make build        Build the Java core distribution and Go gateway"
	@echo "  make test         Run Java, Go (race) and Python test suites"
	@echo "  make test-dashboard  Type-check and build the Svelte dashboard"
	@echo "  make proto        Regenerate Go and Java stubs from gateway/proto/stockrec.proto"
	@echo "  make dashboard    Run the dashboard dev server (expects status API on :8080)"
	@echo "  make backtest     Run a real-data backtest (SYMBOLS/PERIOD/STRATEGY/CASH)"
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
