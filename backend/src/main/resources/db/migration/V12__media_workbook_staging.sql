CREATE SCHEMA media_private;
REVOKE ALL ON SCHEMA media_private FROM PUBLIC;

CREATE TABLE media_private.memberships (
    employee_id uuid PRIMARY KEY REFERENCES public.employees(id),
    category text NOT NULL UNIQUE CHECK (category IN ('BUSINESS_MANAGER', 'COO', 'OPERATOR'))
);

CREATE TABLE media_private.import_batches (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    sha256 text NOT NULL UNIQUE CHECK (sha256 ~ '^[a-f0-9]{64}$'),
    filename text NOT NULL CHECK (length(filename) BETWEEN 1 AND 255),
    original bytea NOT NULL CHECK (octet_length(original) BETWEEN 1 AND 524288),
    snapshot jsonb NOT NULL,
    created_by uuid NOT NULL REFERENCES public.employees(id),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE media_private.import_audit (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id uuid NOT NULL REFERENCES public.employees(id),
    batch_id uuid NOT NULL REFERENCES media_private.import_batches(id),
    action text NOT NULL CHECK (action = 'WORKBOOK_STAGED'),
    before_values jsonb,
    after_values jsonb NOT NULL,
    correlation_id uuid NOT NULL DEFAULT gen_random_uuid(),
    recorded_at timestamptz NOT NULL DEFAULT now()
);

CREATE FUNCTION media_private.reject_audit_mutation() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    RAISE EXCEPTION 'Media import audit records are append-only' USING ERRCODE = '42501';
END;
$$;

CREATE TRIGGER media_import_audit_immutable
BEFORE UPDATE OR DELETE OR TRUNCATE ON media_private.import_audit
FOR EACH STATEMENT EXECUTE FUNCTION media_private.reject_audit_mutation();

CREATE FUNCTION media_private.category(actor uuid) RETURNS text
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog AS $$
    SELECT CASE WHEN e.role = 'ADMIN' THEN 'ADMIN' ELSE m.category END
    FROM public.employees e LEFT JOIN media_private.memberships m ON m.employee_id = e.id
    WHERE e.id = actor AND e.is_active
$$;

CREATE FUNCTION media_private.require_membership(actor uuid) RETURNS void
LANGUAGE plpgsql STABLE SET search_path = pg_catalog AS $$
BEGIN
    IF media_private.category(actor) IS NULL THEN
        RAISE EXCEPTION 'Media module access denied' USING ERRCODE = '42501';
    END IF;
END;
$$;

CREATE FUNCTION media_private.stage_workbook(actor uuid, digest text, name text, contents bytea, preview jsonb)
RETURNS TABLE (batch_id uuid, inserted boolean)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
DECLARE created uuid;
BEGIN
    PERFORM media_private.require_membership(actor);
    IF digest IS DISTINCT FROM encode(sha256(contents), 'hex') THEN
        RAISE EXCEPTION 'Workbook digest does not match contents';
    END IF;
    INSERT INTO media_private.import_batches (sha256, filename, original, snapshot, created_by)
    VALUES (digest, name, contents, preview, actor) ON CONFLICT (sha256) DO NOTHING RETURNING id INTO created;
    IF created IS NULL THEN
        RETURN QUERY SELECT id, false FROM media_private.import_batches WHERE sha256 = digest;
    ELSE
        INSERT INTO media_private.import_audit (actor_id, batch_id, action, after_values)
        VALUES (actor, created, 'WORKBOOK_STAGED', jsonb_build_object('sha256', digest, 'filename', name));
        RETURN QUERY SELECT created, true;
    END IF;
END;
$$;

CREATE FUNCTION media_private.workbook_history(actor uuid)
RETURNS TABLE (id uuid, sha256 text, filename text, created_at timestamptz, created_by uuid)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog AS $$
BEGIN
    PERFORM media_private.require_membership(actor);
    RETURN QUERY SELECT b.id, b.sha256, b.filename, b.created_at, b.created_by
        FROM media_private.import_batches b ORDER BY b.created_at DESC, b.id DESC LIMIT 100;
END;
$$;

CREATE FUNCTION media_private.workbook_preview(actor uuid, batch uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog AS $$
BEGIN
    PERFORM media_private.require_membership(actor);
    RETURN (SELECT b.snapshot FROM media_private.import_batches b WHERE b.id = batch);
END;
$$;

REVOKE ALL ON ALL TABLES IN SCHEMA media_private FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA media_private FROM PUBLIC;
