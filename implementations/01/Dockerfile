FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S payment && adduser -S payment -G payment
COPY --chown=payment:payment target/quarkus-app/lib/ /app/lib/
COPY --chown=payment:payment target/quarkus-app/*.jar /app/
COPY --chown=payment:payment target/quarkus-app/app/ /app/app/
COPY --chown=payment:payment target/quarkus-app/quarkus/ /app/quarkus/
USER payment
EXPOSE 8443
ENTRYPOINT ["java","-jar","/app/quarkus-run.jar"]
