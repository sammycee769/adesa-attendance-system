CREATE TABLE employees (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_number VARCHAR(32)  NOT NULL,
    full_name       VARCHAR(200) NOT NULL,
    email           VARCHAR(320) NOT NULL,
    password_hash   TEXT         NOT NULL,
    role            VARCHAR(32)  NOT NULL,
    is_active       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_employees_employee_number UNIQUE (employee_number),
    CONSTRAINT uq_employees_email UNIQUE (email),
    CONSTRAINT ck_employees_role CHECK (role IN ('EMPLOYEE', 'HR', 'ADMIN'))
);

CREATE INDEX idx_employees_is_active ON employees (is_active);
