CREATE TABLE media_private.reminder_recipients (
    employee_id uuid PRIMARY KEY REFERENCES media_private.memberships(employee_id),
    verified_email text NOT NULL CHECK (length(verified_email) BETWEEN 3 AND 320 AND verified_email ~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$'),
    verified_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE media_private.reminders (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    entity text NOT NULL CHECK (entity IN ('CONTRACT','OBLIGATION')),
    entity_id uuid NOT NULL,
    revision int NOT NULL,
    stage int NOT NULL CHECK (stage IN (0,7,30)),
    reminder_day date NOT NULL,
    recipient_id uuid NOT NULL REFERENCES public.employees(id),
    verified_email text NOT NULL,
    status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENDING','RETRY','SENT','LOCAL_CAPTURED','CANCELLED','FAILED','UNKNOWN')),
    attempts int NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    claim_token uuid,
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(entity,entity_id,revision,stage,recipient_id)
);
CREATE INDEX media_reminders_ready ON media_private.reminders(reminder_day,next_attempt_at)
    WHERE status IN ('PENDING','RETRY');

CREATE TABLE media_private.reminder_attempts (
    reminder_id uuid NOT NULL REFERENCES media_private.reminders(id),
    attempt int NOT NULL,
    status text NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (reminder_id,attempt)
);
CREATE TRIGGER media_reminder_attempts_immutable BEFORE UPDATE OR DELETE OR TRUNCATE
    ON media_private.reminder_attempts FOR EACH STATEMENT EXECUTE FUNCTION media_private.reject_audit_mutation();

CREATE FUNCTION media_private.verified_recipients()
RETURNS TABLE(recipient uuid, email text)
LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
    SELECT e.id, e.email FROM media_private.reminder_recipients v
    JOIN media_private.memberships m USING (employee_id)
    JOIN public.employees e ON e.id=v.employee_id
    WHERE e.is_active AND lower(e.email)=lower(v.verified_email)
      AND m.category IN ('BUSINESS_MANAGER','COO','OPERATOR')
$$;

CREATE FUNCTION media_private.reminder_candidates(day date)
RETURNS TABLE(entity text, entity_id uuid, revision int, stage int, payload jsonb)
LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
    SELECT 'CONTRACT',c.id,c.revision,s.offset_days,
       jsonb_build_object('board',b.code,'location',b.location,'vendor',v.name,'client',p.name,
           'side',c.kind,'start',c.start_date,'date',c.end_date,'amount',c.amount,'boardId',b.id)
    FROM media_private.contracts c JOIN media_private.boards b ON b.id=c.board_id
    LEFT JOIN media_private.parties v ON v.id=b.vendor_id
    LEFT JOIN media_private.parties p ON p.id=b.client_id
    JOIN media_private.parties cp ON cp.id=c.party_id
    CROSS JOIN (VALUES(30),(7)) s(offset_days)
    WHERE c.confirmed AND c.archived_at IS NULL AND b.archived_at IS NULL AND cp.archived_at IS NULL
      AND c.end_date-s.offset_days=day
      AND NOT EXISTS (SELECT 1 FROM media_private.contracts n WHERE n.renews_id=c.id AND n.archived_at IS NULL)
    UNION ALL
    SELECT 'OBLIGATION',o.id,o.revision,s.offset_days,
       jsonb_build_object('board',b.code,'location',b.location,'vendor',v.name,'client',p.name,
          'side',c.kind,'start',c.start_date,'date',o.due_date,'amount',c.amount,
          'balance',o.amount-media_private.net_paid(o.id),'reference',o.reference,'boardId',b.id)
    FROM media_private.obligations o JOIN media_private.contracts c ON c.id=o.contract_id
    JOIN media_private.boards b ON b.id=c.board_id
    JOIN media_private.parties cp ON cp.id=c.party_id
    LEFT JOIN media_private.parties v ON v.id=b.vendor_id
    LEFT JOIN media_private.parties p ON p.id=b.client_id
    CROSS JOIN (VALUES(7),(0)) s(offset_days)
    WHERE o.archived_at IS NULL AND c.confirmed AND c.archived_at IS NULL AND b.archived_at IS NULL
      AND cp.archived_at IS NULL AND o.due_date-s.offset_days=day AND media_private.net_paid(o.id)<o.amount
$$;

CREATE FUNCTION media_private.prepare_reminders(day date) RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE added integer;
BEGIN
    INSERT INTO media_private.reminder_attempts(reminder_id,attempt,status)
    SELECT id,attempts,'UNKNOWN' FROM media_private.reminders
    WHERE status='SENDING' AND updated_at < now()-interval '10 minutes'
    FOR UPDATE SKIP LOCKED ON CONFLICT DO NOTHING;
    UPDATE media_private.reminders r SET status='UNKNOWN',claim_token=NULL,updated_at=now()
    WHERE r.status='SENDING' AND EXISTS (SELECT 1 FROM media_private.reminder_attempts a
       WHERE a.reminder_id=r.id AND a.attempt=r.attempts AND a.status='UNKNOWN');
    UPDATE media_private.reminders r SET status='CANCELLED',updated_at=now()
    WHERE r.status IN ('PENDING','RETRY') AND (r.reminder_day<>day OR
       NOT EXISTS (SELECT 1 FROM media_private.reminder_candidates(day) x
          WHERE (x.entity,x.entity_id,x.revision,x.stage)=(r.entity,r.entity_id,r.revision,r.stage)) OR
       NOT EXISTS (SELECT 1 FROM media_private.verified_recipients() v
          WHERE v.recipient=r.recipient_id AND v.email=r.verified_email));
    IF (SELECT count(*) FROM media_private.verified_recipients()) <> 3 THEN RETURN 0; END IF;
    INSERT INTO media_private.reminders(entity,entity_id,revision,stage,reminder_day,recipient_id,verified_email,payload)
    SELECT x.entity,x.entity_id,x.revision,x.stage,day,v.recipient,v.email,x.payload
    FROM media_private.reminder_candidates(day) x CROSS JOIN media_private.verified_recipients() v
    ON CONFLICT DO NOTHING;
    GET DIAGNOSTICS added = ROW_COUNT;
    RETURN added;
END;
$$;

CREATE FUNCTION media_private.claim_reminder(day date) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE claimed media_private.reminders%ROWTYPE;
    live_payload jsonb;
BEGIN
    IF (SELECT count(*) FROM media_private.verified_recipients()) <> 3 THEN RETURN NULL; END IF;
    WITH next_item AS (
        SELECT r.id FROM media_private.reminders r
        JOIN media_private.verified_recipients() v ON v.recipient=r.recipient_id AND v.email=r.verified_email
        JOIN media_private.reminder_candidates(day) x
            ON (x.entity,x.entity_id,x.revision,x.stage)=(r.entity,r.entity_id,r.revision,r.stage)
        WHERE r.reminder_day=day AND r.status IN ('PENDING','RETRY') AND r.next_attempt_at<=now()
        ORDER BY r.next_attempt_at,r.id LIMIT 1 FOR UPDATE OF r SKIP LOCKED
    )
    UPDATE media_private.reminders r SET status='SENDING',attempts=attempts+1,claim_token=gen_random_uuid(),updated_at=now()
    FROM next_item WHERE r.id=next_item.id RETURNING r.* INTO claimed;
    IF claimed.id IS NULL THEN RETURN NULL; END IF;
    SELECT x.payload INTO live_payload FROM media_private.reminder_candidates(day) x
    WHERE (x.entity,x.entity_id,x.revision,x.stage)=(claimed.entity,claimed.entity_id,claimed.revision,claimed.stage);
    RETURN to_jsonb(claimed) || jsonb_build_object('payload',live_payload);
END;
$$;

CREATE FUNCTION media_private.finish_reminder(item uuid, claim uuid, outcome text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE finished media_private.reminders%ROWTYPE;
BEGIN
    IF outcome NOT IN ('SENT','LOCAL_CAPTURED','RETRY','UNKNOWN','CANCELLED') THEN
        RAISE EXCEPTION 'Invalid reminder outcome' USING ERRCODE='22023';
    END IF;
    UPDATE media_private.reminders r SET status=CASE
        WHEN outcome='RETRY' AND attempts>=4 THEN 'FAILED' ELSE outcome END,
        next_attempt_at=now()+make_interval(mins=>5*attempts),
        claim_token=NULL,updated_at=now()
    WHERE r.id=item AND r.status='SENDING' AND r.claim_token=claim RETURNING * INTO finished;
    IF finished.id IS NOT NULL THEN
        INSERT INTO media_private.reminder_attempts(reminder_id,attempt,status) VALUES (item,finished.attempts,finished.status);
    END IF;
END;
$$;

CREATE FUNCTION media_private.reminder_history(actor uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
    PERFORM media_private.require_membership(actor);
    RETURN (SELECT coalesce(jsonb_agg(to_jsonb(r)-'verified_email'-'claim_token' ORDER BY r.created_at DESC),'[]')
        FROM (SELECT * FROM media_private.reminders ORDER BY created_at DESC LIMIT 500) r);
END;
$$;

CREATE FUNCTION media_private.reminder_plan(actor uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
    PERFORM media_private.require_membership(actor);
    RETURN jsonb_build_object(
        'verifiedRecipients',(SELECT count(*) FROM media_private.verified_recipients()),
        'upcoming',(SELECT coalesce(jsonb_agg(to_jsonb(p) ORDER BY p.reminder_day,p.entity_id,p.stage),'[]') FROM (
            SELECT x.*, d.reminder_day FROM (
                SELECT (now() AT TIME ZONE 'Africa/Lagos')::date + offset_days AS reminder_day
                FROM generate_series(0,30) s(offset_days)
            ) d CROSS JOIN LATERAL media_private.reminder_candidates(d.reminder_day) x
            ORDER BY d.reminder_day,x.entity_id,x.stage LIMIT 500
        ) p));
END;
$$;

REVOKE ALL ON ALL TABLES IN SCHEMA media_private FROM PUBLIC;
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA media_private FROM PUBLIC;
