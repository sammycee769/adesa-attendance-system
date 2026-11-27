-- Isolated vendor records. No operational tables or existing access policies are changed.
create schema vendor_private;
revoke all on schema vendor_private from public;
create table vendor_private.link (
 id uuid primary key, subsidiary varchar(20) not null check(subsidiary in ('HOLDINGS','ENERGY','MEDIA','SERVICES','DISTRIBUTION')),
 label varchar(160) not null, kind varchar(16) not null check(kind in ('REUSABLE','INVITATION')),
 token_hash varchar(64) unique not null, token_cipher bytea not null, invited_email_hash varchar(64),
 active boolean not null default true, revoked_at timestamptz, expires_at timestamptz,
 uses integer not null default 0, created_at timestamptz not null default now(), created_by varchar(254) not null
);
create table vendor_private.application (
 id uuid primary key, reference varchar(50) unique not null, subsidiary varchar(20) not null,
 link_id uuid references vendor_private.link(id), entity_id uuid not null,
 status varchar(32) not null default 'PENDING_REVIEW' check(status in ('PENDING_REVIEW','UNDER_REVIEW','ADDITIONAL_INFORMATION_REQUIRED','APPROVED','REJECTED','SUSPENDED','ARCHIVED')),
 banking_status varchar(16) not null default 'NOT_PROVIDED' check(banking_status in ('NOT_PROVIDED','UNVERIFIED','VERIFIED','REJECTED')),
 banking_verified_by varchar(254), banking_verified_at timestamptz, conflict boolean not null,
 categories text[] not null, duplicate_ids uuid[] not null default '{}', current_revision integer not null default 1,
 submitted_at timestamptz not null default now(), updated_at timestamptz not null default now()
);
create table vendor_private.request (
 id uuid primary key, application_id uuid not null references vendor_private.application(id), token_hash varchar(64) unique not null,
 token_cipher bytea not null, message_cipher bytea not null, expires_at timestamptz not null, consumed_at timestamptz,
 created_by varchar(254) not null, created_at timestamptz not null default now()
);
create table vendor_private.session (
 id uuid primary key, link_id uuid references vendor_private.link(id), request_id uuid references vendor_private.request(id),
 subsidiary varchar(20) not null, token_hash varchar(64) unique not null, expires_at timestamptz not null,
 application_id uuid references vendor_private.application(id), submitted boolean not null default false, created_at timestamptz not null default now(),
 check((link_id is null)<>(request_id is null))
);
create table vendor_private.revision (
 id uuid primary key, application_id uuid not null references vendor_private.application(id), session_id uuid unique not null references vendor_private.session(id),
 number integer not null, payload_cipher bytea not null, signature_id uuid not null,
 content_hash varchar(64) not null, form_version varchar(40) not null, submitted_at timestamptz not null default now(),
 unique(application_id,number)
);
create table vendor_private.document (
 id uuid primary key, session_id uuid not null references vendor_private.session(id), application_id uuid references vendor_private.application(id),
 category varchar(40) not null, filename varchar(180) not null, declared_type varchar(60) not null, mime_type varchar(60),
 expected_size integer not null check(expected_size between 1 and 10485760), received_size integer not null default 0,
 chunks integer not null default 0, body_cipher bytea not null, content_hash varchar(64), ready boolean not null default false,
 created_at timestamptz not null default now(), check(received_size<=expected_size)
);
create table vendor_private.duplicate_index (application_id uuid not null references vendor_private.application(id), kind varchar(12) not null, fingerprint varchar(64) not null, primary key(application_id,kind));
create index vendor_duplicate_lookup on vendor_private.duplicate_index(kind,fingerprint);
create table vendor_private.audit (id bigserial primary key,application_id uuid references vendor_private.application(id),actor varchar(254) not null,action varchar(50) not null,detail_cipher bytea not null,created_at timestamptz not null default now());
create table vendor_private.note (id uuid primary key,application_id uuid not null references vendor_private.application(id),body_cipher bytea not null,created_by varchar(254) not null,created_at timestamptz not null default now());
create table vendor_private.notice (id uuid primary key, application_id uuid not null references vendor_private.application(id), recipient_id uuid not null, kind varchar(40) not null, read_at timestamptz,created_at timestamptz not null default now());
create table vendor_private.outbox (id uuid primary key,application_id uuid not null references vendor_private.application(id),recipient_cipher bytea not null,body_cipher bytea not null,subject varchar(160) not null,internal boolean not null,state varchar(16) not null default 'PREVIEW',created_at timestamptz not null default now(),delivered_at timestamptz);
create table vendor_private.download (token_hash varchar(64) primary key, document_id uuid not null references vendor_private.document(id),employee_id uuid not null,expires_at timestamptz not null,consumed_at timestamptz);
create table vendor_private.rate (key_hash varchar(64) not null,bucket bigint not null,hits integer not null default 1,primary key(key_hash,bucket));
create index vendor_app_filter on vendor_private.application(subsidiary,status,submitted_at desc);
create index vendor_doc_session on vendor_private.document(session_id);
create index vendor_doc_app on vendor_private.document(application_id);
-- Signed data and original documents cannot be overwritten or ordinarily deleted, even by the app owner.
create function vendor_private.immutable_record() returns trigger language plpgsql as $$ begin raise exception 'Signed vendor records are immutable; append a new signed revision'; end $$;
create trigger vendor_revision_immutable before update or delete on vendor_private.revision for each row execute function vendor_private.immutable_record();
create function vendor_private.protect_document() returns trigger language plpgsql as $$ begin
 if old.application_id is not null then raise exception 'Submitted vendor documents are immutable'; end if;
 return case when TG_OP='DELETE' then old else new end;
