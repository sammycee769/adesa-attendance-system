#!/usr/bin/env python3
"""Local vendor presentation. Dedicated DB/account and staged website, never production env files."""
import os,pathlib,subprocess,socket,json,time,urllib.request,shutil,signal,sys
ROOT=pathlib.Path(__file__).resolve().parents[1];WEB=ROOT.parent/'Adesa-hq'
STATE=ROOT/'.vendor-demo';DB=STATE/'db';LOG=STATE/'logs';PIDS=STATE/'processes.json';STAGED=STATE/'website';PG=pathlib.Path('/usr/lib/postgresql/16/bin')
def env(extra=None):
 result={k:os.environ[k] for k in ['PATH','HOME','LANG','JAVA_HOME'] if k in os.environ};result.update({'VENDOR_DEMO_PROCESS':'1','NO_COLOR':'1'});result.update(extra or {});return result
def run(args,**kw):return subprocess.run([str(a) for a in args],env=env(),check=True,**kw)
def free(port):
 with socket.socket() as s:return s.connect_ex(('127.0.0.1',port))!=0
def wait(url,process=None,headers=None,seconds=360):
 end=time.time()+seconds
 while time.time()<end:
  if process and process.poll() is not None:raise RuntimeError('A demo service exited. See '+str(LOG))
  try:
   with urllib.request.urlopen(urllib.request.Request(url,headers=headers or {}),timeout=3) as response:
    if response.status==200:return
  except Exception:time.sleep(1)
 raise RuntimeError('Service not ready: '+url+'; see '+str(LOG))
def prepare_website():
 STATE.mkdir(exist_ok=True);os.chmod(STATE,0o700)
 # Separate output directory: the campaign's running Next.js dev server is not touched.
 STAGED.mkdir(exist_ok=True)
 for item in WEB.iterdir():
  if item.name.startswith(('.env','.next','.git')) or item.name in ['node_modules','test-results','playwright-report']:continue
  target=STAGED/item.name
  if item.is_dir():shutil.copytree(item,target,dirs_exist_ok=True,ignore=shutil.ignore_patterns('__pycache__'))
  elif item.is_file():shutil.copy2(item,target)
 modules=STAGED/'node_modules'
 if not modules.exists():modules.symlink_to(WEB/'node_modules',target_is_directory=True)
 return STAGED
def start():
 STATE.mkdir(exist_ok=True);os.chmod(STATE,0o700);LOG.mkdir(exist_ok=True)
 if PIDS.exists():raise RuntimeError('Demo state already exists. Use restart.')
 for port in [55442,8088,5180,3110]:
  if not free(port):raise RuntimeError(f'Port {port} is occupied; existing services were left alone.')
 if not DB.exists():run([PG/'initdb','-D',DB,'-U','vendor_local','-A','trust'],stdout=subprocess.DEVNULL)
 run([PG/'pg_ctl','-D',DB,'-l',LOG/'postgres.log','-o',f'-p 55442 -h 127.0.0.1 -k {STATE}','start'])
 psql=[PG/'psql','-h','127.0.0.1','-p','55442','-U','vendor_local','-d','postgres','-v','ON_ERROR_STOP=1']
 role=subprocess.run([str(x) for x in psql]+['-tAc',"select 1 from pg_roles where rolname='vendor_app'"],env=env(),capture_output=True,text=True,check=True)
 if role.stdout.strip()!='1':run(psql+['-c',"create role vendor_app login nosuperuser nobypassrls password 'vendor_demo_only'"])
 check=subprocess.run([str(x) for x in psql]+['-tAc',"select 1 from pg_database where datname='adesa_vendor_demo'"],env=env(),capture_output=True,text=True,check=True)
 if check.stdout.strip()!='1':run([PG/'createdb','-h','127.0.0.1','-p','55442','-U','vendor_local','-O','vendor_app','adesa_vendor_demo'])
 prepare_website()
 processes={}
 def launch(name,args,cwd,extra=None):
  output=open(LOG/(name+'.log'),'w');os.chmod(output.name,0o600)
  child=subprocess.Popen(args,cwd=cwd,env=env(extra),stdout=output,stderr=subprocess.STDOUT,start_new_session=True);output.close();processes[name]=child.pid;PIDS.write_text(json.dumps(processes));print('Started '+name+'; log: '+str(LOG/(name+'.log')),flush=True);return child
 backend=launch('backend',['mvn','-o','-f','backend/pom.xml','spring-boot:run','-Dspring-boot.run.profiles=vendor-local','-Dspring-boot.run.jvmArguments=-Djava.awt.headless=true'],ROOT)
 # Public registration endpoint requires a token; health is followed by the completed seed log marker.
 wait('http://127.0.0.1:8088/actuator/health',backend)
 deadline=time.time()+180
 while time.time()<deadline:
  if backend.poll() is not None:raise RuntimeError('Vendor backend exited; inspect backend.log')
  if 'VENDOR_DEMO_READY' in (LOG/'backend.log').read_text():break
  time.sleep(1)
 else:raise RuntimeError('Vendor seed did not complete; inspect backend.log')
 home=launch('home',['npm','run','dev','--','--host','127.0.0.1','--port','5180','--strictPort'],ROOT/'frontend',{'BACKEND_URL':'http://127.0.0.1:8088','VITE_API_BASE_URL':''})
 website=launch('website',['npm','run','dev','--','--webpack','--hostname','127.0.0.1','--port','3110'],STAGED,{'VENDOR_ENABLED':'true','VENDOR_SITE':'HOLDINGS','VENDOR_BACKEND_URL':'http://127.0.0.1:8088','VENDOR_GATEWAY_KEY':'local-vendor-gateway-demo-only-2026','VENDOR_PUBLIC_ORIGIN':'http://localhost:3110','RESEND_CONTACT_API_KEY':'re_local_build_placeholder','NEXT_TELEMETRY_DISABLED':'1'})
 wait('http://localhost:5180',home);wait('http://localhost:3110/vendors/register',website)
 print('Ready. Administrator: http://localhost:5180/vendors\nLogin: admin@vendor.example.test / Vendor-Demo-Only-2026!\nHR denial test: hr@vendor.example.test / same password\nEmployee denial test: employee@vendor.example.test / same password\nOpen Registration Links to copy a valid branded invitation. Plain registration pages intentionally do not expose the form without a valid link.\nMail remains disabled; only synthetic .test contacts are accepted.')
def stop():
 if PIDS.exists():
  for name,pid in json.loads(PIDS.read_text()).items():
   try:
    if b'VENDOR_DEMO_PROCESS=1\x00' not in pathlib.Path(f'/proc/{pid}/environ').read_bytes():continue
    os.killpg(pid,signal.SIGTERM);print('Stopped '+name)
   except (FileNotFoundError,ProcessLookupError,PermissionError):pass
  PIDS.unlink()
 if (DB/'postmaster.pid').exists():run([PG/'pg_ctl','-D',DB,'stop','-m','fast'])
def status():
 print('Local vendor data: '+str(STATE))
 if PIDS.exists():print('Processes: '+PIDS.read_text())
 for port in [55442,8088,5180,3110]:print(port,'stopped' if free(port) else 'listening')
if __name__=='__main__':
 try:
  action=sys.argv[1] if len(sys.argv)>1 else 'status'
  if action=='start':start()
  elif action=='restart':stop();start()
  elif action=='prepare':print(prepare_website())
  elif action=='stop':stop()
  elif action=='status':status()
  else:raise ValueError('Use start, restart, stop, status or prepare.')
 except Exception as error:print('Vendor demo stopped: '+str(error),file=sys.stderr);sys.exit(1)
