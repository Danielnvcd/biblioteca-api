-- ============================================================================
-- V5: Widen users.totp_secret to TEXT.
--
-- AuthService cifra el secreto TOTP con AES-GCM y lo guarda como
-- "gcm:<iv>:<ciphertext>" en base64. Ese payload supera holgadamente los
-- 32 caracteres que el VARCHAR(32) original permitía, por lo que activar 2FA
-- fallaba al persistir el secreto cifrado.
-- ============================================================================

ALTER TABLE users
    ALTER COLUMN totp_secret TYPE TEXT;
