FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /work
COPY pom.xml .
COPY src src
RUN mvn -B -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /work/target/quarkus-app/ /app/
RUN groupadd -g 10001 payments && useradd -u 10001 -g payments payments
USER 10001:10001
EXPOSE 8443
ENTRYPOINT ["java","-jar","/app/quarkus-run.jar"]
