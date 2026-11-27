create schema campaign_private;
revoke all on schema campaign_private from public;
-- Additive, isolated campaign tables. Existing operational tables are not altered.
create table campaign_private.event (
 id uuid primary key, slug varchar(80) unique not null, name varchar(160) not null,
 description text not null, starts_at timestamptz not null, ends_at timestamptz not null,
 active boolean not null default false, approved boolean not null default false, demo boolean not null default false,
 config jsonb not null, created_at timestamptz not null default now(), check(ends_at>starts_at)
);
create table campaign_private.prize (
 id uuid primary key, event_id uuid not null references campaign_private.event(id), business varchar(12) not null check(business in ('ENERGY','MEDIA','SERVICES','GENERAL')),
 name varchar(180) not null, category varchar(16) not null check(category in ('REGULAR','CONSULTATION','PREMIUM','GIFT','GRAND')),
 value_ngn bigint check(value_ngn is null or value_ngn between 1 and 100000000), quantity integer not null check(quantity>=0), issued integer not null default 0 check(issued>=0),
 approved boolean not null default false, active boolean not null default false, terms text not null,
 check(issued<=quantity)
);
create table campaign_private.participant (
 id uuid primary key, event_id uuid not null references campaign_private.event(id), full_name varchar(120) not null,
 email varchar(254) not null, phone varchar(20) not null, company varchar(160) not null default '', industry varchar(120) not null default '',
 interest varchar(12) not null check(interest in ('ENERGY','MEDIA','SERVICES','ALL')), terms_version varchar(40) not null,
 marketing_consent boolean not null default false, registered_at timestamptz not null default now(),
 session_hash varchar(64) unique, session_expires timestamptz, converted boolean not null default false, transaction_ref varchar(180),
 unique(event_id,email), unique(event_id,phone)
);
create table campaign_private.attempt (
 id uuid primary key, participant_id uuid unique not null references campaign_private.participant(id),
 game varchar(12) not null check(game in ('SPIN','MATCH','SCRATCH','TRIVIA')), status varchar(16) not null,
 started_at timestamptz not null, deadline timestamptz, sequence integer not null default 0,
 state jsonb not null, tier varchar(16), completed_at timestamptz
);
create table campaign_private.voucher (
 id uuid primary key, participant_id uuid unique not null references campaign_private.participant(id), prize_id uuid not null references campaign_private.prize(id),
 code varchar(64) unique not null, issued_at timestamptz not null, expires_at timestamptz not null,
 reward_snapshot jsonb not null, requested_category varchar(16) not null,
 redeemed_at timestamptz, redeemed_by varchar(254), redemption_ref varchar(180),
 check(expires_at>issued_at)
);
create table campaign_private.recovery (
 token_hash varchar(64) primary key, participant_id uuid not null references campaign_private.participant(id), expires_at timestamptz not null, consumed_at timestamptz
);
create table campaign_private.audit (
 id bigserial primary key, event_id uuid references campaign_private.event(id), actor varchar(254) not null,
 action varchar(80) not null, detail jsonb not null, created_at timestamptz not null default now()
);
create table campaign_private.message (
 id uuid primary key, event_id uuid not null references campaign_private.event(id), recipient varchar(254) not null,
 kind varchar(40) not null, subject varchar(200) not null, body text not null,
 dedupe_key varchar(180) unique not null, state varchar(16) not null default 'PREVIEW',
 created_at timestamptz not null default now(), delivered_at timestamptz, attempts integer not null default 0
);
create table campaign_private.rate (
 key_hash varchar(64) not null, bucket bigint not null, hits integer not null default 1,
 primary key(key_hash,bucket)
);
create index campaign_attempt_deadline on campaign_private.attempt(deadline) where status='PLAYING';
create index campaign_messages_pending on campaign_private.message(state,created_at);
create index campaign_participants_event on campaign_private.participant(event_id,registered_at);

create table campaign_private.staff (email varchar(254) primary key, capability varchar(12) not null check(capability in ('MANAGE','REDEEM','VIEW')));
revoke all on all tables in schema campaign_private from public;
do $$ begin
 if exists(select from pg_roles where rolname='anon') then execute 'revoke all on schema campaign_private from anon';execute 'revoke all on all tables in schema campaign_private from anon';end if;
 if exists(select from pg_roles where rolname='authenticated') then execute 'revoke all on schema campaign_private from authenticated';execute 'revoke all on all tables in schema campaign_private from authenticated';end if;
end $$;
