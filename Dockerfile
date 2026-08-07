# ---------- Build stage ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY pom.xml .
COPY src ./src

# El cache mount persiste el repo Maven ENTRE builds: un reintento reanuda en
# vez de volver a bajar los ~300 MB desde cero. No viaja a la imagen final —
# esto es multi-stage y al runtime solo se copia el jar.
#
# Antes acá había un `dependency:go-offline` en su propia capa para cachear las
# dependencias aparte de las fuentes. Se quitó: resuelve bastante más de lo que
# el build necesita (todos los plugins, todos los opcionales) y el cache mount
# cubre el mismo objetivo sin bajar de más.
#
# Los timeouts son el motivo real del cambio. Sin ellos, una conexión que muere
# en silencio contra Maven Central deja el build colgado indefinidamente: sin
# output, sin error y sin timeout (visto en la práctica — 13 min a 1 KB/s con
# 1 segundo de CPU acumulado). Van los dos juegos de propiedades a propósito:
# `maven.wagon.*` aplica al transporte wagon y `aether.connector.*` al
# transporte HTTP nativo del resolver, que es el que Maven 3.9 usa por defecto.
# Con uno solo, el que esté activo se queda sin timeout.
#
# Tampoco lleva `-q`: en modo quiet Maven no imprime las descargas, que es
# justamente lo que hacía imposible ver dónde se trababa.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -DskipTests \
        -Dmaven.wagon.rto=30000 \
        -Dmaven.wagon.http.retryHandler.count=3 \
        -Daether.connector.connectTimeout=15000 \
        -Daether.connector.requestTimeout=30000 \
        -Daether.connector.http.retryHandler.count=3 \
        package

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
