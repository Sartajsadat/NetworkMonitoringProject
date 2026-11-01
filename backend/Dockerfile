# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# ---- runtime stage ----
FROM eclipse-temurin:21-jre
RUN groupadd --system netmon && useradd --system --gid netmon --home /app --shell /usr/sbin/nologin netmon
WORKDIR /app
COPY --from=build /src/target/netmon-backend-*.jar /app/app.jar
# The backend never needs root: packet capture and firewall changes run in separate agents (later phases).
USER netmon
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD ["sh", "-c", "wget -qO- http://127.0.0.1:8080/actuator/health/liveness >/dev/null 2>&1 || curl -fs http://127.0.0.1:8080/actuator/health/liveness >/dev/null"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
