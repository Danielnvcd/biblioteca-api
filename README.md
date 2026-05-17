# Biblioteca Maxipet — API

Spring Boot 3.2 / Java 17 backend para la plataforma corporativa Maxipet (migración de la app Flask original). Ofrece autenticación JWT con 2FA TOTP, gestión documental por categoría, KPIs y objetivos, quejas, acciones correctivas, alertas de seguridad, cuestionarios, bitácora y directorio.

## Stack
- **Java 17 + Spring Boot 3.5.14**
- **PostgreSQL 12+** (probado en 18)
- **Flyway 10** (migraciones versionadas)
- **JWT** (jjwt 0.12) con scope `access` / `2fa-pending` (15min) + refresh rotativo (7d) en cookie httpOnly
- **Bucket4j 8.14** (rate-limit; memory por defecto, Redis opt-in)
- **AES-256-GCM** para secretos sensibles en BD (TOTP)
- **Micrometer + Prometheus** (`/actuator/prometheus`)
- **Logback JSON** en prod (LogstashEncoder), texto en dev
- **ZXing** (QR para setup 2FA)

## Profiles

| Profile | Default DDL | Rate-limit | JWT secret fallback | Error verbosity |
|---|---|---|---|---|
| `default` (dev) | `validate` | OFF (override con env) | sí (`application.yml`) | máxima |
| `prod` | `validate` | ON | **obligatorio via env** | mínima |

Activar `prod`: `SPRING_PROFILES_ACTIVE=prod` o `--spring.profiles.active=prod`.

El schema lo gestiona Flyway (ver sección **Migraciones de BD** abajo); Hibernate solo valida.

## Variables de entorno

| Var | Default | Notas |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/biblioteca_maxipet` | JDBC URL |
| `DB_USERNAME` | `postgres` | |
| `DB_PASSWORD` | `postgres` | |
| `DB_SCHEMA` | `public` | Schema para `default_schema` de Hibernate |
| `JWT_SECRET` | dev fallback | **Obligatorio en prod**. Base64, ≥ 64 chars. |
| `JWT_EXPIRATION_MS` | `900000` (15min) | Access token TTL |
| `JWT_REFRESH_EXPIRATION_MS` | `604800000` (7d) | Refresh token TTL |
| `APP_ENCRYPTION_KEY` | dev fallback | **Obligatorio en prod**. Base64 de 32 bytes. Cifra secretos TOTP en BD. **NO la cambies después del primer deploy** (invalidaría todos los 2FA). |
| `ADMIN_INITIAL_PASSWORD` | dev: `Admin1234!` / prod: vacío | Solo se usa al crear el admin en una BD vacía. Una vez creado, se ignora. En prod déjalo vacío salvo en el primer arranque. |
| `UPLOAD_DIR` | `./uploads` | Carpeta de archivos subidos (montar volumen) |
| `CORS_ORIGINS` | `http://localhost:5173,http://localhost:3000` | CSV. En prod: el dominio público real. |
| `RATE_LIMIT_ENABLED` | `false` (dev) / `true` (prod) | Bucket4j por IP+endpoint |
| `RATE_LIMIT_BACKEND` | `memory` | `memory` o `redis` (multi-instancia). Si `redis`, requiere `SPRING_DATA_REDIS_URL`. |
| `SPRING_DATA_REDIS_URL` | — | Solo si `RATE_LIMIT_BACKEND=redis`. Ej.: `redis://host:6379` |

Generar secretos nuevos:
```bash
openssl rand -base64 64    # JWT_SECRET
openssl rand -base64 32    # APP_ENCRYPTION_KEY
```

## Correr local (dev)

```powershell
# 1. crear .env con DATABASE_URL/DB_USERNAME/DB_PASSWORD
# 2. levantar
.\start.ps1
# o:
$env:JAVA_HOME="C:\Program Files\Java\jdk-17"; .\mvnw.cmd spring-boot:run
```

