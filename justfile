symbols := "RELIANCE.NS,TCS.NS,HDFCBANK.NS,INFY.NS,ICICIBANK.NS"
period := "2y"
strategy := "sma"
cash := "100000"

default:
    @just --list

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
    python3 -m unittest discover -s backtest/tests -v

test-dashboard:
    cd monitoring/client && npm run check && npm run build

proto:
    [ -n "$(command -v buf)" ] || { echo "buf is required: https://buf.build/docs/installation"; exit 1; }
    cd gateway && buf generate
    cd gateway && buf generate --template buf.gen.java.yaml -o ../core/app/src/generated/java

backtest:
    python3 -m backtest --symbols "{{symbols}}" --period "{{period}}" --strategy "{{strategy}}" --cash "{{cash}}"

run:
    #!/usr/bin/env bash
    set -e
    core_pid=0
    cleanup() { kill "$core_pid" 2>/dev/null || true; }
    trap cleanup INT TERM EXIT
    (cd core && exec ./gradlew :app:run --no-daemon) & core_pid=$!
    for i in {1..60}; do
        (echo > /dev/tcp/127.0.0.1/50051) >/dev/null 2>&1 && break
        sleep 1
    done
    (cd gateway && go run .)

up:
    docker compose up --build -d

down:
    docker compose down

logs:
    docker compose logs -f --tail=100

clean:
    -cd core && ./gradlew clean --no-daemon
    rm -rf gateway/bin backtest/__pycache__ backtest/tests/__pycache__
    rm -rf monitoring/client/.svelte-kit monitoring/client/build
