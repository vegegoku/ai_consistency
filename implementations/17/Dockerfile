FROM maven:3.10.0-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN mvn -B -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system payments && useradd --system --gid payments payments
COPY --from=build /build/target/payment-service-1.0.0.jar app.jar
USER payments
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-jar","app.jar"]
