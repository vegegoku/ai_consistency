FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
RUN mvn -B -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd -r payments && useradd -r -g payments payments
COPY --from=build /workspace/target/payment-service-15-1.0.0.jar /app/app.jar
USER payments
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-jar","/app/app.jar"]
