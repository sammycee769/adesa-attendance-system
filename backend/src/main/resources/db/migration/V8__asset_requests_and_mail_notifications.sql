CREATE TABLE asset_requests (
 id UUID PRIMARY KEY,
 version BIGINT NOT NULL DEFAULT 0,
 asset_id UUID NOT NULL REFERENCES it_assets(id),
 employee_id UUID NOT NULL REFERENCES employees(id),
 subsidiary_id UUID NOT NULL REFERENCES subsidiaries(id),
 reason TEXT NOT NULL,
 status TEXT NOT NULL CHECK(status IN ('PENDING','APPROVED','REJECTED')),
 assignment_id UUID UNIQUE REFERENCES asset_assignments(id),
 reviewed_by UUID REFERENCES employees(id),
 reviewed_at TIMESTAMPTZ,
 notes TEXT,
 created_at TIMESTAMPTZ NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL,
 created_by UUID REFERENCES employees(id),
 updated_by UUID REFERENCES employees(id),
 CHECK(status <> 'APPROVED' OR (assignment_id IS NOT NULL AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL))
);
-- Pending employee requests reserve devices, just like pending IT assignments.
CREATE UNIQUE INDEX uq_asset_request_pending ON asset_requests(asset_id) WHERE status='PENDING';
CREATE INDEX idx_asset_request_employee ON asset_requests(employee_id,created_at DESC);
CREATE INDEX idx_asset_request_status ON asset_requests(status,created_at DESC);

ALTER TABLE asset_repairs ADD COLUMN employee_id UUID REFERENCES employees(id);
UPDATE asset_repairs r SET employee_id=coalesce(
 (SELECT x.employee_id FROM asset_assignments x WHERE x.asset_id=r.asset_id AND x.status='ACTIVE' AND x.approval_status='APPROVED' LIMIT 1),
 r.created_by);
CREATE INDEX idx_repair_employee ON asset_repairs(employee_id,created_at DESC);

-- Inserted in the same transaction as the request/approval. Delivery only sees committed rows.
CREATE TABLE asset_mail_outbox (
 id UUID PRIMARY KEY,
 version BIGINT NOT NULL DEFAULT 0,
 asset_id UUID NOT NULL REFERENCES it_assets(id),
 request_id UUID NOT NULL,
 event_type TEXT NOT NULL,
 recipient_email TEXT NOT NULL,
 subject TEXT NOT NULL,
 text_body TEXT NOT NULL,
 status TEXT NOT NULL CHECK(status IN ('PENDING','RETRY','SENT','LOCAL_CAPTURED','FAILED')),
 attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts >= 0),
 next_attempt_at TIMESTAMPTZ NOT NULL,
 delivered_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL,
 created_by UUID REFERENCES employees(id),
 updated_by UUID REFERENCES employees(id),
 UNIQUE(event_type,request_id,recipient_email)
);
CREATE INDEX idx_asset_mail_pending ON asset_mail_outbox(next_attempt_at) WHERE status IN ('PENDING','RETRY');
