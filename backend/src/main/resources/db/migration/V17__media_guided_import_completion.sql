CREATE FUNCTION media_private.complete_import_board(actor uuid, batch uuid, row_number int, input jsonb) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE
    workbook_row jsonb;
    previous_review media_private.board_reviews%ROWTYPE;
    details jsonb;
    side_data jsonb;
    party_name text;
    party_key uuid;
    kinds text[] := ARRAY['VENDOR','CLIENT'];
    side text;
    party_keys uuid[] := ARRAY[NULL::uuid,NULL::uuid];
    contract_key uuid;
    target uuid;
    existing_board uuid := nullif(input->>'boardId','')::uuid;
    prior_contract media_private.contracts%ROWTYPE;
    idx integer;
    confirmed_location text := btrim(input->>'location');
    submitted_expiry date;
BEGIN
    PERFORM media_private.require_membership(actor);
    IF coalesce((input->>'confirmed')::boolean,false) IS NOT TRUE OR confirmed_location IS NULL OR length(confirmed_location) NOT BETWEEN 1 AND 200 THEN
        RAISE EXCEPTION 'Confirm the workbook details and enter a board location' USING ERRCODE='22023';
    END IF;
    PERFORM 1 FROM media_private.import_batches b WHERE b.id=batch FOR UPDATE;
    SELECT d INTO workbook_row FROM media_private.import_batches b
        CROSS JOIN LATERAL jsonb_array_elements(b.snapshot->'drafts') d
        WHERE b.id=batch AND (d->>'row')::int=row_number;
    IF workbook_row IS NULL THEN RAISE EXCEPTION 'Workbook board not found' USING ERRCODE='P0002'; END IF;
    SELECT * INTO previous_review FROM media_private.board_reviews r WHERE r.batch_id=batch AND r.source_row=row_number;
    IF coalesce(previous_review.revision,0) IS DISTINCT FROM (input->>'revision')::int THEN
        RAISE EXCEPTION 'Review was edited. Reload the workbook.' USING ERRCODE='40001';
    END IF;
    IF EXISTS(SELECT 1 FROM media_private.board_import_links l WHERE l.batch_id=batch AND l.source_row=row_number) THEN
        RAISE EXCEPTION 'This row is already linked to a board' USING ERRCODE='23505';
    END IF;

    FOR idx IN 1..2 LOOP
        side := kinds[idx];
        side_data := input->lower(side);
        party_name := btrim(side_data->>'name');
        submitted_expiry := (side_data->>'endDate')::date;
        IF party_name IS NULL OR length(party_name) NOT BETWEEN 1 AND 200 OR (side_data->>'startDate') IS NULL
                OR submitted_expiry IS NULL OR (side_data->>'amount') IS NULL THEN
            RAISE EXCEPTION 'Provide the counterparty, start date, expiry and agreement rate for both sides' USING ERRCODE='22023';
        END IF;
        IF submitted_expiry < (side_data->>'startDate')::date
            OR side='VENDOR' AND submitted_expiry < (workbook_row->'dates'->>'Renewed Date')::date THEN
            RAISE EXCEPTION 'Expiry cannot precede the contract start or applicable vendor renewal' USING ERRCODE='22023';
        END IF;
        IF (side_data->>'createObligation') IS NULL THEN
            RAISE EXCEPTION 'Choose whether there is a payment obligation for both sides' USING ERRCODE='22023';
        END IF;
        IF (side_data->>'createObligation')::boolean
                AND ((side_data->>'dueDate') IS NULL OR (side_data->>'obligationAmount') IS NULL
                    OR nullif(btrim(side_data->>'reference'),'') IS NULL) THEN
            RAISE EXCEPTION 'Provide a due date, distinct reference and verified amount for each payment obligation' USING ERRCODE='22023';
        END IF;
        PERFORM pg_advisory_xact_lock(hashtextextended('media-party:'||side||':'||lower(party_name),0));
        SELECT p.id INTO party_key FROM media_private.parties p
           WHERE p.kind=side AND lower(btrim(p.name))=lower(party_name) AND p.archived_at IS NULL;
        IF party_key IS NULL THEN
            party_key := media_private.edit_record(actor,'PARTY',jsonb_build_object('kind',side,'name',party_name));
        END IF;
        party_keys[idx] := party_key;
    END LOOP;

    details := jsonb_build_object('location',confirmed_location,'vendor',btrim(input->'vendor'->>'name'),
        'client',btrim(input->'client'->>'name'),'vendorExpiry',input->'vendor'->>'endDate',
        'clientExpiry',input->'client'->>'endDate','vendorApproved',true,'clientApproved',true,
        'notes',coalesce(previous_review.detail->>'notes',''));
    PERFORM media_private.save_board_review(actor,batch,row_number,coalesce(previous_review.revision,0),details);

    IF existing_board IS NOT NULL THEN
        PERFORM 1 FROM media_private.boards b WHERE b.id=existing_board AND b.archived_at IS NULL
          AND lower(btrim(b.location))=lower(confirmed_location) AND (b.vendor_id IS NULL OR b.vendor_id=party_keys[1])
          AND (b.client_id IS NULL OR b.client_id=party_keys[2]) FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'The selected board has a different location or counterparty. Review the register first.' USING ERRCODE='22023';
        END IF;
    END IF;
    target := media_private.link_review(actor,jsonb_build_object('sourceBatch',batch,'sourceRow',row_number,'boardId',existing_board,
        'code','WB-'||left(batch::text,8)||'-'||row_number,'vendorId',party_keys[1],'clientId',party_keys[2]));

    FOR idx IN 1..2 LOOP
        side := kinds[idx];
        side_data := input->lower(side);
        IF existing_board IS NULL THEN
            SELECT * INTO prior_contract FROM media_private.contracts c WHERE c.board_id=target AND c.kind=side;
        ELSE
            SELECT * INTO prior_contract FROM media_private.contracts c WHERE c.board_id=target AND c.kind=side
              AND c.confirmed AND c.archived_at IS NULL AND c.party_id=party_keys[idx]
              AND c.start_date=(side_data->>'startDate')::date AND c.end_date=(side_data->>'endDate')::date
              AND c.amount=(side_data->>'amount')::numeric;
        END IF;
        IF existing_board IS NOT NULL AND prior_contract.id IS NOT NULL THEN
            contract_key := prior_contract.id;
        ELSE
            contract_key := media_private.edit_record(actor,'CONTRACT',jsonb_build_object('id',prior_contract.id,
                'revision',prior_contract.revision,'boardId',target,'kind',side,'partyId',party_keys[idx],
                'startDate',side_data->>'startDate','endDate',side_data->>'endDate','amount',side_data->>'amount','confirmed',true));
        END IF;
        IF (side_data->>'createObligation')::boolean THEN
            IF NOT EXISTS(SELECT 1 FROM media_private.obligations o WHERE o.contract_id=contract_key
                AND lower(btrim(o.reference))=lower(btrim(side_data->>'reference')) AND o.archived_at IS NULL
                AND o.amount=(side_data->>'obligationAmount')::numeric AND o.due_date=(side_data->>'dueDate')::date) THEN
                PERFORM media_private.edit_record(actor,'OBLIGATION',jsonb_build_object('contractId',contract_key,
                    'reference',side_data->>'reference','dueDate',side_data->>'dueDate','amount',side_data->>'obligationAmount',
                    'invoiceSent',coalesce((side_data->>'invoiceSent')::boolean,false)));
            END IF;
        END IF;
    END LOOP;
    RETURN target;
END;
$$;

REVOKE EXECUTE ON FUNCTION media_private.complete_import_board(uuid,uuid,int,jsonb) FROM PUBLIC;
