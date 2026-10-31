FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
RUN mvn -B -DskipTests package
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S payments && adduser -S payments -G payments
WORKDIR /app
COPY --from=build --chown=payments:payments /build/target/quarkus-app/ ./
USER payments
EXPOSE 8443
ENTRYPOINT ["java","-jar","quarkus-run.jar"]
