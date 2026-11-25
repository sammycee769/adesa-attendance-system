ALTER TABLE media_private.contracts ADD COLUMN invoice_sent boolean NOT NULL DEFAULT false,
    ADD COLUMN invoice_revision integer NOT NULL DEFAULT 1;
UPDATE media_private.contracts c SET invoice_sent=true
WHERE c.kind='CLIENT' AND EXISTS (
    SELECT 1 FROM media_private.obligations o WHERE o.contract_id=c.id AND o.invoice_sent AND o.archived_at IS NULL
);

ALTER TABLE media_private.transactions ALTER COLUMN obligation_id DROP NOT NULL;
ALTER TABLE media_private.transactions ADD COLUMN contract_id uuid REFERENCES media_private.contracts(id);
ALTER TABLE media_private.transactions ADD CONSTRAINT media_transaction_payment_parent
    CHECK ((obligation_id IS NULL) <> (contract_id IS NULL));
CREATE INDEX media_transaction_contract ON media_private.transactions(contract_id);
CREATE INDEX media_obligation_contract ON media_private.obligations(contract_id);

CREATE FUNCTION media_private.contract_net_paid(agreement uuid) RETURNS numeric
LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
    SELECT coalesce(sum(amount),0) FROM (
        SELECT CASE WHEN reversal_of IS NULL THEN amount ELSE -amount END AS amount
        FROM media_private.transactions WHERE contract_id=agreement
        UNION ALL
        SELECT media_private.net_paid(id) FROM media_private.obligations WHERE contract_id=agreement
    ) entries
$$;
REVOKE EXECUTE ON FUNCTION media_private.contract_net_paid(uuid) FROM PUBLIC;


CREATE FUNCTION media_private.set_client_invoice(actor uuid, data jsonb) RETURNS uuid
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE
    agreement media_private.contracts%ROWTYPE;
    sent boolean := (data->>'invoiceSent')::boolean;
BEGIN
    PERFORM 1 FROM media_private.boards b
    JOIN media_private.contracts c ON c.board_id=b.id
    WHERE c.id=(data->>'id')::uuid AND b.archived_at IS NULL FOR SHARE OF b;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Select a confirmed client agreement on an active board' USING ERRCODE='22023';
    END IF;
    SELECT * INTO agreement FROM media_private.contracts c
    WHERE c.id=(data->>'id')::uuid AND c.kind='CLIENT' AND c.confirmed AND c.archived_at IS NULL FOR UPDATE;
    IF NOT FOUND OR sent IS NULL THEN
        RAISE EXCEPTION 'Select a confirmed client agreement and enter its invoice status' USING ERRCODE='22023';
    END IF;
    IF agreement.invoice_revision IS DISTINCT FROM (data->>'revision')::integer THEN
        RAISE EXCEPTION 'Invoice status changed. Reload before editing.' USING ERRCODE='40001';
    END IF;
    IF agreement.invoice_sent IS DISTINCT FROM sent THEN
        UPDATE media_private.contracts SET invoice_sent=sent, invoice_revision=invoice_revision+1 WHERE id=agreement.id;
        INSERT INTO media_private.audit(actor_id,entity,entity_id,action,before_values,after_values)
        VALUES (actor,'CONTRACT',agreement.id,'INVOICE',
            jsonb_build_object('invoice_sent',agreement.invoice_sent),jsonb_build_object('invoice_sent',sent));
    END IF;
    RETURN agreement.id;
END;
$$;
REVOKE EXECUTE ON FUNCTION media_private.set_client_invoice(uuid,jsonb) FROM PUBLIC;

CREATE OR REPLACE FUNCTION media_private.record_payment(actor uuid, command text, data jsonb) RETURNS uuid
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE
    parent media_private.obligations%ROWTYPE;
    agreement media_private.contracts%ROWTYPE;
    agreement_id uuid := (data->>'contractId')::uuid;
    original media_private.transactions%ROWTYPE;
    result uuid;
    previous jsonb;
    balance numeric;
    value numeric := (data->>'amount')::media_private.naira;
    paid date := (data->>'paidOn')::date;
    ref text := btrim(data->>'reference');
    sla_link text := nullif(btrim(data->>'slaLink'),'');
    evidence text := coalesce(data->>'evidence','');
    reason text := nullif(btrim(data->>'reason'),'');
    correction boolean := command IN ('REVERSE','CORRECT');
    reversal_id uuid;
    lagos_day date := (now() AT TIME ZONE 'Africa/Lagos')::date;
