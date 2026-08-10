-- ============================================================================
-- V8: Lista de revocación de access tokens, para que /logout los invalide.
--
-- Hasta ahora el logout solo revocaba el refresh token: el access token seguía
-- siendo válido hasta su expiración natural (15 min). Alguien que lo hubiera
-- capturado conservaba acceso durante ese rato aunque el usuario "cerrara
-- sesión" — que es justo lo que el usuario cree estar impidiendo al hacerlo.
--
-- Por qué una lista por TOKEN (jti) y no una marca por usuario:
-- una columna tipo `tokens_valid_from` en `users` sería más barata, pero
-- cerraría la sesión en TODOS los dispositivos al desconectarse de uno solo.
-- El jti permite revocar exactamente la sesión que se cierra.
--
-- La tabla se mantiene diminuta sola: solo viven aquí los tokens revocados que
-- todavía no expiraron (≤15 min), y cada revocación borra los vencidos.
-- ============================================================================

CREATE TABLE IF NOT EXISTS revoked_access_tokens (
    jti        VARCHAR(64) PRIMARY KEY,
    expires_at TIMESTAMP   NOT NULL,
    revoked_at TIMESTAMP   NOT NULL
);

-- Soporta el borrado de vencidos. La búsqueda por jti ya va por la PK.
CREATE INDEX IF NOT EXISTS idx_revoked_access_tokens_expires_at
    ON revoked_access_tokens (expires_at);
