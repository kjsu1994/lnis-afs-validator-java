FROM eclipse-temurin:21-jre

WORKDIR /app
RUN useradd --system --uid 10001 lnis \
    && mkdir -p /app/data /app/host-project \
    && chown -R 10001:10001 /app

COPY lnis.jar /app/lnis.jar

USER 10001
EXPOSE 8088
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","/app/lnis.jar","server"]
