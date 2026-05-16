# ---------- Build stage ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# Cache deps separately from sources
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package

# ---------- Runtime stage ----------
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Run as non-root
RUN groupadd --system app && useradd --system --gid app --home /app app

# Persistent uploads (mount a volume on this path)
RUN mkdir -p /app/uploads && chown -R app:app /app

COPY --from=build /workspace/target/*.jar app.jar
RUN chown app:app app.jar

USER app

EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom" \
    SPRING_PROFILES_ACTIVE=prod \
    UPLOAD_DIR=/app/uploads

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget -q -O- http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
