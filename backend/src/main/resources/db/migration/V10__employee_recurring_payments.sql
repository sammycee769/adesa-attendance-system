-- Payment ownership is distinct from ADMIN management permissions.
ALTER TABLE payment_configs ADD COLUMN owner_employee_id uuid REFERENCES employees(id);
CREATE INDEX idx_payment_owner ON payment_configs(owner_employee_id);
UPDATE payment_configs c SET owner_employee_id=e.id FROM employees e
 WHERE lower(e.email)='christian@adesahq.com' AND c.code IN ('ZOHO','DATA','CHATGPT');
ALTER TABLE payment_requests ADD COLUMN owner_employee_id uuid REFERENCES employees(id);
UPDATE payment_requests r SET owner_employee_id=c.owner_employee_id FROM payment_configs c WHERE c.id=r.config_id;
CREATE INDEX idx_payment_request_owner ON payment_requests(owner_employee_id,billing_month);
