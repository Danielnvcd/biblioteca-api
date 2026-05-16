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
| `default` (dev) | `update` | OFF (override con env) | sí (`application.yml`) | máxima |
| `prod` | `validate` | ON | **obligatorio via env** | mínima |

Activar `prod`: `SPRING_PROFILES_ACTIVE=prod` o `--spring.profiles.active=prod`.

## Variables de entorno

| Var | Default | Notas |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/biblioteca_maxipet` | JDBC URL |
| `DB_USERNAME` | `postgres` | |
| `DB_PASSWORD` | `postgres` | |
| `DB_SCHEMA` | `public` | Schema para `default_schema` de Hibernate |
| `JWT_SECRET` | dev fallback | **Obligatorio en prod**. Base64, ≥ 64 chars. |
| `JWT_EXPIRATION_MS` | `86400000` (24h) | |
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
- `POST /api/auth/login` `{username, password}` → `{token, user}` o `{requires2fa, stepToken, message}`
- `POST /api/auth/verify-2fa` `{stepToken, code}` → `{token, user}` (stepToken expira a 5 min)
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

### Health (prod)
- `GET /actuator/health` — público, devuelve solo `UP`/`DOWN`.

## Seguridad implementada

- JWT con `scope`: `access` para uso normal, `2fa-pending` (5 min) sólo para `/verify-2fa`. El filter rechaza step tokens fuera de ese endpoint.
- **JWT invalidation on password change** — claim `iat` se compara con `users.password_changed_at`; tokens viejos se rechazan automáticamente.
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

## Pendientes recomendados para escala

- Migraciones con **Flyway** o **Liquibase** (hoy `ddl-auto: validate` en prod requiere que la BD esté al día manualmente).
- **Refresh tokens** (hoy JWT es de 24h, sin rotación).
- Paginación real (hoy listas con cap de 500).
- **Distributed rate-limit** (Redis) si se escala a múltiples instancias.
- Métricas Prometheus + logs estructurados (Logback JSON).

## Build/test

```powershell
.\mvnw.cmd clean package
.\mvnw.cmd test
```
