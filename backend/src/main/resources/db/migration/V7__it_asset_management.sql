-- Asset permissions are separate from attendance and account administration.
ALTER TABLE employees DROP CONSTRAINT ck_employees_role;
ALTER TABLE employees ADD CONSTRAINT ck_employees_role CHECK(role IN ('EMPLOYEE','HR','ADMIN','IT_ADMIN'));
CREATE SEQUENCE asset_laptop_number;
CREATE SEQUENCE asset_phone_number;
CREATE TABLE subsidiaries (
 id UUID PRIMARY KEY,
 version BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL,
 created_by UUID REFERENCES employees(id),
 updated_by UUID REFERENCES employees(id),
 name TEXT NOT NULL,
 active BOOLEAN NOT NULL
);
CREATE TABLE it_assets (
 id UUID PRIMARY KEY,
 version BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL,
 created_by UUID REFERENCES employees(id),
 updated_by UUID REFERENCES employees(id),
 asset_code TEXT NOT NULL,
 asset_type TEXT NOT NULL,
 brand TEXT NOT NULL,
 model TEXT NOT NULL,
 serial_number TEXT NOT NULL,
 purchase_date DATE NOT NULL,
 status TEXT NOT NULL,
 condition TEXT NOT NULL
);
CREATE TABLE asset_assignments (
 id UUID PRIMARY KEY,
 version BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL,
 created_by UUID REFERENCES employees(id),
 updated_by UUID REFERENCES employees(id),
 asset_id UUID REFERENCES it_assets(id) NOT NULL,
 employee_id UUID REFERENCES employees(id) NOT NULL,
 subsidiary_id UUID REFERENCES subsidiaries(id) NOT NULL,
 assignment_date DATE NOT NULL,
 status TEXT NOT NULL,
 approval_status TEXT NOT NULL,
 approved_by UUID REFERENCES employees(id),
 approval_date TIMESTAMPTZ,
 notes TEXT
);
CREATE TABLE asset_issues (
 id UUID PRIMARY KEY,
 version BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL,
 created_by UUID REFERENCES employees(id),
 updated_by UUID REFERENCES employees(id),
 asset_id UUID REFERENCES it_assets(id) NOT NULL,
 employee_id UUID REFERENCES employees(id) NOT NULL,
 title TEXT NOT NULL,
 description TEXT NOT NULL,
 category TEXT NOT NULL,
 priority TEXT NOT NULL,
 status TEXT NOT NULL,
 notes TEXT
);
CREATE TABLE asset_repairs (
 id UUID PRIMARY KEY,
 version BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL,
 created_by UUID REFERENCES employees(id),
 updated_by UUID REFERENCES employees(id),
 asset_id UUID REFERENCES it_assets(id) NOT NULL,
 issue_id UUID REFERENCES asset_issues(id),
 description TEXT NOT NULL,
 repair_type TEXT NOT NULL,
 status TEXT NOT NULL,
 decision TEXT,
 technician TEXT,
 estimated_cost NUMERIC(14,2),
 actual_cost NUMERIC(14,2),
 submitted_date DATE NOT NULL,
 approved_date DATE,
 start_date DATE,
 completion_date DATE,
 approved_by UUID REFERENCES employees(id),
 notes TEXT
);
CREATE TABLE asset_replacement_requests (
 id UUID PRIMARY KEY,
 version BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL,
 created_by UUID REFERENCES employees(id),
 updated_by UUID REFERENCES employees(id),
 asset_id UUID REFERENCES it_assets(id) NOT NULL,
 employee_id UUID REFERENCES employees(id) NOT NULL,
 reason TEXT NOT NULL,
 description TEXT NOT NULL,
 status TEXT NOT NULL,
 reviewed_by UUID REFERENCES employees(id),
 approval_date TIMESTAMPTZ,
 notes TEXT,
 replacement_asset_id UUID REFERENCES it_assets(id)
);
CREATE TABLE asset_returns (
 id UUID PRIMARY KEY,
 version BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL,
 created_by UUID REFERENCES employees(id),
 updated_by UUID REFERENCES employees(id),
 asset_id UUID REFERENCES it_assets(id) NOT NULL,
 employee_id UUID REFERENCES employees(id) NOT NULL,
 assignment_id UUID REFERENCES asset_assignments(id) NOT NULL,
 request_date DATE NOT NULL,
 expected_date DATE NOT NULL,
 actual_date DATE,
 condition TEXT,
 status TEXT NOT NULL,
 received_by UUID REFERENCES employees(id),
 notes TEXT
);
CREATE TABLE employee_asset_clearances (
 id UUID PRIMARY KEY,
 version BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL,
 created_by UUID REFERENCES employees(id),
 updated_by UUID REFERENCES employees(id),
 employee_id UUID REFERENCES employees(id) NOT NULL,
 status TEXT NOT NULL,
 clearance_date TIMESTAMPTZ,
 cleared_by UUID REFERENCES employees(id),
 notes TEXT
);
CREATE TABLE employee_clearance_items (
 id UUID PRIMARY KEY,
 version BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL,
 created_by UUID REFERENCES employees(id),
 updated_by UUID REFERENCES employees(id),
 clearance_id UUID REFERENCES employee_asset_clearances(id) NOT NULL,
 assignment_id UUID REFERENCES asset_assignments(id) NOT NULL
);
CREATE TABLE asset_audit_logs (
 id UUID PRIMARY KEY,
 version BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL,
 created_by UUID REFERENCES employees(id),
 updated_by UUID REFERENCES employees(id),
 asset_id UUID REFERENCES it_assets(id),
 employee_id UUID REFERENCES employees(id),
 action TEXT NOT NULL,
 previous_state TEXT,
 new_state TEXT,
 notes TEXT
);