BEGIN
    IF correction THEN
        SELECT * INTO original FROM media_private.transactions t WHERE t.id=(data->>'id')::uuid;
        IF NOT FOUND THEN RAISE EXCEPTION 'Payment not found' USING ERRCODE='P0002'; END IF;
        IF original.reversal_of IS NOT NULL OR reason IS NULL OR length(reason)>1000 THEN
            RAISE EXCEPTION 'Select a payment and provide a correction reason' USING ERRCODE='22023';
        END IF;
        SELECT * INTO parent FROM media_private.obligations o WHERE o.id=original.obligation_id;
        agreement_id := coalesce(original.contract_id,parent.contract_id);
        previous := to_jsonb(original);
    ELSIF agreement_id IS NULL AND data->>'obligationId' IS NOT NULL THEN
        -- Accept old clients and retain their ledger association.
        SELECT * INTO parent FROM media_private.obligations o WHERE o.id=(data->>'obligationId')::uuid;
        agreement_id := parent.contract_id;
    END IF;
    PERFORM 1 FROM media_private.boards b
    JOIN media_private.contracts c ON c.board_id=b.id
    WHERE c.id=agreement_id AND b.archived_at IS NULL FOR SHARE OF b;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Select a confirmed agreement on an active board' USING ERRCODE='22023';
    END IF;
    SELECT * INTO agreement FROM media_private.contracts c
    WHERE c.id=agreement_id AND c.confirmed AND c.archived_at IS NULL FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Select a confirmed agreement on an active board' USING ERRCODE='22023';
    END IF;
    IF parent.id IS NOT NULL THEN
        SELECT * INTO parent FROM media_private.obligations o WHERE o.id=parent.id FOR UPDATE;
    END IF;
    IF parent.archived_at IS NOT NULL THEN
        RAISE EXCEPTION 'Select a confirmed agreement on an active board' USING ERRCODE='22023';
    END IF;
    balance := agreement.amount-media_private.contract_net_paid(agreement.id);
    IF correction THEN
        IF EXISTS (SELECT 1 FROM media_private.transactions t WHERE t.reversal_of=original.id) THEN
            RAISE EXCEPTION 'Payment already reversed' USING ERRCODE='23505';
        END IF;
        balance := balance+original.amount;
    END IF;
    IF command <> 'REVERSE' THEN
        IF sla_link IS NULL OR length(sla_link)>2048 OR sla_link !~* '^https?://[a-z0-9][a-z0-9.-]*(:[0-9]{1,5})?([/?#][^[:space:][:cntrl:]]*)?$' OR strpos(sla_link, chr(92)) > 0 THEN
            RAISE EXCEPTION 'Enter a valid HTTP or HTTPS SLA link' USING ERRCODE='22023';
        END IF;
        IF value IS NULL OR value <= 0 OR value > balance OR paid IS NULL OR paid > lagos_day OR ref IS NULL OR ref='' THEN
            RAISE EXCEPTION 'Payment needs a positive amount within the balance, a past or current date and a reference' USING ERRCODE='22023';
        END IF;
        IF EXISTS (SELECT 1 FROM media_private.transactions t
                   WHERE (t.contract_id=agreement.id OR t.obligation_id IN (
                       SELECT o.id FROM media_private.obligations o WHERE o.contract_id=agreement.id))
                   AND t.reversal_of IS NULL AND lower(btrim(t.reference))=lower(ref)
                   AND t.id IS DISTINCT FROM original.id
                   AND NOT EXISTS (SELECT 1 FROM media_private.transactions r WHERE r.reversal_of=t.id)) THEN
            RAISE EXCEPTION 'Payment reference already recorded for this agreement' USING ERRCODE='23505';
        END IF;
    END IF;
    IF correction THEN
        INSERT INTO media_private.transactions(obligation_id,contract_id,amount,paid_on,reference,actor_id,reversal_of,reason)
        VALUES (parent.id,original.contract_id,original.amount,original.paid_on,original.reference,actor,original.id,reason) RETURNING id INTO reversal_id;
    END IF;
    IF command <> 'REVERSE' THEN
        INSERT INTO media_private.transactions(obligation_id,contract_id,amount,paid_on,reference,evidence,sla_link,actor_id,replacement_for,reason)
        VALUES (parent.id,CASE WHEN parent.id IS NULL THEN agreement.id END,value,paid,ref,evidence,sla_link,actor,original.id,reason) RETURNING id INTO result;
    ELSE
        result := reversal_id;
    END IF;
    INSERT INTO media_private.audit(actor_id,entity,entity_id,action,reason,before_values,after_values)
    VALUES (actor,'PAYMENT',result,command,reason,previous,
        (SELECT to_jsonb(t) || jsonb_build_object('balance',agreement.amount-media_private.contract_net_paid(agreement.id),'reversalId',reversal_id)
         FROM media_private.transactions t WHERE t.id=result));
    RETURN result;
