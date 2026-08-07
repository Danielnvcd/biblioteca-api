# Separar el rol de la aplicación del dueño del esquema

## El problema

La aplicación se conecta a Postgres con `DB_USERNAME`, que es el mismo
`POSTGRES_USER` que la imagen de Postgres crea como **superusuario** de la
base. Es decir: cada consulta que hace la API corre con permisos para
`DROP TABLE`, leer cualquier tabla del cluster y crear roles.

Hoy no hay ninguna inyección SQL en el código — todas las consultas son JPQL
con parámetros nombrados, no hay queries nativas ni concatenación. Esto no
corrige una vulnerabilidad existente: acota el daño de una futura. Es la
diferencia entre que un bug de inyección permita leer datos y que permita
destruir la base.

## El diseño

Dos roles con responsabilidades distintas:

| Rol | Quién lo usa | Permisos |
|---|---|---|
| `bibliotecario` (el actual, dueño) | Flyway, en el arranque | DDL sobre el esquema |
| `biblioteca_app` (nuevo) | La aplicación, en marcha | Solo `SELECT/INSERT/UPDATE/DELETE` |

Spring lo soporta de forma nativa: `spring.flyway.user` / `password` ya están
cableados en `application.yml` y, si se definen, Flyway abre su propia conexión
con esas credenciales contra la misma URL. Por defecto caen a las de la
aplicación, así que **sin configurar nada el comportamiento no cambia**.

## Aplicarlo

### 1. Crear el rol

Elegí una contraseña fuerte y distinta de la del dueño:

```bash
openssl rand -base64 24
```

Conectate como el dueño actual y ejecutá (sustituyendo `<PASSWORD>`, y
`biblioteca_maxipet` / `bibliotecario` si tus nombres difieren):

```sql
CREATE ROLE biblioteca_app LOGIN PASSWORD '<PASSWORD>';

GRANT CONNECT ON DATABASE biblioteca_maxipet TO biblioteca_app;
GRANT USAGE   ON SCHEMA public               TO biblioteca_app;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES    IN SCHEMA public TO biblioteca_app;
GRANT USAGE, SELECT                  ON ALL SEQUENCES IN SCHEMA public TO biblioteca_app;

-- Sin esto, la PRÓXIMA migración crea tablas que el rol de la app no puede
-- leer, y la aplicación arranca bien pero falla al tocar esa tabla. Es el
-- error clásico de este montaje y no aparece hasta semanas después.
ALTER DEFAULT PRIVILEGES FOR ROLE bibliotecario IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO biblioteca_app;
ALTER DEFAULT PRIVILEGES FOR ROLE bibliotecario IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO biblioteca_app;
```

En el stack de Docker:

```bash
docker compose exec -T db psql -U bibliotecario -d biblioteca_maxipet
```

### 2. Configurar las variables

En `.env`:

```bash
# La aplicación: solo DML
DB_USERNAME=biblioteca_app
DB_PASSWORD=<PASSWORD>

# Flyway: el dueño del esquema, el único que necesita DDL
DB_MIGRATION_USERNAME=bibliotecario
DB_MIGRATION_PASSWORD=<la contraseña actual de bibliotecario>
```

`docker-compose.yml` ya pasa las cuatro.

### 3. Verificar

Reiniciá y comprobá que el arranque aplica migraciones y que la app funciona:

```bash
docker compose up -d api
docker compose logs api | grep -i "Successfully validated\|Successfully applied"
curl -s localhost:8080/actuator/health
```

La prueba de que el privilegio quedó realmente recortado — debe **fallar**:

```bash
docker compose exec -T db psql -U biblioteca_app -d biblioteca_maxipet \
  -c "DROP TABLE users;"
# ERROR:  must be owner of table users
```

Y esta debe funcionar:

```bash
docker compose exec -T db psql -U biblioteca_app -d biblioteca_maxipet \
  -c "SELECT count(*) FROM users;"
```

## Revertir

Poné `DB_USERNAME`/`DB_PASSWORD` de vuelta al dueño, quitá las `DB_MIGRATION_*`
y reiniciá. El rol `biblioteca_app` puede quedarse sin usar; para borrarlo hay
que revocarle los permisos primero (`REVOKE ... ; DROP ROLE biblioteca_app;`).
