-- These modules use the authenticated Spring API, never the Supabase public Data API.
DO $$
DECLARE table_name text; role_name text;
BEGIN
 FOREACH table_name IN ARRAY ARRAY['subsidiaries','it_assets','asset_assignments','asset_issues','asset_repairs',
 'asset_replacement_requests','asset_returns','employee_asset_clearances','employee_clearance_items','asset_audit_logs',
 'asset_requests','asset_mail_outbox','payment_configs','payment_automation','payment_requests','payment_delivery_attempts','payment_audit']
 LOOP
  IF to_regclass('public.' || table_name) IS NOT NULL THEN
   EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY',table_name);
   FOREACH role_name IN ARRAY ARRAY['anon','authenticated'] LOOP
    IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname=role_name) THEN
     EXECUTE format('REVOKE ALL ON TABLE public.%I FROM %I',table_name,role_name);
    END IF;
   END LOOP;
  END IF;
 END LOOP;
END $$;
ALTER FUNCTION check_asset_assignment_integrity() SET search_path=public,pg_temp;
ALTER FUNCTION protect_asset_audit() SET search_path=public,pg_temp;
REVOKE EXECUTE ON FUNCTION check_asset_assignment_integrity(),protect_asset_audit() FROM PUBLIC;
