-- ============================================================================
-- V9: Correo verificado por cuenta, código de acceso por correo (segundo
--     factor alternativo al TOTP) y avisos de inicio de sesión.
--
-- Contexto (1) — por qué el correo se verifica antes de servir para algo:
-- Si bastara con escribir una dirección para que empiece a recibir códigos de
-- acceso, cualquiera que consiguiera un access token robado podría asignarse
-- un correo propio y quedarse con el segundo factor de la cuenta — o sea, el
-- factor que existe para frenar exactamente ese escenario. Por eso el alta va
-- a `pending_email` y solo se promueve a `email` cuando un código enviado a
-- esa dirección vuelve correcto.
--
-- Contexto (2) — por qué los códigos van hasheados y no en claro:
-- Un código de acceso vivo es, durante su ventana, equivalente al segundo
-- factor. Guardarlo en claro convierte cualquier lectura de la base (backup
-- extraviado, inyección de solo-lectura, dump de soporte) en un bypass del
-- 2FA. Se guarda un HMAC-SHA256 con clave de aplicación: sin la clave, el
-- contenido de la tabla no sirve. Se usa HMAC y no BCrypt a propósito — el
-- código tiene entropía alta (10^8) y vive 10 minutos, así que el costo de
-- CPU por intento no compra nada y sí abre un vector de DoS.
--
-- Contexto (3) — por qué los contadores de fallo son columnas propias:
-- Mismo criterio que V7 al separarse de V6. Un fallo tecleando el código del
-- correo no tiene por qué bloquear /change-password, ni al revés. Compartir
-- columnas acopla bloqueos que se disparan por motivos distintos.
--
-- Contexto (4) — known_devices:
-- El aviso "iniciaste sesión en un dispositivo nuevo" necesita saber qué es
-- viejo. Se identifica por una cookie opaca y aleatoria (hash guardado aquí),
-- no por IP: la IP cambia sola al pasar de wifi a datos móviles y generaría
-- avisos falsos hasta que el usuario los ignore, que es la forma más rápida
-- de que un aviso real pase desapercibido.
--
-- Todo el archivo es aditivo: se puede aplicar con la versión anterior del
-- jar corriendo, sin romperla.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- users: correo, verificación, segundo factor por correo y preferencia de avisos
-- ---------------------------------------------------------------------------
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS email VARCHAR(254) NULL;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT FALSE;

-- Dirección en proceso de verificación. Vive separada de `email` para que un
-- cambio a medias no deje a la cuenta apuntando a un correo que nadie probó
-- que sea del usuario.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS pending_email VARCHAR(254) NULL;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS email_2fa_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- 'off' | 'new_device' | 'always'. El default es new_device y no always
-- porque un aviso que llega todos los días se vuelve ruido, y el objetivo del
-- aviso es que alguien note el inicio de sesión que NO hizo.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS login_alerts VARCHAR(16) NOT NULL DEFAULT 'new_device';

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS failed_email_code_attempts INTEGER NOT NULL DEFAULT 0;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS email_code_locked_until TIMESTAMP NULL;

-- Dos cuentas no pueden compartir correo: es canal de segundo factor y de
-- aviso de intrusión, y compartirlo haría que el aviso de una cuenta llegue
-- al dueño de otra. Índice parcial y sobre lower() — NULL queda libre (la
-- mayoría de las cuentas no tienen correo) y la unicidad no depende de cómo
-- el usuario haya tecleado las mayúsculas.
CREATE UNIQUE INDEX IF NOT EXISTS ux_users_email_lower
    ON users (lower(email))
    WHERE email IS NOT NULL;

-- ---------------------------------------------------------------------------
-- email_codes: códigos de un solo uso enviados por correo
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS email_codes (
    id           BIGSERIAL PRIMARY KEY,
    user_id      INTEGER      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- 'login' | 'verify_email'
    purpose      VARCHAR(20)  NOT NULL,
    -- HMAC-SHA256 en hex (64 chars). Ver contexto (2) arriba.
    code_hash    VARCHAR(64)  NOT NULL,
    -- A qué dirección se envió. Se congela aquí para que un cambio de correo
    -- entre la emisión y la verificación no redirija un código ya en vuelo.
    destination  VARCHAR(254) NOT NULL,
    attempts     SMALLINT     NOT NULL DEFAULT 0,
    created_at   TIMESTAMP    NOT NULL,
    expires_at   TIMESTAMP    NOT NULL,
    -- Se marca tanto al usarlo bien como al quemarlo (demasiados intentos o
    -- emisión de uno nuevo). Un código consumido no vuelve a verificarse.
    consumed_at  TIMESTAMP    NULL,
    ip           VARCHAR(45)  NULL
);

-- Cubre la búsqueda del código vivo (user + purpose + consumed_at IS NULL) y
-- los conteos de emisión por ventana de tiempo, que son las dos únicas
-- consultas que hace la tabla.
CREATE INDEX IF NOT EXISTS idx_email_codes_lookup
    ON email_codes (user_id, purpose, created_at DESC);

-- Para el borrado periódico de vencidos.
CREATE INDEX IF NOT EXISTS idx_email_codes_expires_at
    ON email_codes (expires_at);

-- ---------------------------------------------------------------------------
-- known_devices: dispositivos desde los que la cuenta ya inició sesión
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS known_devices (
    id          BIGSERIAL PRIMARY KEY,
    user_id     INTEGER      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- SHA-256 en hex del valor de la cookie de dispositivo. Se guarda hasheado
    -- por la misma razón que los refresh tokens: la cookie es un identificador
    -- que el navegador presenta, y un dump de esta tabla no debería entregar
    -- valores presentables.
    -- VARCHAR y no CHAR: CHAR rellena con espacios hasta el largo fijo, y un
    -- hash hexadecimal que vuelve de la base con padding deja de comparar
    -- igual. Mismo tipo que refresh_tokens.token_hash.
    device_hash VARCHAR(64)  NOT NULL,
    -- "Chrome · Windows" — para que el correo de aviso diga algo reconocible.
    label       VARCHAR(120) NULL,
    last_ip     VARCHAR(45)  NULL,
    first_seen  TIMESTAMP    NOT NULL,
    last_seen   TIMESTAMP    NOT NULL,
    CONSTRAINT ux_known_devices_user_hash UNIQUE (user_id, device_hash)
);

CREATE INDEX IF NOT EXISTS idx_known_devices_user_id
    ON known_devices (user_id);
