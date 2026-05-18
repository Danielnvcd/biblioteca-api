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

# curl es para el HEALTHCHECK. Lo instalamos explícitamente para no depender
# de qué herramientas trae la imagen base (eclipse-temurin a veces incluye
# wget, a veces no, según la versión).
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl tzdata \
 && ln -snf /usr/share/zoneinfo/America/Mexico_City /etc/localtime \
 && echo "America/Mexico_City" > /etc/timezone \
 && rm -rf /var/lib/apt/lists/*

# Run as non-root
RUN groupadd --system app && useradd --system --gid app --home /app app

# Persistent uploads + access logs (mount volumes on these paths)
RUN mkdir -p /app/uploads /app/logs && chown -R app:app /app

COPY --from=build /workspace/target/*.jar app.jar
RUN chown app:app app.jar

USER app

EXPOSE 8080

# TZ alinea la zona horaria del SO; -Duser.timezone hace lo mismo a nivel JVM
# (LocalDateTime.now() lee user.timezone). Sin esto, el contenedor corre en UTC
# y los timestamps que ve el usuario quedan 6 horas adelantados.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom -Duser.timezone=America/Mexico_City" \
    SPRING_PROFILES_ACTIVE=prod \
    UPLOAD_DIR=/app/uploads \
    TZ=America/Mexico_City

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
