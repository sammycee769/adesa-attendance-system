CREATE DOMAIN media_private.naira AS numeric
    CHECK (VALUE >= 0 AND VALUE < 1000000000000000 AND VALUE = round(VALUE, 2));

CREATE TABLE media_private.parties (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    kind text NOT NULL CHECK (kind IN ('VENDOR','CLIENT')),
    name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 200),
    notes text NOT NULL DEFAULT '' CHECK (length(notes) <= 2000),
    archived_at timestamptz,
    revision int NOT NULL DEFAULT 1,
    UNIQUE (id, kind)
);
CREATE UNIQUE INDEX media_party_name ON media_private.parties (kind, lower(btrim(name)));

CREATE TABLE media_private.boards (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code text NOT NULL CHECK (length(btrim(code)) BETWEEN 1 AND 50),
    description text NOT NULL CHECK (length(btrim(description)) BETWEEN 1 AND 250),
    location text NOT NULL CHECK (length(btrim(location)) BETWEEN 1 AND 200),
    vendor_id uuid REFERENCES media_private.parties(id),
    client_id uuid REFERENCES media_private.parties(id),
    manager_id uuid REFERENCES public.employees(id),
    status text CHECK (status IN ('RUNNING','WORK_IN_PROGRESS','COMPLETED')),
    notes text NOT NULL DEFAULT '' CHECK (length(notes) <= 2000),
    source_batch uuid REFERENCES media_private.import_batches(id),
    source_row int,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    archived_at timestamptz,
    revision int NOT NULL DEFAULT 1,
    UNIQUE (source_batch,source_row),
    CHECK ((source_batch IS NULL) = (source_row IS NULL))
);
CREATE UNIQUE INDEX media_board_code ON media_private.boards (lower(btrim(code)));
CREATE INDEX media_board_vendor ON media_private.boards(vendor_id);
CREATE INDEX media_board_client ON media_private.boards(client_id);

CREATE TABLE media_private.contracts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    board_id uuid NOT NULL REFERENCES media_private.boards(id),
    kind text NOT NULL CHECK (kind IN ('VENDOR','CLIENT')),
    party_id uuid REFERENCES media_private.parties(id),
    start_date date,
    end_date date,
    amount media_private.naira,
    currency text NOT NULL DEFAULT 'NGN' CHECK (currency = 'NGN'),
    terms text NOT NULL DEFAULT '' CHECK (length(terms) <= 4000),
    confirmed boolean NOT NULL DEFAULT false,
    renews_id uuid UNIQUE REFERENCES media_private.contracts(id),
    archived_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    revision int NOT NULL DEFAULT 1,
    FOREIGN KEY (party_id,kind) REFERENCES media_private.parties(id,kind),
    CHECK (end_date >= start_date),
    CHECK (NOT confirmed OR (party_id IS NOT NULL AND start_date IS NOT NULL AND end_date IS NOT NULL AND amount IS NOT NULL))
);
CREATE INDEX media_contract_board ON media_private.contracts(board_id,kind);
CREATE INDEX media_expiry ON media_private.contracts(end_date) WHERE confirmed AND archived_at IS NULL;

CREATE TABLE media_private.obligations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    contract_id uuid NOT NULL REFERENCES media_private.contracts(id),
    reference text NOT NULL CHECK (length(btrim(reference)) BETWEEN 1 AND 120),
    amount media_private.naira NOT NULL CHECK (amount > 0),
    due_date date,
    invoice_sent boolean NOT NULL DEFAULT false,
    notes text NOT NULL DEFAULT '' CHECK (length(notes) <= 2000),
    archived_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    revision int NOT NULL DEFAULT 1
);
CREATE UNIQUE INDEX media_obligation_reference ON media_private.obligations(contract_id,lower(btrim(reference)));
CREATE INDEX media_payment_due ON media_private.obligations(due_date) WHERE archived_at IS NULL;