En dev, el primer arranque crea `admin` / `Admin1234!` automáticamente (ese password viene de la variable `ADMIN_INITIAL_PASSWORD`, hard-coded en `application.yml` solo para dev). **Cámbialo después del primer login.**

## Build de producción

```bash
./mvnw -DskipTests package
java -jar target/biblioteca-api-1.0.0.jar \
  --spring.profiles.active=prod
```

## Docker

```bash
# .env (no commitear):
#   POSTGRES_PASSWORD=...
#   JWT_SECRET=$(openssl rand -base64 64)
#   CORS_ORIGINS=https://app.midominio.com

docker compose up -d --build
# Logs:
docker compose logs -f api
# Health:
curl http://localhost:8080/actuator/health
```

El contenedor monta volumen `uploads` (persistente) y corre como usuario no-root.

## Endpoints clave

### Auth
- `POST /api/auth/login` `{username, password}` → `{token, user}` o `{requires2fa, stepToken, message}`. Si OK, además setea cookie `refreshToken` (httpOnly, SameSite=Strict, Path=/api/auth, Secure en prod).
- `POST /api/auth/verify-2fa` `{stepToken, code}` → `{token, user}` (stepToken expira a 5 min). Setea la misma cookie de refresh.
- `POST /api/auth/refresh` (cookie) → `{token, user}` con nuevo access y rota la cookie. Detecta reuso: si llega un refresh ya rotado, revoca toda la familia del usuario.
- `POST /api/auth/logout` (cookie) → revoca el refresh actual y borra la cookie.
- `GET  /api/auth/me` (auth) — actualiza `last_seen` con throttle 5 min
- `POST /api/auth/setup-2fa` (auth) `{currentPassword}` → `{secret, uri, qr}` (qr = PNG base64)
- `POST /api/auth/confirm-2fa` (auth) `{code, secret, currentPassword}` — valida y activa
- `POST /api/auth/register` (super_admin) — crea usuario, valida rol válido y password ≥ 8
- `POST /api/auth/change-password/{id}` (auth) — self requiere `currentPassword`; super_admin para otros; bloqueado para usuarios protegidos. Password ≥ 8.
- `POST /api/auth/delete-user/{id}` (super_admin) — bloqueado para protegidos y self
- `GET  /api/auth/users` y `/users/{id}` (auth) — directorio

### Roles
- `user` — base
- `admin` — gestiona contenido (excepto almacenes)
- `super_admin` — todo
- `almacen_admin` / `almacen_user` — gestión / lectura de almacenes

**Usuarios protegidos** (no se pueden borrar ni cambiar pass como admin): `Daniel`, `Vanesa Rivera`.

### Health & métricas (prod)
- `GET /actuator/health` — público, devuelve solo `UP`/`DOWN`.
- `GET /actuator/info` — público, vacío por defecto.
- `GET /actuator/prometheus` — público, métricas Micrometer en formato Prometheus. Scrapea con Prometheus dentro de la red interna. Las métricas incluyen `application=biblioteca-api` como tag.

## Observabilidad

### Métricas
Micrometer expone JVM, Hikari, HTTP server, Spring Security y métricas de aplicación. Para scrapear con Prometheus:

```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'biblioteca-api'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['biblioteca-api.internal:8080']
```

### Logs estructurados
- **Dev (perfil default)**: una línea por evento, formato `timestamp level [requestId] logger - message`.
- **Prod (perfil `prod`)**: una línea JSON por evento (LogstashEncoder), con campos `@timestamp`, `level`, `message`, `logger_name`, `thread_name`, `stack_trace`, `requestId`, `app`. Listo para ingestar en ELK / Loki / CloudWatch sin parser adicional.

### Request ID
Cada request entrante recibe un `X-Request-Id`:
- Si el cliente o el load balancer envía el header → se respeta.
- Si no → el servidor genera un UUID.

El id viaja en MDC durante toda la request, aparece en cada línea de log emitida, y se devuelve al cliente en el header `X-Request-Id` de la respuesta. Útil para soporte: el usuario cita el id de una alerta y lo correlacionas en los logs.

