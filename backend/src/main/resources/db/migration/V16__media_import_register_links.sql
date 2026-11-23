CREATE TABLE media_private.board_import_links (
    batch_id uuid NOT NULL REFERENCES media_private.import_batches(id),
    source_row int NOT NULL,
    board_id uuid NOT NULL REFERENCES media_private.boards(id),
    linked_by uuid NOT NULL REFERENCES public.employees(id),
    linked_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (batch_id,source_row)
);
CREATE INDEX media_import_link_board ON media_private.board_import_links(board_id);

CREATE FUNCTION media_private.link_review(actor uuid, data jsonb) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE
    batch uuid := (data->>'sourceBatch')::uuid;
    row_number int := (data->>'sourceRow')::int;
    target uuid := (data->>'boardId')::uuid;
    review jsonb;
    board_detail jsonb;
    rates jsonb;
BEGIN
    PERFORM media_private.require_membership(actor);
    PERFORM 1 FROM media_private.import_batches b WHERE b.id=batch FOR UPDATE;
    SELECT r.detail INTO review FROM media_private.board_reviews r
       WHERE r.batch_id=batch AND r.source_row=row_number;
    IF review IS NULL THEN RAISE EXCEPTION 'Save the board review before linking it' USING ERRCODE='22023'; END IF;
    IF EXISTS (SELECT 1 FROM media_private.board_import_links l WHERE l.batch_id=batch AND l.source_row=row_number) THEN
        RAISE EXCEPTION 'This workbook row is already linked' USING ERRCODE='23505';
    END IF;
    IF target IS NULL THEN
        PERFORM pg_advisory_xact_lock(hashtextextended(lower(btrim(review->>'location')),0));
        IF EXISTS (SELECT 1 FROM media_private.boards b WHERE lower(btrim(b.location))=lower(btrim(review->>'location'))) THEN
            RAISE EXCEPTION 'A board at this location already exists. Review it and choose Link existing board.' USING ERRCODE='22023';
        END IF;
        board_detail := jsonb_build_object('code',data->>'code',
            'description',coalesce(nullif(btrim(data->>'description'),''),review->>'location'),
            'location',btrim(review->>'location'),'vendorId',data->>'vendorId','clientId',data->>'clientId',
            'notes',coalesce(review->>'notes',''),'sourceBatch',batch,'sourceRow',row_number);
        target := media_private.edit_record(actor,'BOARD',board_detail);
        SELECT draft->'rates' INTO rates FROM media_private.import_batches b
            CROSS JOIN LATERAL jsonb_array_elements(b.snapshot->'drafts') draft
            WHERE b.id=batch AND (draft->>'row')::int=row_number;
        PERFORM media_private.edit_record(actor,'CONTRACT',jsonb_build_object('boardId',target,
            'kind','VENDOR','partyId',data->>'vendorId','amount',rates->>'Cost with Vendor',
            'endDate',CASE WHEN (review->>'vendorApproved')::boolean THEN review->>'vendorExpiry' END));
        PERFORM media_private.edit_record(actor,'CONTRACT',jsonb_build_object('boardId',target,
            'kind','CLIENT','partyId',data->>'clientId','amount',rates->>'Cost with Client',
            'endDate',CASE WHEN (review->>'clientApproved')::boolean THEN review->>'clientExpiry' END));
    ELSE
        PERFORM 1 FROM media_private.boards b WHERE b.id=target AND b.archived_at IS NULL FOR SHARE;
        IF NOT FOUND THEN RAISE EXCEPTION 'Choose an active board from the register' USING ERRCODE='22023'; END IF;
    END IF;
    INSERT INTO media_private.board_import_links(batch_id,source_row,board_id,linked_by)
    VALUES (batch,row_number,target,actor);
    INSERT INTO media_private.audit(actor_id,entity,entity_id,action,after_values)
        VALUES (actor,'BOARD',target,'LINK_IMPORT',jsonb_build_object('batch',batch,'row',row_number));
    RETURN target;
END;
$$;

CREATE FUNCTION media_private.import_links(actor uuid,batch uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
    PERFORM media_private.require_membership(actor);
    RETURN (SELECT coalesce(jsonb_object_agg(source_row,board_id),'{}'::jsonb)
        FROM media_private.board_import_links WHERE batch_id=batch);
END;
$$;

REVOKE ALL ON media_private.board_import_links FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION media_private.link_review(uuid,jsonb), media_private.import_links(uuid,uuid) FROM PUBLIC;