end $$;
create trigger vendor_doc_immutable before update or delete on vendor_private.document for each row execute function vendor_private.protect_document();
-- Context is set transaction-locally by authenticated vendor services; public database roles receive no access.
alter table vendor_private.application enable row level security;
alter table vendor_private.application force row level security;
create policy vendor_app_context on vendor_private.application using(current_setting('vendor.access',true)='ADMIN' or id::text=current_setting('vendor.application',true)) with check(current_setting('vendor.access',true)='ADMIN' or id::text=current_setting('vendor.application',true));
alter table vendor_private.revision enable row level security;
alter table vendor_private.revision force row level security;
create policy vendor_revision_context on vendor_private.revision using(current_setting('vendor.access',true)='ADMIN' or application_id::text=current_setting('vendor.application',true)) with check(current_setting('vendor.access',true)='ADMIN' or (application_id::text=current_setting('vendor.application',true) and session_id::text=current_setting('vendor.session',true)));
alter table vendor_private.document enable row level security;
alter table vendor_private.document force row level security;
create policy vendor_document_context on vendor_private.document using(current_setting('vendor.access',true)='ADMIN' or session_id::text=current_setting('vendor.session',true) or application_id::text=current_setting('vendor.application',true)) with check(current_setting('vendor.access',true)='ADMIN' or session_id::text=current_setting('vendor.session',true));
-- Staff-only records. Workflow may append notifications/audit, never read staff notes.
do $$ declare t text; begin foreach t in array array['audit','note','notice','outbox','download'] loop
 execute format('alter table vendor_private.%I enable row level security',t);
 execute format('alter table vendor_private.%I force row level security',t);
 execute format('create policy vendor_admin on vendor_private.%I using(current_setting(''vendor.access'',true)=''ADMIN'') with check(current_setting(''vendor.access'',true)=''ADMIN'')',t);
 if t in ('audit','notice','outbox') then execute format('create policy vendor_workflow_append on vendor_private.%I for insert with check(current_setting(''vendor.access'',true)=''WORKFLOW'')',t); end if;
 end loop;end $$;
revoke all on all tables in schema vendor_private from public;
revoke all on all sequences in schema vendor_private from public;
revoke all on all functions in schema vendor_private from public;
do $$ declare r text; begin foreach r in array array['anon','authenticated'] loop if exists(select from pg_roles where rolname=r) then execute format('revoke all on schema vendor_private from %I',r);execute format('revoke all on all tables in schema vendor_private from %I',r);end if;end loop;end $$;
