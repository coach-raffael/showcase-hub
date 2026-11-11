# Build-Kontext ist api/:  docker build -t demos-api api
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
# Nur die Abhängigkeiten fürs Paketieren vorab laden (Layer-Cache). Kein dependency:go-offline:
# das löst auch optionale Test-Abhängigkeiten auf (Saxon-HE → Axiom 1.2.15, nur als POM auf Central)
# und bricht dann ab – Exclusions beachtet es dabei nicht.
RUN mvn -q -B -Dmaven.test.skip=true dependency:resolve -DincludeScope=runtime || true
COPY src ./src
RUN mvn -q -B -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre
WORKDIR /app
# wget für den Compose-Healthcheck (Traefik routet nicht zu "unhealthy" Containern)
RUN apt-get update \
 && apt-get install -y --no-install-recommends wget \
 && rm -rf /var/lib/apt/lists/* \
 && useradd -r -u 10001 app \
 && mkdir -p /data && chown app /data
COPY --from=build /app/target/demos-api-*.jar app.jar
USER app
ENV TZ=Asia/Tbilisi \
    HUB_DATA_DIR=/data
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]