END;
$$;

CREATE OR REPLACE FUNCTION media_private.lifecycle(actor uuid, command text, entity text, data jsonb) RETURNS uuid
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE
    target uuid := (data->>'id')::uuid;
    category text := media_private.category(actor);
    target_table text;
    prior jsonb;
    after_value jsonb;
    has_dependencies boolean;
    reason text := nullif(btrim(data->>'reason'),'');
BEGIN
    IF (command='ARCHIVE' AND category NOT IN ('ADMIN','BUSINESS_MANAGER','COO'))
        OR (command<>'ARCHIVE' AND category<>'ADMIN') THEN
        RAISE EXCEPTION 'Media lifecycle permission denied' USING ERRCODE='42501';
    END IF;
    target_table := CASE entity WHEN 'PARTY' THEN 'parties' WHEN 'BOARD' THEN 'boards'
        WHEN 'CONTRACT' THEN 'contracts' WHEN 'OBLIGATION' THEN 'obligations' ELSE NULL END;
    IF target_table IS NULL OR reason IS NULL OR length(reason)>1000 THEN
        RAISE EXCEPTION 'Select a record and enter a reason' USING ERRCODE='22023';
    END IF;
    EXECUTE format('select to_jsonb(r) from media_private.%I r where id=$1 for update',target_table) INTO prior USING target;
    PERFORM media_private.assert_revision(prior,data);
    has_dependencies := CASE entity
        WHEN 'PARTY' THEN EXISTS (SELECT 1 FROM media_private.boards b WHERE b.vendor_id=target OR b.client_id=target)
                       OR EXISTS (SELECT 1 FROM media_private.contracts c WHERE c.party_id=target)
        WHEN 'BOARD' THEN EXISTS (SELECT 1 FROM media_private.contracts c WHERE c.board_id=target)
        WHEN 'CONTRACT' THEN EXISTS (SELECT 1 FROM media_private.obligations o WHERE o.contract_id=target)
                         OR EXISTS (SELECT 1 FROM media_private.transactions t WHERE t.contract_id=target)
                         OR EXISTS (SELECT 1 FROM media_private.contracts c WHERE c.renews_id=target OR c.id=target AND c.renews_id IS NOT NULL)
        WHEN 'OBLIGATION' THEN EXISTS (SELECT 1 FROM media_private.transactions t WHERE t.obligation_id=target)
    END;
    IF has_dependencies AND command='ARCHIVE' AND NOT coalesce((data->>'acknowledgeHistory')::boolean,false) THEN
        RAISE EXCEPTION 'Confirm that dependent records and their history will be retained' USING ERRCODE='22023';
    END IF;
    IF command='PURGE' THEN
        IF prior->>'archived_at' IS NULL OR has_dependencies OR entity='OBLIGATION'
                OR (entity='CONTRACT' AND (prior->>'confirmed')::boolean) OR
                   (entity='BOARD' AND prior->>'source_batch' IS NOT NULL) THEN
            RAISE EXCEPTION 'Permanent deletion requires an archived record with no verified financial, import or renewal history' USING ERRCODE='22023';
        END IF;
        IF NOT coalesce((data->>'acknowledgeHistory')::boolean,false) THEN
            RAISE EXCEPTION 'Confirm permanent deletion' USING ERRCODE='22023';
        END IF;
        EXECUTE format('delete from media_private.%I where id=$1',target_table) USING target;
    ELSE
        IF command='ARCHIVE' AND prior->>'archived_at' IS NOT NULL
            OR command='RESTORE' AND prior->>'archived_at' IS NULL THEN
            RAISE EXCEPTION 'Record is already in that state' USING ERRCODE='22023';
        END IF;
        IF command='RESTORE' AND entity='CONTRACT' AND (prior->>'confirmed')::boolean THEN
            PERFORM 1 FROM media_private.boards b WHERE b.id=(prior->>'board_id')::uuid FOR UPDATE;
            IF EXISTS (SELECT 1 FROM media_private.contracts c WHERE c.board_id=(prior->>'board_id')::uuid
                AND c.kind=prior->>'kind' AND c.id<>target AND c.confirmed AND c.archived_at IS NULL
                AND c.start_date<=(prior->>'end_date')::date AND c.end_date>=(prior->>'start_date')::date) THEN
                RAISE EXCEPTION 'A confirmed contract overlaps this period' USING ERRCODE='23505';
            END IF;
        END IF;
        EXECUTE format('update media_private.%I r set archived_at=$2, revision=revision+1 where id=$1 returning to_jsonb(r)',target_table)
            INTO after_value USING target, CASE WHEN command='ARCHIVE' THEN now() ELSE NULL END;
    END IF;
    INSERT INTO media_private.audit(actor_id,entity,entity_id,action,reason,before_values,after_values)
        VALUES (actor,entity,target,command,reason,prior,after_value);
    RETURN target;
