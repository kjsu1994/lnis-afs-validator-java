FROM gradle:8.14.3-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle --no-daemon bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 lnis \
    && mkdir -p /app/data \
    && chown -R 10001:10001 /app/data
COPY --from=build /workspace/build/libs/lnis.jar /app/lnis.jar
USER 10001
EXPOSE 8088
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","/app/lnis.jar","server"]
