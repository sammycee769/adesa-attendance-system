-- Phase: password setup, forgot-password and forced first-login change.
--
-- Two additions:
--
-- 1. employees.must_change_password — an explicit, server-enforced account
--    state. When true the account may only reach the minimum endpoints needed
--    to set a new password (and sign out); the normal application and every
--    administrative action are refused until the password has been changed.
--    Existing accounts default to FALSE so no current employee is affected and
--    development attendance history is preserved untouched.
--
-- 2. account_tokens — single-use, short-lived tokens backing two flows:
--      * ACCOUNT_SETUP     — emailed when an administrator creates an account;
--                            lets the employee choose their own first password.
--      * PASSWORD_RECOVERY — emailed from the "forgot password" form.
--
--    Only a SHA-256 hash of each token is stored (never the token itself), the
--    token is consumed atomically (the UPDATE ... WHERE consumed_at IS NULL
--    guard makes a double click fail the second time) and rows are deleted or
--    left consumed so a token can never be replayed.

ALTER TABLE employees
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE account_tokens (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id  UUID NOT NULL REFERENCES employees (id) ON DELETE CASCADE,
    -- SHA-256 (hex) of the opaque token that was emailed. The raw token is
    -- never stored, so a database leak cannot be turned into a reset link.
    token_hash   TEXT NOT NULL,
    type         VARCHAR(32) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,
    consumed_at  TIMESTAMPTZ,
    CONSTRAINT uq_account_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_account_tokens_type
        CHECK (type IN ('ACCOUNT_SETUP', 'PASSWORD_RECOVERY'))
);

-- Lookup is by token hash (the only value the client presents).
CREATE UNIQUE INDEX uq_account_tokens_hash ON account_tokens (token_hash);
-- Used to retire a previous outstanding token of the same type for an employee
-- before issuing a fresh one.
CREATE INDEX idx_account_tokens_employee_type
    ON account_tokens (employee_id, type, created_at DESC);
