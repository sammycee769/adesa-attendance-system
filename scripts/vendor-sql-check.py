#!/usr/bin/env python3
"""Real PostgreSQL migration, private-row access and immutable-record checks. Disposable cluster only."""
import pathlib,subprocess,tempfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
PG=pathlib.Path('/usr/lib/postgresql/16/bin')
assertions=r"""
CREATE ROLE vendor_check NOSUPERUSER NOLOGIN;
GRANT USAGE ON SCHEMA vendor_private TO vendor_check;
GRANT ALL ON ALL TABLES IN SCHEMA vendor_private TO vendor_check;
GRANT ALL ON ALL SEQUENCES IN SCHEMA vendor_private TO vendor_check;
ALTER TABLE vendor_private.application OWNER TO vendor_check;
ALTER TABLE vendor_private.revision OWNER TO vendor_check;
ALTER TABLE vendor_private.document OWNER TO vendor_check;
SET ROLE vendor_check;
SELECT set_config('vendor.access','ADMIN',false);
INSERT INTO vendor_private.link(id,subsidiary,label,kind,token_hash,token_cipher,created_by) VALUES('00000000-0000-0000-0000-000000000001','ENERGY','Test link','REUSABLE','hash','\x01','admin@example.test');
INSERT INTO vendor_private.application(id,reference,subsidiary,link_id,entity_id,conflict,categories) VALUES('00000000-0000-0000-0000-000000000002','VND-TEST','ENERGY','00000000-0000-0000-0000-000000000001',gen_random_uuid(),false,'{}');
INSERT INTO vendor_private.session(id,link_id,subsidiary,token_hash,expires_at,application_id) VALUES('00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000001','ENERGY','session-hash',now()+interval '1 day','00000000-0000-0000-0000-000000000002');
INSERT INTO vendor_private.document(id,session_id,application_id,category,filename,declared_type,expected_size,body_cipher) VALUES('00000000-0000-0000-0000-000000000004','00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000002','SIGNATURE','signature.png','image/png',1,'\x01');
INSERT INTO vendor_private.revision(id,application_id,session_id,number,payload_cipher,signature_id,content_hash,form_version) VALUES(gen_random_uuid(),'00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000003',1,'\x01','00000000-0000-0000-0000-000000000004','digest','v1');
INSERT INTO vendor_private.note VALUES(gen_random_uuid(),'00000000-0000-0000-0000-000000000002','\x01','admin@example.test',now());
INSERT INTO vendor_private.application(id,reference,subsidiary,link_id,entity_id,conflict,categories) VALUES('00000000-0000-0000-0000-000000000012','VND-OTHER','ENERGY','00000000-0000-0000-0000-000000000001',gen_random_uuid(),false,'{}');
INSERT INTO vendor_private.session(id,link_id,subsidiary,token_hash,expires_at,application_id) VALUES('00000000-0000-0000-0000-000000000013','00000000-0000-0000-0000-000000000001','ENERGY','other-session-hash',now()+interval '1 day','00000000-0000-0000-0000-000000000012');
INSERT INTO vendor_private.document(id,session_id,application_id,category,filename,declared_type,expected_size,body_cipher) VALUES('00000000-0000-0000-0000-000000000014','00000000-0000-0000-0000-000000000013','00000000-0000-0000-0000-000000000012','SIGNATURE','other-signature.png','image/png',1,'\x01');
SELECT set_config('vendor.access','',false);

DO $$ BEGIN
 IF (SELECT count(*) FROM vendor_private.application)<>0 OR (SELECT count(*) FROM vendor_private.revision)<>0 OR (SELECT count(*) FROM vendor_private.document)<>0 OR (SELECT count(*) FROM vendor_private.note)<>0 THEN RAISE EXCEPTION 'Unscoped application owner can read private vendor records'; END IF;
END $$;
SELECT set_config('vendor.session','00000000-0000-0000-0000-000000000003',false);
SELECT set_config('vendor.application','00000000-0000-0000-0000-000000000002',false);
DO $$ BEGIN
 IF (SELECT count(*) FROM vendor_private.document)<>1 OR (SELECT count(*) FROM vendor_private.application)<>1 OR (SELECT count(*) FROM vendor_private.note)<>0 THEN RAISE EXCEPTION 'Vendor scope is incorrect or leaks administrator notes'; END IF;
END $$;
SELECT set_config('vendor.access','ADMIN',false);
DO $$ DECLARE blocked boolean:=false; BEGIN
 BEGIN UPDATE vendor_private.revision SET content_hash='tampered'; EXCEPTION WHEN raise_exception THEN blocked:=true; END;
 IF NOT blocked THEN RAISE EXCEPTION 'Signed revision was editable'; END IF;
 blocked:=false;
 BEGIN DELETE FROM vendor_private.document; EXCEPTION WHEN raise_exception THEN blocked:=true; END;
 IF NOT blocked THEN RAISE EXCEPTION 'Submitted signature was deletable'; END IF;
 UPDATE vendor_private.application SET status='ARCHIVED' WHERE reference='VND-TEST';
 IF (SELECT count(*) FROM vendor_private.revision)<>1 THEN RAISE EXCEPTION 'Archiving lost signed history'; END IF;
END $$;
RESET ROLE;
SELECT 'VENDOR_SQL_CHECKS_PASSED';
"""
with tempfile.TemporaryDirectory(prefix='adesa-vendor-sql-') as directory:
 subprocess.run([str(PG/'initdb'),'-D',directory,'-U','vendor_migration_check','-A','trust'],check=True,capture_output=True)
 # Standalone PostgreSQL allocates bootstrap-range OIDs; those bypass RLS by design.
 # Advance past FirstNormalObjectId before creating application relations.
 prelude="DO $$ DECLARE generated oid; BEGIN LOOP generated:=lo_create(0);PERFORM lo_unlink(generated);EXIT WHEN generated>=16384;END LOOP;END $$;\n"
 migrations=sorted((ROOT/'backend/src/main/resources/db/migration').glob('V*__*.sql'),key=lambda p:int(p.name.split('__')[0][1:]))
 sql='\n'.join(line for line in (prelude+'\n'.join(p.read_text() for p in migrations)+'\n'+assertions).splitlines() if line.strip())+'\n\n'
 result=subprocess.run([str(PG/'postgres'),'--single','-j','-D',directory,'postgres'],input=sql,text=True,capture_output=True)
 output=result.stdout+result.stderr
 if result.returncode or 'ERROR:' in output or 'VENDOR_SQL_CHECKS_PASSED' not in output:print(output);raise SystemExit(1)
 print('PASS: V1–V22 migrations, forced row-level security for non-superuser owner, isolated vendor scope, private admin notes, immutable signed revisions/documents and safe archiving.')
