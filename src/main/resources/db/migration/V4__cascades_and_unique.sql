-- ============================================================================
-- V4: Integrity hardening.
--
-- 1. refresh_tokens.user_id → users(id) ON DELETE CASCADE
--    Otherwise deleting a user with active refresh tokens fails on FK.
-- 2. correction_activity.action_id → corrective_action(id) ON DELETE CASCADE
--    Deleting an action with activities currently fails on FK; the JPA cascade
--    is application-level only.
-- 3. questionnaire_responses (questionnaire_id, user_name) UNIQUE
--    The "already answered?" check in the service is race-condition-prone;
--    enforce uniqueness at the DB level.
--
-- The constraint names that Hibernate auto-generated are not portable across
-- environments, so the DO blocks drop whatever constraint is there before
-- adding the cascading one.
-- ============================================================================

-- refresh_tokens.user_id → users(id) ON DELETE CASCADE
DO $$
DECLARE
    cname TEXT;
BEGIN
    SELECT conname INTO cname
    FROM pg_constraint
    WHERE conrelid = 'refresh_tokens'::regclass
      AND contype  = 'f'
      AND pg_get_constraintdef(oid) ILIKE '%user_id%REFERENCES%users%';
    IF cname IS NOT NULL THEN
        EXECUTE 'ALTER TABLE refresh_tokens DROP CONSTRAINT ' || quote_ident(cname);
    END IF;
    ALTER TABLE refresh_tokens
        ADD CONSTRAINT fk_refresh_tokens_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
END $$;

-- correction_activity.action_id → corrective_action(id) ON DELETE CASCADE
DO $$
DECLARE
    cname TEXT;
BEGIN
    SELECT conname INTO cname
    FROM pg_constraint
    WHERE conrelid = 'correction_activity'::regclass
      AND contype  = 'f'
      AND pg_get_constraintdef(oid) ILIKE '%action_id%REFERENCES%corrective_action%';
    IF cname IS NOT NULL THEN
        EXECUTE 'ALTER TABLE correction_activity DROP CONSTRAINT ' || quote_ident(cname);
    END IF;
    ALTER TABLE correction_activity
        ADD CONSTRAINT fk_correction_activity_action
        FOREIGN KEY (action_id) REFERENCES corrective_action(id) ON DELETE CASCADE;
END $$;

-- Prevent duplicate questionnaire responses per user/questionnaire.
-- If duplicates already exist they must be cleaned up before this runs.
ALTER TABLE questionnaire_responses
    ADD CONSTRAINT uq_questionnaire_responses_user
    UNIQUE (questionnaire_id, user_name);
