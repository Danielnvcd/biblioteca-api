-- ============================================================================
-- V7: Lockout por cuenta para la verificación del código TOTP + secret
--     pendiente de confirmación guardado en el servidor.
--
-- Contexto (1) — lockout:
-- El login por contraseña tenía DOS techos: rate-limit por IP (8/min) y
-- bloqueo por cuenta (V6, 10 fallos → 15 min). La verificación del segundo
-- factor solo tenía el primero: verify2fa no tocaba failed_password_attempts,
-- así que un fallo de TOTP no dejaba ningún estado en la cuenta.
--
-- El techo por IP no alcanza solo: un atacante distribuido (botnet, proxies)
-- lo evade por definición — es el mismo razonamiento que motivó V6 para el
-- login. Con 10^6 códigos y WINDOW=1 (3 códigos válidos a la vez), sin un
-- contador ligado a la cuenta el segundo factor era forzable por quien ya
-- tuviera la contraseña.
--
-- Se usan columnas propias en vez de reutilizar las de V6 a propósito: mezclar
-- ambos contadores haría que un fallo de TOTP bloquee /change-password y
-- viceversa, acoplando dos flujos que se bloquean por motivos distintos.
--
-- Contexto (2) — totp_pending_secret:
-- /confirm-2fa recibía el secret desde el cuerpo del request, así que el
-- factor que la cuenta iba a usar lo elegía quien llamaba, no el servidor.
-- Ahora /setup-2fa lo persiste cifrado (AES-256-GCM, mismo EncryptionService
-- que totp_secret) y /confirm-2fa solo confirma el que ya emitió el servidor.
-- ============================================================================

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS failed_totp_attempts INTEGER NOT NULL DEFAULT 0;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS totp_locked_until TIMESTAMP NULL;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS totp_pending_secret TEXT NULL;
