-- Attendance corrections (Phase 7: HR and admin interfaces).
--
-- Every manual correction of an attendance record leaves an immutable trail:
-- the previous values, the new values, the mandatory reason and the actor.
-- The record itself keeps only the current values; this table preserves the
-- history that the master prompt requires ("Do not silently overwrite records.
-- Preserve sufficient history for authorized review.").
--
-- The audit log (audit_logs) carries the same facts in JSONB; this table keeps
-- them queryable next to the record they belong to.

CREATE TABLE attendance_corrections (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    attendance_record_id  UUID NOT NULL REFERENCES attendance_records(id) ON DELETE CASCADE,
    employee_id           UUID NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
    previous_sign_in_at   TIMESTAMPTZ,
    previous_sign_out_at  TIMESTAMPTZ,
    corrected_sign_in_at  TIMESTAMPTZ NOT NULL,
    corrected_sign_out_at TIMESTAMPTZ,
    reason                VARCHAR(500) NOT NULL,
    actor_id              UUID REFERENCES employees(id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_attendance_corrections_sign_out_not_before_sign_in
        CHECK (corrected_sign_out_at IS NULL OR corrected_sign_out_at >= corrected_sign_in_at)
);

CREATE INDEX idx_attendance_corrections_record
    ON attendance_corrections (attendance_record_id, created_at DESC);
CREATE INDEX idx_attendance_corrections_employee
    ON attendance_corrections (employee_id, created_at DESC);

-- The registered_devices schema calls for a last-used timestamp: it is the
-- only reliable way to tell a forgotten device from a forgotten password.
ALTER TABLE registered_devices ADD COLUMN last_used_at TIMESTAMPTZ;
