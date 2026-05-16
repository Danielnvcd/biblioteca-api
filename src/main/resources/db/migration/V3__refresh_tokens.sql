-- ============================================================================
-- V3: Refresh tokens.
--
-- Stores rotated refresh tokens for the new short-lived access / long-lived
-- refresh flow. The raw token is never persisted — only its SHA-256 hash.
--
-- Rotation: on each /auth/refresh call, the current row is marked revoked_at
-- and points to its successor via replaced_by. If a token that was already
-- rotated is presented again, all tokens for that user are revoked (reuse
-- detection) and the caller is forced to log in.
-- ============================================================================

CREATE TABLE refresh_tokens (
    id          BIGSERIAL PRIMARY KEY,
    user_id     INTEGER       NOT NULL REFERENCES users(id),
    token_hash  VARCHAR(64)   NOT NULL UNIQUE,
    expires_at  TIMESTAMP     NOT NULL,
    created_at  TIMESTAMP     NOT NULL,
    revoked_at  TIMESTAMP,
    replaced_by BIGINT        REFERENCES refresh_tokens(id),
    ip          VARCHAR(45),
    user_agent  VARCHAR(200)
);

CREATE INDEX idx_refresh_tokens_user_id    ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);
