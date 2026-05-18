-- ============================================================================
-- V6: Lockout por usuario para defensa de fuerza bruta sobre currentPassword.
--
-- Contexto: el rate-limit IP-based del filtro protege contra brute-force a
-- gran escala, pero un atacante con un access token válido (token robado vía
-- XSS, sesión olvidada, etc.) podía probar `currentPassword` desde la misma
-- IP a 5/min × 60min × 24h = 7200 intentos/día. Estas dos columnas permiten
-- bloquear la operación a nivel de usuario tras N fallos consecutivos.
-- ============================================================================

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS failed_password_attempts INTEGER NOT NULL DEFAULT 0;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS password_locked_until TIMESTAMP NULL;