## Seguridad implementada

- JWT con `scope`: `access` para uso normal, `2fa-pending` (5 min) sólo para `/verify-2fa`. El filter rechaza step tokens fuera de ese endpoint.
- **Access tokens cortos (15 min) + refresh tokens rotativos (7d)** en cookie httpOnly. Hash SHA-256 en BD; el valor crudo nunca se persiste. Reuso de un refresh revocado → revoca toda la familia (defensa contra robo).
- **JWT invalidation on password change** — claim `iat` se compara con `users.password_changed_at`; tokens viejos se rechazan automáticamente. Además se revocan TODOS los refresh tokens del usuario en cambio de contraseña.
- **2FA TOTP RFC 6238** con Base32 (compatible Google Authenticator/Authy), ventana ±1 step.
- **TOTP secret cifrado at-rest con AES-256-GCM** (clave: `APP_ENCRYPTION_KEY`). El valor crudo nunca se persiste; lo que ve la BD es `gcm:<base64-iv>:<base64-ciphertext>`. Setup y confirm de 2FA exigen `currentPassword` para que un JWT robado no permita tomar 2FA.
- **Rate-limit Bucket4j** en `/login` (8/min/IP), `/verify-2fa` (8/min/IP), `/register` (20/h/IP). IP del cliente leída de `request.getRemoteAddr()` (Spring procesa `X-Forwarded-For` oficialmente via `forward-headers-strategy=framework` — no se acepta el header crudo, que un atacante podría falsificar).
- **Path-traversal protegido** en `FileStorageService` (whitelist categorías + sanitize + `normalize().startsWith(root)`).
- **Validación por extensión + magic bytes** del contenido (PDF, PNG, JPEG, OOXML/OLE, audio, video). Rechaza un `.exe` renombrado a `.pdf`.
- **MIME whitelist** para servir, `X-Content-Type-Options: nosniff`.
- **Acceso a archivos**: solo `perfiles`, `boletin`, `quejas`, `seguridad` son públicos (necesarios para `<img src>`). `documentos`, `manuales`, `cursos`, `lecciones`, `almacenes` exigen JWT. `almacenes` adicionalmente exige rol.
- **Comentarios**: máx 1000 chars + validan que el content exista y que la categoría sea `boletin`/`lecciones`.
- **Mass-assignment** evitado con DTOs sin `id` para KPI/Objetivo.
- **Video URLs** solo `https://` (mitiga mixed-content y tampering en tránsito).
- **CSRF disabled** (stateless JWT en headers). El refresh va en cookie con `SameSite=Strict`, lo que mitiga CSRF en `/auth/refresh` sin necesidad de un CSRF token.
- **CORS** configurable por `CORS_ORIGINS`. `Allow-Credentials: true`, lista explícita de orígenes (no wildcard).
- **last_seen UPDATE directo** para evitar contención de fila.
- **Audit log en transacción propia** (`REQUIRES_NEW`); fallos no rompen la operación.
- **Validador de secretos en arranque (`prod`)**: si `JWT_SECRET`, `APP_ENCRYPTION_KEY` o `ADMIN_INITIAL_PASSWORD` resuelven a los fallbacks de dev, la app rechaza arrancar.
- **Integridad referencial**: FK con `ON DELETE CASCADE` para `refresh_tokens` y `correction_activity`. `UNIQUE(questionnaire_id, user_name)` en respuestas de cuestionario.

## Migraciones de BD (Flyway)

El schema vive en `src/main/resources/db/migration/` con convención `V{n}__descripcion.sql`. Flyway corre **al arrancar la app**, antes de que Hibernate haga `validate`.

