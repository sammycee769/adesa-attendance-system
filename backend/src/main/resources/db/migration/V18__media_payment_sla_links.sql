ALTER TABLE media_private.transactions ADD COLUMN sla_link text;

CREATE OR REPLACE FUNCTION media_private.record_payment(actor uuid, command text, data jsonb) RETURNS uuid
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
        IF sla_link IS NULL OR length(sla_link)>2048 OR sla_link !~* '^https?://[a-z0-9][a-z0-9.-]*(:[0-9]{1,5})?([/?#][^[:space:][:cntrl:]]*)?$' OR strpos(sla_link, chr(92)) > 0 THEN
            RAISE EXCEPTION 'Enter a valid HTTP or HTTPS SLA link' USING ERRCODE='22023';
        END IF;
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
        INSERT INTO media_private.transactions(obligation_id,amount,paid_on,reference,evidence,sla_link,actor_id,replacement_for,reason)
        VALUES (parent.id,value,paid,ref,evidence,sla_link,actor,original.id,reason) RETURNING id INTO result;
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
