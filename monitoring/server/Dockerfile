# Use official Deno image
FROM denoland/deno:alpine-2.7.11

WORKDIR /app

# Cache dependencies (important)
COPY main.ts .

# Copy rest of the code
COPY . .

# Expose port (if your app listens on one)
EXPOSE 8000

# Run app
CMD ["deno", "run", "--allow-all", "main.ts"]