**Reglas:**
- Una vez aplicado un `V{n}__*.sql` (en cualquier entorno), **no se edita**. Cualquier cambio va en un nuevo `V{n+1}__*.sql`.
- DDL idempotente cuando aplique (`IF NOT EXISTS`, `WHERE NOT EXISTS`) para que el script sobreviva re-corridas en BDs heterogéneas.
- Cambios de datos (seeds, backfills) también como migración. Sólo el bootstrap del admin se queda en `DataInitializer` porque depende del `PasswordEncoder` en runtime.

**Estado del schema:** Flyway registra cada migración aplicada en la tabla `flyway_schema_history`.

```sql
SELECT version, description, installed_on, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

**Migraciones actuales:**

| Archivo | Qué hace |
|---|---|
| `V1__init_schema.sql` | 14 tablas iniciales + índices |
| `V2__seed_default_data.sql` | 30 categorías de contenido (idempotente con `WHERE NOT EXISTS`) |
| `V3__refresh_tokens.sql` | Tabla `refresh_tokens` para el flujo de access+refresh |
| `V4__cascades_and_unique.sql` | `ON DELETE CASCADE` en FKs problemáticas + `UNIQUE(questionnaire_id, user_name)` |

### Primer arranque en una BD que ya existe (prod, dev actual)

Configurado con `baseline-on-migrate=true` + `baseline-version=1`:

1. Flyway detecta que el schema no está vacío y que no hay tabla `flyway_schema_history`.
2. Crea la tabla con un registro de baseline marcado como V1.
3. **No re-ejecuta `V1`** (el schema actual ya está en V1).
4. Ejecuta V2, V3, V4 sobre el schema existente. V2 es idempotente; V3/V4 añaden tablas/constraints que aún no existen.
5. Hibernate hace `validate`. Si todo coincide, la app arranca.

**Antes del primer deploy a prod**, opcionalmente verifica V1 contra el schema real:

```bash
pg_dump --schema-only --no-owner --no-privileges -h <host> -U <user> biblioteca_maxipet > prod_schema.sql
diff prod_schema.sql src/main/resources/db/migration/V1__init_schema.sql
```

Si hay diferencias significativas (columnas distintas, tipos distintos), corrige V1 **antes** de commitear. Si son cosméticas (orden, nombres de constraint), no importa: `validate` no las inspecciona.

### Primer arranque en BD vacía (CI, dev nuevo)

1. Flyway crea `flyway_schema_history` vacía.
2. Ejecuta V1 → V2 → V3 → V4.
3. Hibernate `validate` pasa.
4. `DataInitializer` crea el admin si `ADMIN_INITIAL_PASSWORD` está seteado.

---

# Deploy a producción

Esta sección documenta lo necesario para subir el backend detrás de **Nginx + Cloudflare Tunnel**. La app no requiere puerto público.

## Pre-requisitos

| Item | Cómo |
|---|---|
| Java 17 | Cualquier distro (OpenJDK, Temurin). En el host del backend. |
| PostgreSQL 13+ | Con la BD `biblioteca_maxipet` creada y un usuario con permisos. |
| `cloudflared` | Cliente del Cloudflare Tunnel registrado contra tu zona DNS. |
| Nginx | Reverse proxy entre `cloudflared` y la JVM. |
| (Opcional) Redis | Solo si configuras `RATE_LIMIT_BACKEND=redis` para múltiples instancias. |

## Secretos a generar

```bash
openssl rand -base64 64    # JWT_SECRET
openssl rand -base64 32    # APP_ENCRYPTION_KEY  ← guardarla a buen recaudo
```

Si **pierdes** `APP_ENCRYPTION_KEY` después del primer deploy, los 2FA cifrados quedan inservibles y los usuarios afectados deben re-configurar 2FA. **No la rotes sin un plan de migración**.

## Variables de entorno mínimas en prod

```bash
SPRING_PROFILES_ACTIVE=prod
JWT_SECRET=<openssl rand -base64 64>
APP_ENCRYPTION_KEY=<openssl rand -base64 32>
DATABASE_URL=jdbc:postgresql://localhost:5432/biblioteca_maxipet
DB_USERNAME=biblioteca_user
DB_PASSWORD=<password>
CORS_ORIGINS=https://app.tudominio.com
UPLOAD_DIR=/var/biblioteca-api/uploads
# Solo en el PRIMER arranque para crear el admin:
ADMIN_INITIAL_PASSWORD=<password-fuerte-temporal>
```

Después del primer login del admin, **borra `ADMIN_INITIAL_PASSWORD` del entorno** y reinicia. La app ya no la necesita.

## Configuración de Nginx

Crítica para que el backend reciba la IP real del cliente y sepa que la request es HTTPS (las cookies `Secure` dependen de eso):

```nginx
upstream biblioteca_api {
    server 127.0.0.1:8080;
}

