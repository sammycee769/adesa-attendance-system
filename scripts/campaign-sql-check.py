#!/usr/bin/env python3
"""Run real PostgreSQL migration/constraint tests without opening network sockets.
Only accepts the disposable cluster created under /tmp by this test.
"""
import pathlib, subprocess, tempfile
root=pathlib.Path(__file__).resolve().parents[1]
bin=pathlib.Path('/usr/lib/postgresql/16/bin')
with tempfile.TemporaryDirectory(prefix='adesa-campaign-sql-') as directory:
    result=subprocess.run([str(bin/'initdb'),'-D',directory,'-U','campaign_check','-A','trust'],capture_output=True,text=True)
    if result.returncode: raise RuntimeError(result.stderr)
    migrations=sorted((root/'backend/src/main/resources/db/migration').glob('V*__*.sql'),key=lambda path:int(path.name.split('__')[0][1:]))
    migration='\n'.join(path.read_text() for path in migrations)
    assertions=r"""
DO $$ DECLARE e uuid := '00000000-0000-0000-0000-000000000001'; p uuid := '00000000-0000-0000-0000-000000000002'; r uuid := '00000000-0000-0000-0000-000000000003'; changed int; BEGIN
 IF to_regclass('public.employees') IS NULL OR to_regclass('public.attendance_records') IS NULL THEN RAISE EXCEPTION 'operational schema missing after upgrade'; END IF;
 INSERT INTO campaign_private.event(id,slug,name,description,starts_at,ends_at,config) VALUES(e,'test','Test','Test',now(),now()+interval '1 day','{}');
 INSERT INTO campaign_private.participant(id,event_id,full_name,email,phone,interest,terms_version) VALUES(p,e,'Test','a@example.test','+2348012345678','ALL','v1');
 BEGIN INSERT INTO campaign_private.participant(id,event_id,full_name,email,phone,interest,terms_version) VALUES(gen_random_uuid(),e,'Duplicate','a@example.test','+2348099999999','ALL','v1'); RAISE EXCEPTION 'duplicate email accepted'; EXCEPTION WHEN unique_violation THEN NULL; END;
 BEGIN INSERT INTO campaign_private.participant(id,event_id,full_name,email,phone,interest,terms_version) VALUES(gen_random_uuid(),e,'Duplicate','b@example.test','+2348012345678','ALL','v1'); RAISE EXCEPTION 'duplicate phone accepted'; EXCEPTION WHEN unique_violation THEN NULL; END;
 INSERT INTO campaign_private.prize(id,event_id,business,name,category,quantity,approved,active,terms) VALUES(r,e,'ENERGY','Test reward','PREMIUM',1,true,true,'Test');
 UPDATE campaign_private.prize SET issued=issued+1 WHERE id=r AND approved AND active AND issued<quantity; GET DIAGNOSTICS changed=ROW_COUNT; IF changed<>1 THEN RAISE EXCEPTION 'first allocation failed'; END IF;
 UPDATE campaign_private.prize SET issued=issued+1 WHERE id=r AND approved AND active AND issued<quantity; GET DIAGNOSTICS changed=ROW_COUNT; IF changed<>0 THEN RAISE EXCEPTION 'overaward accepted'; END IF;
 BEGIN UPDATE campaign_private.prize SET issued=2 WHERE id=r; RAISE EXCEPTION 'stock constraint missing'; EXCEPTION WHEN check_violation THEN NULL; END;
 INSERT INTO campaign_private.attempt(id,participant_id,game,status,started_at,state) VALUES(gen_random_uuid(),p,'SPIN','AWARDED',now(),'{}');
 BEGIN INSERT INTO campaign_private.attempt(id,participant_id,game,status,started_at,state) VALUES(gen_random_uuid(),p,'MATCH','PLAYING',now(),'{}'); RAISE EXCEPTION 'second attempt accepted'; EXCEPTION WHEN unique_violation THEN NULL; END;
 INSERT INTO campaign_private.voucher(id,participant_id,prize_id,code,issued_at,expires_at,reward_snapshot,requested_category) VALUES(gen_random_uuid(),p,r,'TEST-VOUCHER',now(),now()+interval '90 days','{}','PREMIUM');
 UPDATE campaign_private.voucher SET redeemed_at=now(),redeemed_by='test',redemption_ref='TX1' WHERE code='TEST-VOUCHER' AND redeemed_at IS NULL AND expires_at>now(); GET DIAGNOSTICS changed=ROW_COUNT; IF changed<>1 THEN RAISE EXCEPTION 'redemption failed'; END IF;
 UPDATE campaign_private.voucher SET redeemed_at=now(),redeemed_by='test',redemption_ref='TX2' WHERE code='TEST-VOUCHER' AND redeemed_at IS NULL AND expires_at>now(); GET DIAGNOSTICS changed=ROW_COUNT; IF changed<>0 THEN RAISE EXCEPTION 'duplicate redemption accepted'; END IF;
 BEGIN INSERT INTO campaign_private.prize(id,event_id,business,name,category,quantity,terms) VALUES(gen_random_uuid(),e,'DISTRIBUTION','Forbidden','REGULAR',1,''); RAISE EXCEPTION 'excluded business accepted'; EXCEPTION WHEN check_violation THEN NULL; END;
 INSERT INTO campaign_private.rate(key_hash,bucket) VALUES('test',1) ON CONFLICT(key_hash,bucket) DO UPDATE SET hits=campaign_private.rate.hits+1;
 INSERT INTO campaign_private.rate(key_hash,bucket) VALUES('test',1) ON CONFLICT(key_hash,bucket) DO UPDATE SET hits=campaign_private.rate.hits+1;
 IF (SELECT hits FROM campaign_private.rate WHERE key_hash='test')<>2 THEN RAISE EXCEPTION 'rate counter invalid'; END IF;
 RAISE NOTICE 'CAMPAIGN_SQL_CHECKS_PASSED';
END $$;
"""
    sql='\n'.join(line for line in (migration+'\n'+assertions+"\nselect 'CAMPAIGN_SQL_CHECKS_PASSED';").splitlines() if line.strip())+'\n\n'
    result=subprocess.run([str(bin/'postgres'),'--single','-j','-D',directory,'postgres'],input=sql,capture_output=True,text=True)
    output=result.stdout+result.stderr
    if result.returncode or 'ERROR:' in output or 'CAMPAIGN_SQL_CHECKS_PASSED' not in output:
        print(output);raise SystemExit(1)
    print('PASS: full V1–V21 migration history, retained operational tables, private schema, duplicate email/phone, one attempt, inventory guard, voucher redemption, excluded businesses and rate counter on PostgreSQL 16.')
