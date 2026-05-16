# Biblioteca Maxipet — API

Spring Boot 3.2 / Java 17 backend para la plataforma corporativa Maxipet (migración de la app Flask original). Ofrece autenticación JWT con 2FA TOTP, gestión documental por categoría, KPIs y objetivos, quejas, acciones correctivas, alertas de seguridad, cuestionarios, bitácora y directorio.

## Stack
- **Java 17 + Spring Boot 3.2.5**
- **PostgreSQL 12+**
- **JWT** (jjwt 0.12) con scope `access` / `2fa-pending`
- **Bucket4j** (rate-limit en memoria)
- **ZXing** (QR para setup 2FA)
- **Spring Actuator** (`/actuator/health`)

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
| `UPLOAD_DIR` | `./uploads` | Carpeta de archivos subidos (montar volumen) |
| `CORS_ORIGINS` | `http://localhost:5173,http://localhost:3000` | CSV |
| `RATE_LIMIT_ENABLED` | `false` (dev) / `true` (prod) | Bucket4j por IP+endpoint |

Genera un JWT secret nuevo:
```bash
openssl rand -base64 64
```

## Correr local (dev)

```powershell
# 1. crear .env con DATABASE_URL/DB_USERNAME/DB_PASSWORD
# 2. levantar
.\start.ps1
# o:
$env:JAVA_HOME="C:\Program Files\Java\jdk-17"; .\mvnw.cmd spring-boot:run
```

Admin por defecto creado en primer arranque: `admin` / `Admin1234!`. **Cámbiala**.

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
- `POST /api/auth/setup-2fa` (auth) → `{secret, uri, qr}` (qr = PNG base64)
- `POST /api/auth/confirm-2fa` (auth) `{code, secret}` — valida y activa
- `POST /api/auth/register` (super_admin) — crea usuario, valida rol válido y password ≥6
- `POST /api/auth/change-password/{id}` (auth) — self requiere `currentPassword`; super_admin para otros; bloqueado para usuarios protegidos
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
- **TOTP secret cifrado por usuario** (Base32, 160 bits, SecureRandom).
- **Rate-limit Bucket4j** en `/login` (8/min/IP), `/verify-2fa` (8/min/IP), `/register` (20/h/IP).
- **Path-traversal protegido** en `FileStorageService` (whitelist categorías + sanitize + `normalize().startsWith(root)`).
- **Validación de extensión** según `app.allowed-extensions`.
- **MIME whitelist** para servir, `X-Content-Type-Options: nosniff`.
- **Cross-category delete** bloqueado — el permiso se aplica sobre la categoría real del row.
- **Mass-assignment** evitado con DTOs sin `id` para KPI/Objetivo.
- **XSS en video URLs** — sólo se aceptan `http://` y `https://`.
- **Almacenes restringido** — `/api/files/almacenes/*` exige auth + rol.
- **CSRF disabled** (stateless JWT). **CORS** configurable por `CORS_ORIGINS`.
- **last_seen UPDATE directo** para evitar contención de fila.
- **Audit log en transacción propia** (`REQUIRES_NEW`); fallos no rompen la operación.

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

### Primer deploy a una BD que ya existe (prod, dev actual)

Está configurado con `baseline-on-migrate=true` + `baseline-version=0`. En el primer arranque:

1. Flyway detecta que el schema no está vacío y que no hay tabla `flyway_schema_history`.
2. Crea la tabla con un registro de baseline V0.
3. **No re-ejecuta `V1__init_schema.sql`** (porque V1 > baseline V0).
4. Ejecuta `V2__seed_default_data.sql` (no hace nada si las categorías ya existen, por `WHERE NOT EXISTS`).
5. Hibernate hace `validate` contra el schema existente. Si todo coincide, la app arranca.

**Antes del primer deploy a prod**, verifica que `V1__init_schema.sql` coincida con el schema actual de prod:

```bash
# Desde una máquina con acceso a la BD prod
pg_dump --schema-only --no-owner --no-privileges -h <host> -U <user> biblioteca_maxipet > prod_schema.sql
# Comparar visualmente contra src/main/resources/db/migration/V1__init_schema.sql
```

Si encuentras diferencias significativas (columnas distintas, tipos distintos), corrige V1 **antes** de hacer commit y deploy. Si las diferencias son cosméticas (orden de columnas, nombres de constraints autogenerados), no importa: `validate` no las inspecciona.

### Primer arranque en BD vacía (CI, dev nuevo)

1. Flyway crea `flyway_schema_history` vacía.
2. Ejecuta `V1__init_schema.sql` → crea las 14 tablas + índices.
3. Ejecuta `V2__seed_default_data.sql` → siembra las 30 categorías.
4. Hibernate `validate` pasa.
5. `DataInitializer` crea el admin.

## Pendientes recomendados para escala

- Paginación real (hoy listas con cap de 500).
- **Distributed rate-limit** (Redis) si se escala a múltiples instancias.

## Build/test

```powershell
.\mvnw.cmd clean package
.\mvnw.cmd test
```