CREATE UNIQUE INDEX uq_asset_code ON it_assets(asset_code);
CREATE UNIQUE INDEX uq_asset_serial ON it_assets(lower(serial_number));
CREATE UNIQUE INDEX uq_subsidiary_name ON subsidiaries(lower(name));
CREATE UNIQUE INDEX uq_assignment_active ON asset_assignments(asset_id) WHERE status='ACTIVE';
CREATE UNIQUE INDEX uq_replacement_unresolved ON asset_replacement_requests(asset_id) WHERE status IN ('PENDING','UNDER_REVIEW','APPROVED');
CREATE UNIQUE INDEX uq_return_pending ON asset_returns(assignment_id) WHERE status IN ('PENDING','OVERDUE');
CREATE UNIQUE INDEX uq_clearance_pending ON employee_asset_clearances(employee_id) WHERE status IN ('PENDING','NOT_CLEARED');
CREATE UNIQUE INDEX uq_clearance_item ON employee_clearance_items(clearance_id,assignment_id);
CREATE UNIQUE INDEX uq_repair_open ON asset_repairs(asset_id) WHERE status IN ('REPORTED','UNDER_REVIEW','APPROVED','IN_PROGRESS');
ALTER TABLE it_assets ADD CHECK(asset_type IN ('LAPTOP','MOBILE_PHONE'));
ALTER TABLE it_assets ADD CHECK(status IN ('AVAILABLE','ASSIGNED','UNDER_REPAIR','PENDING_REPLACEMENT','RETIRED','LOST','DISPOSED'));
ALTER TABLE it_assets ADD CHECK(condition IN ('NEW','GOOD','FAIR','DAMAGED','UNUSABLE'));
ALTER TABLE it_assets ADD CHECK(purchase_date <= CURRENT_DATE);
ALTER TABLE asset_assignments ADD CHECK(status IN ('ACTIVE','RETURNED','CANCELLED'));
ALTER TABLE asset_assignments ADD CHECK(approval_status IN ('PENDING','APPROVED','REJECTED'));
ALTER TABLE asset_issues ADD CHECK(status IN ('OPEN','UNDER_REVIEW','IN_PROGRESS','RESOLVED','CLOSED'));
ALTER TABLE asset_issues ADD CHECK(priority IN ('LOW','MEDIUM','HIGH','CRITICAL'));
ALTER TABLE asset_issues ADD CHECK(category IN ('HARDWARE','SOFTWARE','BATTERY','SCREEN','KEYBOARD','NETWORK','PHYSICAL_DAMAGE','OTHER'));
ALTER TABLE asset_repairs ADD CHECK(status IN ('REPORTED','UNDER_REVIEW','APPROVED','IN_PROGRESS','COMPLETED','REJECTED','CANCELLED'));
ALTER TABLE asset_repairs ADD CHECK(repair_type IN ('INTERNAL','EXTERNAL'));
ALTER TABLE asset_repairs ADD CHECK(decision IN ('REPAIR','REPLACE','REJECT'));
ALTER TABLE asset_repairs ADD CHECK(estimated_cost >= 0 AND actual_cost >= 0);
ALTER TABLE asset_replacement_requests ADD CHECK(status IN ('PENDING','UNDER_REVIEW','APPROVED','REJECTED','FULFILLED'));
ALTER TABLE asset_replacement_requests ADD CHECK(reason IN ('DAMAGED','FAULTY','OBSOLETE','PERFORMANCE_ISSUES','OTHER'));
ALTER TABLE asset_returns ADD CHECK(status IN ('PENDING','OVERDUE','RETURNED','CANCELLED'));
ALTER TABLE employee_asset_clearances ADD CHECK(status IN ('PENDING','NOT_CLEARED','CLEARED'));
CREATE INDEX idx_asset_status ON it_assets(status);
CREATE INDEX idx_asset_condition ON it_assets(condition);
CREATE INDEX idx_assignment_employee ON asset_assignments(employee_id,status);
CREATE INDEX idx_assignment_subsidiary ON asset_assignments(subsidiary_id);
CREATE INDEX idx_issue_asset ON asset_issues(asset_id,status);
CREATE INDEX idx_issue_employee ON asset_issues(employee_id);
CREATE INDEX idx_repair_asset ON asset_repairs(asset_id,status);
CREATE INDEX idx_replacement_employee ON asset_replacement_requests(employee_id);
CREATE INDEX idx_return_employee ON asset_returns(employee_id);
CREATE INDEX idx_audit_asset ON asset_audit_logs(asset_id,created_at DESC);
CREATE INDEX idx_audit_employee ON asset_audit_logs(employee_id,created_at DESC);
-- Enforce status/assignment integrity even for writers outside the application.
CREATE FUNCTION check_asset_assignment_integrity() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target UUID;
BEGIN
 IF TG_TABLE_NAME = 'it_assets' THEN target := NEW.id; ELSE target := NEW.asset_id; END IF;
 IF EXISTS (SELECT 1 FROM it_assets a JOIN asset_assignments x ON x.asset_id=a.id
   WHERE a.id=target AND a.status='AVAILABLE' AND x.status='ACTIVE' AND x.approval_status='APPROVED') THEN
   RAISE EXCEPTION 'An approved assigned asset cannot be available' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER asset_assignment_status AFTER INSERT OR UPDATE ON it_assets DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_asset_assignment_integrity();
CREATE CONSTRAINT TRIGGER assignment_asset_status AFTER INSERT OR UPDATE ON asset_assignments DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_asset_assignment_integrity();
CREATE FUNCTION protect_asset_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Asset audit history is immutable'; END $$;
CREATE TRIGGER immutable_asset_audit BEFORE UPDATE OR DELETE ON asset_audit_logs FOR EACH ROW EXECUTE FUNCTION protect_asset_audit();
-- Optional current employee affiliation; assignment subsidiary is a permanent snapshot.
ALTER TABLE employees ADD COLUMN subsidiary_id UUID REFERENCES subsidiaries(id);
CREATE INDEX idx_employee_subsidiary ON employees(subsidiary_id);
