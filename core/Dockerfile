FROM gradle:8.7-jdk21 AS builder
WORKDIR /app
COPY . .
RUN gradle :app:installDist -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /app/app/build/install/app/ .
EXPOSE 50051
ENTRYPOINT ["bin/app"]
