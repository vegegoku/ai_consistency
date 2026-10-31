FROM maven:3.9.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml ./
COPY src ./src
RUN mvn -B package -DskipTests
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /build/target/quarkus-app/ ./
USER 10001:10001
EXPOSE 8443
ENTRYPOINT ["java","-jar","quarkus-run.jar"]
