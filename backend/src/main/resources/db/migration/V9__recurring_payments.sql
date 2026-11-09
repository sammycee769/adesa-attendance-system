-- Additive only: attendance and asset tables are untouched.
CREATE TABLE payment_configs (
 id uuid PRIMARY KEY, code varchar(30) UNIQUE NOT NULL, name varchar(150) NOT NULL,
 amount numeric(14,2) NOT NULL CHECK(amount>0), currency varchar(3) NOT NULL,
 estimated_ngn varchar(100) NOT NULL DEFAULT '', scheduled_day integer NOT NULL CHECK(scheduled_day BETWEEN 1 AND 28),
 renewal_day integer CHECK(renewal_day BETWEEN 1 AND 28), description varchar(2000) NOT NULL DEFAULT '',
 to_address varchar(500) NOT NULL, cc_addresses varchar(2000) NOT NULL,
 subject varchar(300) NOT NULL, body text NOT NULL, enabled boolean NOT NULL DEFAULT true,
 version integer NOT NULL DEFAULT 0
);
CREATE TABLE payment_automation (
 id integer PRIMARY KEY CHECK(id=1), enabled boolean NOT NULL DEFAULT false,
 signature_html text NOT NULL DEFAULT '', updated_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO payment_automation(id) VALUES(1) ON CONFLICT DO NOTHING;
CREATE TABLE payment_requests (
 id uuid PRIMARY KEY, config_id uuid NOT NULL REFERENCES payment_configs(id), billing_month date NOT NULL,
 scheduled_date date NOT NULL, send_date date NOT NULL, status varchar(25) NOT NULL,
 snapshot text NOT NULL, attempts integer NOT NULL DEFAULT 0, next_attempt_at timestamptz, delivery_deadline timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 sent_at timestamptz, provider_message_id varchar(300), UNIQUE(config_id,billing_month),
 CHECK(status IN ('PREVIEW','MISSED','READY','SENDING','RETRY','FAILED','UNKNOWN','SENT','MOCK_CAPTURED'))
);
CREATE TABLE payment_delivery_attempts (
 id uuid PRIMARY KEY, request_id uuid NOT NULL REFERENCES payment_requests(id), attempt integer NOT NULL,
 started_at timestamptz NOT NULL DEFAULT now(), finished_at timestamptz, result varchar(30) NOT NULL,
 provider_message_id varchar(300), detail varchar(300), UNIQUE(request_id,attempt)
);
CREATE TABLE payment_audit (
 id bigserial PRIMARY KEY, actor varchar(200) NOT NULL, action varchar(100) NOT NULL,
 target varchar(100) NOT NULL, detail text NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX payment_requests_pending ON payment_requests(status,next_attempt_at);
INSERT INTO payment_configs(id,code,name,amount,currency,estimated_ngn,scheduled_day,renewal_day,to_address,cc_addresses,subject,body) VALUES
('90000000-0000-0000-0000-000000000001','ZOHO','Zoho Subscription Renewal',72842,'NGN','',16,null,
'Finance Manager <finance@adesahq.com>','Oluwafemi Adeleye <femi@adesahq.com>, Mercy Agbo <payment@adesahq.com>, Blessing Gbadeyan <blessing@adesahq.com>',
'Request for Payment – Zoho Subscription Renewal', E'Dear Finance Team,\n\nI would like to request payment of {{amount}} for the renewal of the company''s Zoho subscription.\n\nKindly process the payment promptly to ensure uninterrupted access to the Zoho services used for company operations.\n\nThank you for your prompt attention.\n\nBest regards,'),
('90000000-0000-0000-0000-000000000002','DATA','Monthly Internet Data Subscription',25000,'NGN','',20,null,
'Finance Manager <finance@adesahq.com>','Oluwafemi Adeleye <femi@adesahq.com>, Mercy Agbo <payment@adesahq.com>, Blessing Gbadeyan <blessing@adesahq.com>',
'Request for Payment – Monthly Data Subscription', E'Dear Finance Team,\n\nI would like to request payment of {{amount}} for the company''s monthly internet data subscription.\n\nKindly process the payment to ensure uninterrupted internet connectivity and smooth daily operations.\n\nThank you for your prompt attention.\n\nBest regards,'),
('90000000-0000-0000-0000-000000000003','CHATGPT','ChatGPT Plus Subscription Renewal',20,'USD','₦30,000–₦33,000',28,28,
'Finance Manager <finance@adesahq.com>','Oluwafemi Adeleye <femi@adesahq.com>, Mercy Agbo <payment@adesahq.com>, Blessing Gbadeyan <blessing@adesahq.com>',
'Request for Payment – ChatGPT Plus Subscription Renewal', E'Dear Finance Team,\n\nI would like to request payment for the renewal of the company''s ChatGPT Plus subscription, which is due on the {{renewal_day}}th of this month.\n\nThe subscription costs {{amount}} per month, with an estimated naira equivalent of {{estimated_ngn}}, depending on the prevailing exchange rate.\n\nKindly process the payment promptly to ensure uninterrupted access to the service.\n\nThank you for your prompt attention.\n\nBest regards,')
ON CONFLICT(code) DO NOTHING;
