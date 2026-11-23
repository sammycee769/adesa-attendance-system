CREATE TABLE media_private.board_reviews (
    batch_id uuid NOT NULL REFERENCES media_private.import_batches(id),
    source_row integer NOT NULL CHECK (source_row > 1),
    revision integer NOT NULL CHECK (revision > 0),
    detail jsonb NOT NULL,
    updated_by uuid NOT NULL REFERENCES public.employees(id),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (batch_id, source_row),
    CHECK (jsonb_typeof(detail) = 'object'),
    CHECK (NOT coalesce((detail->>'vendorApproved')::boolean, false) OR detail->>'vendorExpiry' IS NOT NULL),
    CHECK (NOT coalesce((detail->>'clientApproved')::boolean, false) OR detail->>'clientExpiry' IS NOT NULL)
);

CREATE TABLE media_private.board_review_audit (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id uuid NOT NULL,
    source_row integer NOT NULL,
    revision integer NOT NULL,
    actor_id uuid NOT NULL REFERENCES public.employees(id),
    before_values jsonb,
    after_values jsonb NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (batch_id, source_row, revision),
    FOREIGN KEY (batch_id, source_row) REFERENCES media_private.board_reviews(batch_id, source_row)
);

CREATE TRIGGER media_board_review_audit_immutable
BEFORE UPDATE OR DELETE OR TRUNCATE ON media_private.board_review_audit
FOR EACH STATEMENT EXECUTE FUNCTION media_private.reject_audit_mutation();

CREATE FUNCTION media_private.board_reviews(actor uuid, batch uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog AS $$
BEGIN
    PERFORM media_private.require_membership(actor);
    RETURN (SELECT coalesce(jsonb_agg(r.detail || jsonb_build_object('row', r.source_row,
                    'revision', r.revision, 'updatedAt', r.updated_at) ORDER BY r.source_row), '[]'::jsonb)
            FROM media_private.board_reviews r WHERE r.batch_id = batch);
END;
$$;

CREATE FUNCTION media_private.save_board_review(actor uuid, batch uuid, row_number int, expected_revision int, review jsonb)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
DECLARE
    prior media_private.board_reviews%ROWTYPE;
    saved media_private.board_reviews%ROWTYPE;
BEGIN
    PERFORM media_private.require_membership(actor);
    -- The immutable batch is also the lock for concurrent first edits.
    PERFORM 1 FROM media_private.import_batches b
    WHERE b.id = batch AND b.snapshot->'drafts' @> jsonb_build_array(jsonb_build_object('row', row_number))
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Staged board does not exist' USING ERRCODE = 'P0002';
    END IF;
    SELECT * INTO prior FROM media_private.board_reviews r WHERE r.batch_id = batch AND r.source_row = row_number;
    IF coalesce(prior.revision, 0) <> expected_revision THEN
        RAISE EXCEPTION 'Board review has changed' USING ERRCODE = '40001';
    END IF;
    INSERT INTO media_private.board_reviews (batch_id, source_row, revision, detail, updated_by)
    VALUES (batch, row_number, expected_revision + 1, review, actor)
    ON CONFLICT (batch_id, source_row) DO UPDATE
    SET revision = excluded.revision, detail = excluded.detail, updated_by = excluded.updated_by, updated_at = now()
    RETURNING * INTO saved;
    INSERT INTO media_private.board_review_audit (batch_id, source_row, revision, actor_id, before_values, after_values)
    VALUES (batch, row_number, saved.revision, actor, prior.detail, saved.detail);
    RETURN saved.detail || jsonb_build_object('row', saved.source_row, 'revision', saved.revision, 'updatedAt', saved.updated_at);
END;
$$;

REVOKE ALL ON media_private.board_reviews, media_private.board_review_audit FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION media_private.board_reviews(uuid, uuid),
    media_private.save_board_review(uuid, uuid, int, int, jsonb) FROM PUBLIC;
