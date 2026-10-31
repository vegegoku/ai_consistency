FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/quarkus-app/lib/ /app/lib/
COPY target/quarkus-app/*.jar /app/
COPY target/quarkus-app/app/ /app/app/
COPY target/quarkus-app/quarkus/ /app/quarkus/
USER 1001
EXPOSE 8080 8443
ENTRYPOINT ["java","-jar","/app/quarkus-run.jar"]
