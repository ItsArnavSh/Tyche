FROM node:20-alpine

WORKDIR /app

# Install deps first (for caching)
COPY package.json package-lock.json ./
RUN npm ci

# Copy rest of the code
COPY . .

# Expose dev server port (Vite default)
EXPOSE 5173

# Important: allow external access + polling for file changes
ENV HOST=0.0.0.0
ENV CHOKIDAR_USEPOLLING=true

CMD ["npm", "run", "dev", "--", "--host"]
