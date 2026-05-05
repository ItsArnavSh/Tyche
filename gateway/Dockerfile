FROM golang:1.25.5-alpine

WORKDIR /app

# Install git (needed for modules sometimes)
RUN apk add --no-cache git

# Cache deps
COPY go.mod go.sum ./
RUN go mod download

# Copy source
COPY . .

# Expose port (change if needed)
EXPOSE 8080

# Run directly
CMD ["go", "run", "."]
