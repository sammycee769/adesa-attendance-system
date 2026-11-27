#!/usr/bin/env python3
"""Dedicated local campaign demo. Never reads production env files or databases."""
import json, os, pathlib, shutil, signal, socket, subprocess, sys, time, urllib.request
ROOT=pathlib.Path(__file__).resolve().parents[1]
WEB=ROOT.parent/'Adesa-hq'
STATE=ROOT/'.campaign-demo'
PG=pathlib.Path('/usr/lib/postgresql/16/bin')
DB=STATE/'db'; LOGS=STATE/'logs'; PID=STATE/'processes.json'
PORTS={'backend':8087,'home':5177,'website':3000}
def environment():
    allowed=['PATH','HOME','LANG','JAVA_HOME','JAVA_TOOL_OPTIONS']
    env={k:os.environ[k] for k in allowed if k in os.environ}
    env.update({'CAMPAIGN_DEMO_PROCESS':'1','NO_COLOR':'1'})
    # Drop inherited Java agents/proxies and every production DB/mail/API setting.
    env.pop('JAVA_TOOL_OPTIONS',None)
    return env

def command(args,**kw):
    return subprocess.run([str(a) for a in args],check=True,env=environment(),**kw)

def available(port):
    with socket.socket() as s:return s.connect_ex(('127.0.0.1',port))!=0

def wait(url,seconds=150,headers=None,process=None):
    deadline=time.time()+seconds
    while time.time()<deadline:
        if process is not None and process.poll() is not None:
            raise RuntimeError("Service exited during startup. See "+str(LOGS))
        try:
            request=urllib.request.Request(url,headers=headers or {})
            with urllib.request.urlopen(request,timeout=2) as response:
                if response.status==200:return
        except Exception:time.sleep(1)
    raise RuntimeError('Service did not become ready: '+url+'. See '+str(LOGS))

def start():
    STATE.mkdir(exist_ok=True);os.chmod(STATE,0o700);LOGS.mkdir(exist_ok=True)
    if PID.exists():raise RuntimeError('Demo state already exists. Use the restart command to restart this demo safely.')
    for port in [55441,*PORTS.values()]:
        if not available(port):raise RuntimeError(f'Port {port} is already in use. Existing processes were left alone.')
    if not PG.exists():raise RuntimeError('Install PostgreSQL 16 binaries, or update PG in this script.')
    if not DB.exists():
        print('Initialising dedicated demonstration database…',flush=True)
        command([PG/'initdb','-D',DB,'-U','campaign_local','-A','trust'],stdout=subprocess.DEVNULL)
    command([PG/'pg_ctl','-D',DB,'-l',LOGS/'postgres.log','-o','-p 55441 -h 127.0.0.1 -k '+str(STATE),'start'])
    result=subprocess.run([str(PG/'psql'),'-h','127.0.0.1','-p','55441','-U','campaign_local','-d','postgres','-tAc',"SELECT 1 FROM pg_database WHERE datname='adesa_campaign_demo'"],capture_output=True,text=True,env=environment(),check=True)
    if result.stdout.strip()!='1':command([PG/'createdb','-h','127.0.0.1','-p','55441','-U','campaign_local','adesa_campaign_demo'])
    processes={}
    def launch(name,args,cwd,extra):
        env=environment();env.update(extra)
        log=open(LOGS/(name+'.log'),'a');os.chmod(log.name,0o600)
        proc=subprocess.Popen(args,cwd=cwd,env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
        processes[name]=proc.pid;PID.write_text(json.dumps(processes));log.close()
        print(f'Started {name}, log: {LOGS/(name+".log")}',flush=True)
        return proc
    backend=launch('backend',['mvn','-o','-f','backend/pom.xml','spring-boot:run','-Dspring-boot.run.profiles=campaign-local'],ROOT,{})
    # Readiness includes the transactional demo seed, not merely an open Tomcat port.
    wait('http://127.0.0.1:8087/api/v1/campaign/public/event/demo-business-gathering',seconds=300,headers={'X-Campaign-Gateway':'local-campaign-gateway-demo-only-2026'},process=backend)
    launch('home',['npm','run','dev','--','--host','127.0.0.1','--port','5177','--strictPort'],ROOT/'frontend',{'BACKEND_URL':'http://127.0.0.1:8087','VITE_API_BASE_URL':''})
    launch('website',['npm','run','dev','--','--webpack','--hostname','127.0.0.1','--port','3000'],WEB,{'CAMPAIGN_ENABLED':'true','CAMPAIGN_BACKEND_URL':'http://127.0.0.1:8087','CAMPAIGN_GATEWAY_KEY':'local-campaign-gateway-demo-only-2026','CAMPAIGN_PUBLIC_ORIGIN':'http://localhost:3000','CAMPAIGN_TRUST_PROXY':'false','RESEND_CONTACT_API_KEY':'re_local_build_placeholder','NEXT_TELEMETRY_DISABLED':'1'})
    wait('http://localhost:5177');wait('http://localhost:3000/api/campaign/event/demo-business-gathering')
    print('Ready. Public: http://localhost:3000/play/demo-business-gathering\nHQ–Home: http://localhost:5177/events-leads\nDemo staff: admin@campaign.example.test / Campaign-Demo-Only-2026!\nViewer: viewer@campaign.example.test / same password\nOnly synthetic .test participant emails are accepted. No live campaign email is sent.')

def stop():
    if PID.exists():
        for name,pid in json.loads(PID.read_text()).items():
            try:
                # A PID may have been reused. Only terminate a marked demo process group.
                env=pathlib.Path(f'/proc/{pid}/environ').read_bytes()
                if b'CAMPAIGN_DEMO_PROCESS=1\x00' not in env:continue
                os.killpg(pid,signal.SIGTERM);print('Stopped '+name)
            except (FileNotFoundError,ProcessLookupError,PermissionError):pass
        PID.unlink()
    if (DB/'postmaster.pid').exists():command([PG/'pg_ctl','-D',DB,'stop','-m','fast'])

def status():
    print('Demo data: '+str(STATE))
    if PID.exists():print('Processes: '+PID.read_text())
    for name,port in PORTS.items():print(name+(': stopped' if available(port) else ': port is listening'))

if __name__=='__main__':
    try:
        action=sys.argv[1] if len(sys.argv)>1 else 'status'
        if action=='start':start()
        elif action=='restart':
            stop();start()
        elif action=='stop':stop()
        elif action=='status':status()
        else:raise ValueError('Use start, restart, stop or status. To reset, stop and explicitly remove only .campaign-demo/.')
    except Exception as error:print('Demo setup stopped: '+str(error),file=sys.stderr);sys.exit(1)