CREATE TABLE media_private.transactions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    obligation_id uuid NOT NULL REFERENCES media_private.obligations(id),
    amount media_private.naira NOT NULL CHECK (amount > 0),
    paid_on date NOT NULL,
    reference text NOT NULL CHECK (length(btrim(reference)) BETWEEN 1 AND 120),
    evidence text NOT NULL DEFAULT '' CHECK (length(evidence) <= 1000),
    actor_id uuid NOT NULL REFERENCES public.employees(id),
    reversal_of uuid UNIQUE REFERENCES media_private.transactions(id),
    replacement_for uuid UNIQUE REFERENCES media_private.transactions(id),
    reason text CHECK (length(reason) <= 1000),
    recorded_at timestamptz NOT NULL DEFAULT now(),
    CHECK (reversal_of IS NULL OR replacement_for IS NULL),
    CHECK ((reversal_of IS NULL AND replacement_for IS NULL) OR length(btrim(reason)) > 0)
);
CREATE INDEX media_transaction_obligation ON media_private.transactions(obligation_id);
CREATE UNIQUE INDEX media_transaction_original_reference
    ON media_private.transactions(obligation_id, lower(btrim(reference)))
    WHERE reversal_of IS NULL AND replacement_for IS NULL;

CREATE TABLE media_private.audit (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id uuid REFERENCES public.employees(id),
    entity text NOT NULL,
    entity_id uuid NOT NULL,
    action text NOT NULL,
    reason text,
    before_values jsonb,
    after_values jsonb,
    correlation_id uuid NOT NULL DEFAULT gen_random_uuid(),
    recorded_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX media_audit_recorded ON media_private.audit(recorded_at DESC);
CREATE TRIGGER media_audit_immutable BEFORE UPDATE OR DELETE OR TRUNCATE
    ON media_private.audit FOR EACH STATEMENT EXECUTE FUNCTION media_private.reject_audit_mutation();
CREATE TRIGGER media_transactions_immutable BEFORE UPDATE OR DELETE OR TRUNCATE
    ON media_private.transactions FOR EACH STATEMENT EXECUTE FUNCTION media_private.reject_audit_mutation();

CREATE FUNCTION media_private.net_paid(obligation uuid) RETURNS numeric
LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
    SELECT coalesce(sum(CASE WHEN reversal_of IS NULL THEN amount ELSE -amount END),0)
    FROM media_private.transactions WHERE obligation_id=obligation
$$;

CREATE FUNCTION media_private.assert_revision(prior jsonb, input jsonb) RETURNS void
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF prior IS NULL THEN RAISE EXCEPTION 'Media record not found' USING ERRCODE='P0002'; END IF;
    IF (prior->>'revision')::int IS DISTINCT FROM (input->>'revision')::int THEN
        RAISE EXCEPTION 'Media record changed. Reload before editing.' USING ERRCODE='40001';
    END IF;
END;
$$;

CREATE FUNCTION media_private.record_payment(actor uuid, command text, data jsonb) RETURNS uuid
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE
    parent media_private.obligations%ROWTYPE;
    original media_private.transactions%ROWTYPE;
    result uuid;
    previous jsonb;
    balance numeric;
    value numeric := (data->>'amount')::media_private.naira;
    paid date := (data->>'paidOn')::date;
    ref text := btrim(data->>'reference');
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
        SELECT * INTO parent FROM media_private.obligations o WHERE o.id=original.obligation_id FOR UPDATE;
        IF EXISTS (SELECT 1 FROM media_private.transactions t WHERE t.reversal_of=original.id) THEN
            RAISE EXCEPTION 'Payment already reversed' USING ERRCODE='23505';
        END IF;
        previous := to_jsonb(original);
    ELSE
        SELECT * INTO parent FROM media_private.obligations o WHERE o.id=(data->>'obligationId')::uuid FOR UPDATE;
    END IF;
    IF parent.id IS NULL THEN RAISE EXCEPTION 'Obligation not found' USING ERRCODE='P0002'; END IF;
    IF parent.archived_at IS NOT NULL OR EXISTS (
        SELECT 1 FROM media_private.contracts c JOIN media_private.boards b ON b.id=c.board_id
        WHERE c.id=parent.contract_id AND (c.archived_at IS NOT NULL OR b.archived_at IS NOT NULL)
    ) THEN RAISE EXCEPTION 'Restore the archived obligation or contract before changing payments' USING ERRCODE='22023'; END IF;
    balance := parent.amount-media_private.net_paid(parent.id);
    IF correction THEN balance := balance+original.amount; END IF;
    IF command <> 'REVERSE' THEN
        IF value IS NULL OR value <= 0 OR value > balance OR paid IS NULL OR paid > lagos_day OR ref IS NULL OR ref='' THEN
            RAISE EXCEPTION 'Payment needs a positive amount within the balance, a past or current date and a reference' USING ERRCODE='22023';
        END IF;
        IF EXISTS (SELECT 1 FROM media_private.transactions t
                   WHERE t.obligation_id=parent.id AND t.reversal_of IS NULL AND lower(t.reference)=lower(ref)
                   AND t.id IS DISTINCT FROM original.id
                   AND NOT EXISTS (SELECT 1 FROM media_private.transactions r WHERE r.reversal_of=t.id)) THEN
            RAISE EXCEPTION 'Payment reference already recorded for this obligation' USING ERRCODE='23505';
        END IF;
    END IF;
    IF correction THEN
        INSERT INTO media_private.transactions(obligation_id,amount,paid_on,reference,actor_id,reversal_of,reason)
        VALUES (parent.id,original.amount,original.paid_on,original.reference,actor,original.id,reason) RETURNING id INTO reversal_id;
    END IF;
    IF command <> 'REVERSE' THEN
        INSERT INTO media_private.transactions(obligation_id,amount,paid_on,reference,evidence,actor_id,replacement_for,reason)
        VALUES (parent.id,value,paid,ref,evidence,actor,original.id,reason) RETURNING id INTO result;
    ELSE
        result := reversal_id;
    END IF;
    INSERT INTO media_private.audit(actor_id,entity,entity_id,action,reason,before_values,after_values)
    VALUES (actor,'PAYMENT',result,command,reason,previous,
        (SELECT to_jsonb(t) || jsonb_build_object('balance',parent.amount-media_private.net_paid(parent.id),'reversalId',reversal_id)
         FROM media_private.transactions t WHERE t.id=result));
    RETURN result;
END;
$$;

CREATE FUNCTION media_private.edit_record(actor uuid, entity text, data jsonb) RETURNS uuid
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE
    id_to_save uuid := (data->>'id')::uuid;
    old_row jsonb;
    new_row jsonb;
    target_table text;
    board uuid := (data->>'boardId')::uuid;
    party uuid := (data->>'partyId')::uuid;
    contract uuid := (data->>'contractId')::uuid;
    vendor uuid := (data->>'vendorId')::uuid;
    client uuid := (data->>'clientId')::uuid;
    manager uuid := (data->>'managerId')::uuid;
    renewal uuid := (data->>'renewsId')::uuid;
    source_batch uuid := (data->>'sourceBatch')::uuid;
    source_line int := (data->>'sourceRow')::int;
    record_kind text := data->>'kind';
    begin_on date := (data->>'startDate')::date;
    end_on date := (data->>'endDate')::date;
    value numeric := (data->>'amount')::media_private.naira;
    verified boolean := coalesce((data->>'confirmed')::boolean,false);
    detail jsonb;
BEGIN
    target_table := CASE entity WHEN 'PARTY' THEN 'parties' WHEN 'BOARD' THEN 'boards'
                  WHEN 'CONTRACT' THEN 'contracts' WHEN 'OBLIGATION' THEN 'obligations' ELSE NULL END;
    IF target_table IS NULL THEN RAISE EXCEPTION 'Unknown Media record type' USING ERRCODE='22023'; END IF;
    IF id_to_save IS NOT NULL THEN
        EXECUTE format('select to_jsonb(r) from media_private.%I r where r.id=$1 for update',target_table)
            INTO old_row USING id_to_save;
        PERFORM media_private.assert_revision(old_row,data);
        IF old_row->>'archived_at' IS NOT NULL THEN
            RAISE EXCEPTION 'Restore the archived record before editing it' USING ERRCODE='22023';
        END IF;
    END IF;
    IF entity='PARTY' THEN
        IF old_row IS NULL THEN
            INSERT INTO media_private.parties(kind,name,notes) VALUES (record_kind,btrim(data->>'name'),coalesce(data->>'notes','')) RETURNING id INTO id_to_save;
        ELSE
            UPDATE media_private.parties SET name=btrim(data->>'name'),notes=coalesce(data->>'notes',''),revision=revision+1
            WHERE id=id_to_save;
        END IF;
    ELSIF entity='BOARD' THEN
        IF source_batch IS NOT NULL AND old_row IS NULL THEN
            SELECT r.detail INTO detail FROM media_private.board_reviews r
            WHERE r.batch_id=source_batch AND r.source_row=source_line FOR SHARE;
            IF detail IS NULL THEN RAISE EXCEPTION 'Review the staged board before creating a register entry' USING ERRCODE='22023'; END IF;
            IF (detail->>'location') IS DISTINCT FROM btrim(data->>'location') THEN
                RAISE EXCEPTION 'Board location differs from the saved review' USING ERRCODE='22023';
            END IF;
        END IF;
        IF vendor IS NOT NULL AND NOT EXISTS (SELECT 1 FROM media_private.parties p WHERE p.id=vendor AND p.kind='VENDOR' AND p.archived_at IS NULL)
            OR client IS NOT NULL AND NOT EXISTS (SELECT 1 FROM media_private.parties p WHERE p.id=client AND p.kind='CLIENT' AND p.archived_at IS NULL)
            OR manager IS NOT NULL AND NOT EXISTS (SELECT 1 FROM public.employees e WHERE e.id=manager AND e.is_active AND media_private.category(e.id) IS NOT NULL) THEN
            RAISE EXCEPTION 'Select active vendor, client and manager records' USING ERRCODE='22023';
        END IF;
        IF old_row IS NULL THEN
            INSERT INTO media_private.boards(code,description,location,vendor_id,client_id,manager_id,status,notes,source_batch,source_row)
            VALUES (btrim(data->>'code'),btrim(data->>'description'),btrim(data->>'location'),vendor,client,manager,
                    data->>'status',coalesce(data->>'notes',''),source_batch,source_line) RETURNING id INTO id_to_save;
        ELSE
            UPDATE media_private.boards SET code=btrim(data->>'code'),description=btrim(data->>'description'),location=btrim(data->>'location'),
                vendor_id=vendor,client_id=client,manager_id=manager,status=data->>'status',notes=coalesce(data->>'notes',''),
                updated_at=now(),revision=revision+1 WHERE id=id_to_save;
        END IF;
    ELSIF entity='CONTRACT' THEN
        IF old_row IS NOT NULL THEN
            board := (old_row->>'board_id')::uuid;
            record_kind := old_row->>'kind';
            IF (old_row->>'confirmed')::boolean THEN
                RAISE EXCEPTION 'Create a renewal period to preserve the confirmed agreement' USING ERRCODE='22023';
            END IF;
        END IF;
        PERFORM 1 FROM media_private.boards b WHERE b.id=board AND b.archived_at IS NULL FOR UPDATE;
        IF NOT FOUND THEN RAISE EXCEPTION 'Select an active board' USING ERRCODE='22023'; END IF;
        IF party IS NOT NULL AND NOT EXISTS (SELECT 1 FROM media_private.parties p WHERE p.id=party AND p.kind=record_kind AND p.archived_at IS NULL) THEN
            RAISE EXCEPTION 'Select the active contract counterparty' USING ERRCODE='22023';
        END IF;
        IF renewal IS NOT NULL AND (old_row IS NOT NULL OR NOT verified OR NOT EXISTS (
            SELECT 1 FROM media_private.contracts c WHERE c.id=renewal AND c.board_id=board AND c.kind=record_kind
                AND c.confirmed AND c.archived_at IS NULL AND begin_on>c.end_date
        )) THEN RAISE EXCEPTION 'Renewal must follow the confirmed period for this board and side' USING ERRCODE='22023'; END IF;
        IF verified AND EXISTS (
            SELECT 1 FROM media_private.contracts c WHERE c.board_id=board AND c.kind=record_kind AND c.confirmed
            AND c.archived_at IS NULL AND c.id IS DISTINCT FROM id_to_save
            AND c.start_date<=end_on AND c.end_date>=begin_on
        ) THEN RAISE EXCEPTION 'A confirmed contract overlaps this period' USING ERRCODE='23505'; END IF;
        IF old_row IS NULL THEN
            INSERT INTO media_private.contracts(board_id,kind,party_id,start_date,end_date,amount,terms,confirmed,renews_id)
            VALUES (board,record_kind,party,begin_on,end_on,value,coalesce(data->>'terms',''),verified,renewal) RETURNING id INTO id_to_save;
        ELSE
            UPDATE media_private.contracts SET party_id=party,start_date=begin_on,end_date=end_on,amount=value,
                terms=coalesce(data->>'terms',''),confirmed=verified,revision=revision+1 WHERE id=id_to_save;
        END IF;
    ELSE
        IF old_row IS NOT NULL THEN contract := (old_row->>'contract_id')::uuid; END IF;
        IF NOT EXISTS (SELECT 1 FROM media_private.contracts c JOIN media_private.boards b ON b.id=c.board_id
                WHERE c.id=contract AND c.confirmed AND c.archived_at IS NULL AND b.archived_at IS NULL) THEN
            RAISE EXCEPTION 'Select a confirmed contract on an active board' USING ERRCODE='22023';
        END IF;
        IF old_row IS NULL THEN
            INSERT INTO media_private.obligations(contract_id,reference,amount,due_date,invoice_sent,notes)
            VALUES (contract,btrim(data->>'reference'),value,(data->>'dueDate')::date,
                    coalesce((data->>'invoiceSent')::boolean,false),coalesce(data->>'notes','')) RETURNING id INTO id_to_save;
        ELSE
            IF EXISTS (SELECT 1 FROM media_private.transactions t WHERE t.obligation_id=id_to_save)
                AND value IS DISTINCT FROM (old_row->>'amount')::numeric THEN
                RAISE EXCEPTION 'Obligation amount is locked once payment history exists' USING ERRCODE='22023';
            END IF;
            UPDATE media_private.obligations SET reference=btrim(data->>'reference'),amount=value,due_date=(data->>'dueDate')::date,
                invoice_sent=coalesce((data->>'invoiceSent')::boolean,false),notes=coalesce(data->>'notes',''),revision=revision+1 WHERE id=id_to_save;
        END IF;
    END IF;
    EXECUTE format('select to_jsonb(r) from media_private.%I r where r.id=$1',target_table) INTO new_row USING id_to_save;
    INSERT INTO media_private.audit(actor_id,entity,entity_id,action,before_values,after_values)
    VALUES (actor,entity,id_to_save,CASE WHEN old_row IS NOT NULL THEN 'EDIT' WHEN renewal IS NOT NULL THEN 'RENEW' ELSE 'CREATE' END,old_row,new_row);
    RETURN id_to_save;
END;
$$;

CREATE FUNCTION media_private.lifecycle(actor uuid, command text, entity text, data jsonb) RETURNS uuid
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

CREATE FUNCTION media_private.mutate(actor uuid, action text, entity text, data jsonb) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
    PERFORM media_private.require_membership(actor);
    IF action='SAVE' THEN RETURN media_private.edit_record(actor,entity,data-'sourceBatch'-'sourceRow');
    ELSIF action IN ('ARCHIVE','RESTORE','PURGE') THEN RETURN media_private.lifecycle(actor,action,entity,data);
    ELSIF action IN ('RECORD','REVERSE','CORRECT') AND entity='PAYMENT' THEN
        RETURN media_private.record_payment(actor,action,data);
    END IF;
    RAISE EXCEPTION 'Unsupported Media action' USING ERRCODE='22023';
END;
$$;

CREATE FUNCTION media_private.register(actor uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
    PERFORM media_private.require_membership(actor);
    RETURN jsonb_build_object(
      'category',media_private.category(actor),
      'parties',(SELECT coalesce(jsonb_agg(to_jsonb(p) ORDER BY p.name),'[]') FROM media_private.parties p),
      'boards',(SELECT coalesce(jsonb_agg(to_jsonb(b) ORDER BY b.code),'[]') FROM media_private.boards b),
      'managers',(SELECT coalesce(jsonb_agg(jsonb_build_object('id',e.id,'name',e.full_name) ORDER BY e.full_name),'[]')
         FROM public.employees e WHERE e.is_active AND media_private.category(e.id) IS NOT NULL),
      'contracts',(SELECT coalesce(jsonb_agg(to_jsonb(c) || jsonb_build_object('superseded',
        exists(SELECT 1 FROM media_private.contracts n WHERE n.renews_id=c.id AND n.archived_at IS NULL))
        ORDER BY c.start_date DESC NULLS LAST),'[]') FROM media_private.contracts c),
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

REVOKE ALL ON ALL TABLES IN SCHEMA media_private FROM PUBLIC;
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA media_private FROM PUBLIC;