# Acepta CF-Connecting-IP solo de los rangos de Cloudflare.
# Lista oficial: https://www.cloudflare.com/ips-v4
set_real_ip_from 173.245.48.0/20;
set_real_ip_from 103.21.244.0/22;
# ... (añadir todos los rangos)
real_ip_header CF-Connecting-IP;

server {
    listen 80;
    server_name app.tudominio.com;

    client_max_body_size 50M;   # uploads grandes

    location / {
        proxy_pass         http://biblioteca_api;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto https;  # crítico: la cookie Secure depende de esto
        proxy_set_header   X-Forwarded-Host  $host;
    }
}
```

Spring procesa los `X-Forwarded-*` automáticamente vía `forward-headers-strategy: framework`, así que tu `RateLimitFilter` y la cookie `Secure` funcionan correctamente.

## Cloudflare Tunnel

`cloudflared` apunta su ingress a Nginx local (`http://localhost:80`). No abras el puerto 80 al internet — el tunnel se encarga del transporte cifrado.

```yaml
# /etc/cloudflared/config.yml
tunnel: <tunnel-id>
credentials-file: /etc/cloudflared/<tunnel-id>.json
ingress:
  - hostname: app.tudominio.com
    service: http://localhost:80
  - service: http_status:404
```

## Endurecimiento adicional (recomendado en Cloudflare)

- **Bloquear `/actuator/prometheus`** en Cloudflare WAF — exponerlo internamente para tu scraper, no por Cloudflare.
- **Rate-limit a nivel del edge** en `/api/auth/login` y `/api/auth/refresh` (10 req/min por IP). Tu rate-limit Bucket4j sigue siendo defensa adicional.
- **WAF managed rules** activadas (OWASP CRS).
- **Bot Fight Mode** o **Bot Management**.

## Smoke test post-deploy

```bash
# Health
curl https://app.tudominio.com/actuator/health
# → {"status":"UP"}

# Login real
curl -i -X POST https://app.tudominio.com/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"...","remember":true}'
# → 200 + Set-Cookie: refreshToken=...; HttpOnly; Secure; SameSite=Strict; Path=/api/auth
```

Si la cookie viene **sin** `Secure`, hay un problema con `X-Forwarded-Proto` en Nginx: revísalo. Si la app rechaza arrancar con un mensaje sobre "dev fallback", es que olvidaste setear alguna env var de secret.

## Logs en prod

Salen en JSON (LogstashEncoder) a stdout. Recoléctalos con tu logger preferido (Loki/ELK/CloudWatch). Cada línea trae `requestId` para correlacionar.

```bash
# Si corres con systemd:
journalctl -u biblioteca-api -f --output=cat | jq .
```

## Mejoras opcionales para escalar (cuando aplique)

| Escenario | Cambio |
|---|---|
| Necesitas correr 2+ instancias del backend | `RATE_LIMIT_BACKEND=redis` + `SPRING_DATA_REDIS_URL=...`. Sin esto los buckets son por proceso. |
| Audit log crece sin freno | Job cron que mueva filas viejas a una tabla histórica o las archive. |
| Picos de tráfico en `/login` | Subir el rate-limit en Cloudflare antes que en la app. |

## Build/test

```powershell
.\mvnw.cmd clean package
.\mvnw.cmd test
```
