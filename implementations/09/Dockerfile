FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 payments
COPY target/payments-1.0.0.jar /app/payments.jar
USER payments
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/payments.jar"]
