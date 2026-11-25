-- Authoritative daily schedule snapshots supplied from approved HR records.
-- No history is inferred from an employee account's creation/update timestamps.
CREATE SCHEMA attendance_private;
REVOKE ALL ON SCHEMA attendance_private FROM PUBLIC;

CREATE TABLE attendance_private.daily_roster (
    workday date NOT NULL,
    employee_id uuid NOT NULL REFERENCES public.employees(id),
    employment_start date NOT NULL,
    employment_end date,
    subsidiary_id uuid REFERENCES public.subsidiaries(id),
    department varchar(120),
    arrangement varchar(20) NOT NULL CHECK
        (arrangement IN ('OFFICE','REMOTE','APPROVED_LEAVE','EXCUSED_ABSENCE','NOT_SCHEDULED')),
    late_after timestamptz,
    absence_at timestamptz,
    shift_end timestamptz,
    remarks varchar(500),
    source_reference varchar(200) NOT NULL CHECK (length(btrim(source_reference)) > 0),
    verified_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workday, employee_id),
    CHECK (workday >= employment_start AND (employment_end IS NULL OR workday <= employment_end)),
    CHECK (arrangement <> 'OFFICE' OR (absence_at IS NOT NULL AND shift_end IS NOT NULL)),
    CHECK (absence_at IS NULL OR absence_at <= shift_end),
    CHECK (late_after IS NULL OR late_after <= shift_end),
    CHECK (shift_end IS NULL OR (shift_end AT TIME ZONE 'Africa/Lagos')::date BETWEEN workday AND workday + 1),
    CHECK (absence_at IS NULL OR (absence_at AT TIME ZONE 'Africa/Lagos')::date BETWEEN workday AND workday + 1),
    CHECK (late_after IS NULL OR (late_after AT TIME ZONE 'Africa/Lagos')::date BETWEEN workday AND workday + 1)
);
REVOKE ALL ON attendance_private.daily_roster FROM PUBLIC;
