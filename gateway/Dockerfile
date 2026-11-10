FROM golang:1.25.5-alpine AS builder

WORKDIR /src

RUN apk add --no-cache git

COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/tyche-gateway .

FROM alpine:3.21

RUN apk add --no-cache ca-certificates tzdata

COPY --from=builder /out/tyche-gateway /usr/local/bin/tyche-gateway

USER nobody
ENTRYPOINT ["tyche-gateway"]
