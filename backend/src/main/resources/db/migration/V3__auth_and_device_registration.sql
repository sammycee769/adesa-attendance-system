-- Phase 3: authentication sessions and the registered-device lifecycle.
--
-- Every employee may have at most one APPROVED device (the mandatory second
-- factor for attendance) and at most one PENDING_APPROVAL registration at a
-- time. WebAuthn challenges are single-use and short-lived; device proofs are
-- single-use, session-bound assertions consumed by attendance actions.

-- Session invalidation counter: bumped on password change, role change,
-- deactivation and device revocation. Sessions store the value they were
-- created with, so any bump invalidates every existing session.
ALTER TABLE employees
    ADD COLUMN credentials_version INTEGER NOT NULL DEFAULT 1;

CREATE TABLE registered_devices (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id              UUID NOT NULL REFERENCES employees (id) ON DELETE CASCADE,
    -- Base64url encoded WebAuthn credential id (unique across all employees).
    credential_id            TEXT NOT NULL,
    -- Public WebAuthn attestation data (aaguid + credential id + COSE public
    -- key). Never contains private key material.
    attested_credential_data BYTEA NOT NULL,
    sign_count               BIGINT NOT NULL DEFAULT 0,
    -- Comma-separated AuthenticatorTransport values reported at registration.
    transports               VARCHAR(255),
    label                    VARCHAR(100),
    status                   VARCHAR(32) NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_at              TIMESTAMPTZ,
    approved_by              UUID REFERENCES employees (id),
    revoked_at               TIMESTAMPTZ,
    revoked_by               UUID REFERENCES employees (id),
    revoked_reason           VARCHAR(255),
    CONSTRAINT uq_registered_devices_credential_id UNIQUE (credential_id),
    CONSTRAINT ck_registered_devices_status
        CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REVOKED', 'REPLACED'))
);

-- At most one approved device per employee, and at most one pending request.
CREATE UNIQUE INDEX uq_registered_devices_one_approved
    ON registered_devices (employee_id) WHERE status = 'APPROVED';
CREATE UNIQUE INDEX uq_registered_devices_one_pending
    ON registered_devices (employee_id) WHERE status = 'PENDING_APPROVAL';
CREATE INDEX idx_registered_devices_employee ON registered_devices (employee_id);

CREATE TABLE webauthn_challenges (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id UUID NOT NULL REFERENCES employees (id) ON DELETE CASCADE,
    type        VARCHAR(32) NOT NULL,
    challenge   TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT ck_webauthn_challenges_type
        CHECK (type IN ('REGISTRATION', 'AUTHENTICATION'))
);

CREATE INDEX idx_webauthn_challenges_employee ON webauthn_challenges (employee_id, type);

CREATE TABLE device_proofs (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id  UUID NOT NULL REFERENCES employees (id) ON DELETE CASCADE,
    -- SHA-256 of the server-side session id that requested the proof.
    session_hash TEXT NOT NULL,
    device_id    UUID REFERENCES registered_devices (id) ON DELETE SET NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,
    consumed_at  TIMESTAMPTZ
);

CREATE INDEX idx_device_proofs_lookup ON device_proofs (employee_id, session_hash);
