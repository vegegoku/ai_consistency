FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src src
RUN mvn -B -DskipTests package
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S payments && adduser -S payments -G payments
WORKDIR /app
COPY --from=build /build/target/payment-service-1.0.0.jar app.jar
USER payments
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]
