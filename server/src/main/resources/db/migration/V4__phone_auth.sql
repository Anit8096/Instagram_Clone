-- Google-first sign-in with phone OTP (docs/SPEC.md). Every account now has a Google identity and a verified phone;
-- email/password sign-in is gone.

-- Existing accounts have no verified phone and can't sign in any more: remove them (cascades their content).
-- Local development data only; run the seeder again afterwards.
DELETE FROM users;

ALTER TABLE users ADD COLUMN phone_e164 VARCHAR(16) NOT NULL UNIQUE CHECK (phone_e164 ~ '^\+[1-9][0-9]{6,14}$');
-- Dropping the column also drops the old "password or Google" CHECK that referenced it.
ALTER TABLE users DROP COLUMN password_hash;
ALTER TABLE users ALTER COLUMN google_sub SET NOT NULL;

CREATE TABLE otp_challenges (
    id                 UUID PRIMARY KEY,
    phone_e164         VARCHAR(16) NOT NULL,
    purpose            VARCHAR(16) NOT NULL CHECK (purpose IN ('login', 'onboarding', 'change_phone', 'delete_account')),
    -- Who the code is for: an existing user, or (onboarding) the Google subject being onboarded.
    user_id            UUID REFERENCES users (id) ON DELETE CASCADE,
    onboarding_subject VARCHAR(255),
    code_hash          VARCHAR(64) NOT NULL,
    attempts           INT         NOT NULL DEFAULT 0,
    expires_at         TIMESTAMPTZ NOT NULL,
    consumed_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Resend cooldown and hourly cap look up the latest codes per number.
CREATE INDEX otp_challenges_phone_created ON otp_challenges (phone_e164, created_at DESC);