END;
$$;

CREATE OR REPLACE FUNCTION media_private.mutate(actor uuid, action text, entity text, data jsonb) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
    PERFORM media_private.require_membership(actor);
    IF action='INVOICE' AND entity='CONTRACT' THEN RETURN media_private.set_client_invoice(actor,data);
    ELSIF action='SAVE' AND entity<>'OBLIGATION' THEN RETURN media_private.edit_record(actor,entity,data-'sourceBatch'-'sourceRow');
    ELSIF action IN ('ARCHIVE','RESTORE','PURGE') THEN RETURN media_private.lifecycle(actor,action,entity,data);
    ELSIF action IN ('RECORD','REVERSE','CORRECT') AND entity='PAYMENT' THEN
        RETURN media_private.record_payment(actor,action,data);
    END IF;
    RAISE EXCEPTION 'Unsupported Media action' USING ERRCODE='22023';
END;
$$;

CREATE OR REPLACE FUNCTION media_private.register(actor uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
    PERFORM media_private.require_membership(actor);
    RETURN jsonb_build_object(
      'category',media_private.category(actor),
      'parties',(SELECT coalesce(jsonb_agg(to_jsonb(p) ORDER BY p.name),'[]') FROM media_private.parties p),
      'boards',(SELECT coalesce(jsonb_agg(to_jsonb(b) ORDER BY b.code),'[]') FROM media_private.boards b),
      'managers',(SELECT coalesce(jsonb_agg(jsonb_build_object('id',e.id,'name',e.full_name) ORDER BY e.full_name),'[]')
         FROM public.employees e WHERE e.is_active AND media_private.category(e.id) IS NOT NULL),
      'contracts',(SELECT coalesce(jsonb_agg(to_jsonb(c) || jsonb_build_object(
        'paid',totals.paid,'balance',greatest(0,c.amount-totals.paid),'superseded',
        exists(SELECT 1 FROM media_private.contracts n WHERE n.renews_id=c.id AND n.archived_at IS NULL))
        ORDER BY c.start_date DESC NULLS LAST),'[]') FROM media_private.contracts c
        CROSS JOIN LATERAL (SELECT media_private.contract_net_paid(c.id) AS paid OFFSET 0) totals),
      'obligations',(SELECT coalesce(jsonb_agg(to_jsonb(o) || jsonb_build_object(
        'paid',media_private.net_paid(o.id),'balance',o.amount-media_private.net_paid(o.id))
         ORDER BY o.due_date NULLS LAST),'[]') FROM media_private.obligations o),
      'transactions',(SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY t.recorded_at DESC),'[]') FROM media_private.transactions t),
      'audit',(SELECT coalesce(jsonb_agg(to_jsonb(a) ORDER BY a.recorded_at DESC),'[]') FROM
        (SELECT * FROM (
            SELECT id, actor_id, entity, entity_id, action, reason, before_values, after_values, correlation_id, recorded_at
                FROM media_private.audit
            UNION ALL
            SELECT id,actor_id,'WORKBOOK',batch_id,action,NULL,before_values,after_values,correlation_id,recorded_at
                FROM media_private.import_audit
            UNION ALL
            SELECT id,actor_id,'BOARD_REVIEW',batch_id,'REVIEW_SAVED',NULL,before_values,
                after_values || jsonb_build_object('sourceRow',source_row,'revision',revision),id,recorded_at
                FROM media_private.board_review_audit
        ) events WHERE media_private.category(actor) IN ('ADMIN','COO') ORDER BY recorded_at DESC LIMIT 100) a));
END;
$$;

CREATE OR REPLACE FUNCTION media_private.complete_import_board(actor uuid, batch uuid, row_number int, input jsonb) RETURNS uuid
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
        IF side='CLIENT' AND coalesce((side_data->>'invoiceSent')::boolean,false) THEN
            SELECT * INTO prior_contract FROM media_private.contracts WHERE id=contract_key;
            PERFORM media_private.set_client_invoice(actor,jsonb_build_object('id',contract_key,
                'revision',prior_contract.invoice_revision,'invoiceSent',true));
        END IF;
    END LOOP;
    RETURN target;
END;
$$;

CREATE OR REPLACE FUNCTION media_private.reminder_candidates(day date)
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
$$;
