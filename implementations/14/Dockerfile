FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY scripts/maven-settings.xml /build/settings.xml
COPY src src
RUN mvn -s /build/settings.xml -B -DskipTests package
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 payments
WORKDIR /app
COPY --from=build /build/target/payments-1.0.0.jar /app/payments.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/payments.jar"]
