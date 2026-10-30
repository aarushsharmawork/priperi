import { createServer } from 'node:http';
import { createServer as createTlsServer } from 'node:https';
import {mkdtempSync,readFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {execFileSync} from 'node:child_process';
import { spawn } from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {androidServer} from '../supabase/test/android-server.js';
process.chdir(fileURLToPath(new URL('.',import.meta.url)));
const backend=await androidServer({signupProtection:process.env.PRIPERI_SIGNUP_TESTS==='yes'});
let tlsServer,temporaryTls,redirectCaptures=0,tlsRequests=0;
if(process.env.PRIPERI_TRANSPORT_AUDIT==='yes'){
 temporaryTls=mkdtempSync(join(tmpdir(),'priperi-transport-'));
 execFileSync('openssl',['req','-x509','-newkey','rsa:2048','-nodes','-keyout',join(temporaryTls,'key.pem'),'-out',join(temporaryTls,'cert.pem'),'-days','1','-subj','/CN=10.0.2.2','-addext','subjectAltName=IP:10.0.2.2'],{stdio:'ignore'});
 tlsServer=createTlsServer({key:readFileSync(join(temporaryTls,'key.pem')),cert:readFileSync(join(temporaryTls,'cert.pem'))},(req,res)=>{tlsRequests++;res.end('{}');});
 tlsServer.on('tlsClientError',()=>{});await new Promise(resolve=>tlsServer.listen(5443,'127.0.0.1',resolve));
 rmSync(temporaryTls,{recursive:true,force:true});
}
let delayedSyncs=0,nextSyncDelay=0,nextDelayOperation='syncInbox',nextDelayStatus=0,refreshError=false,fullInbox=null;
const server=createServer(async(req,res)=>{
 if(process.env.PRIPERI_TRANSPORT_AUDIT==='yes'&&req.url.startsWith('/audit/')){
  if(req.url==='/audit/redirect'){res.writeHead(302,{Location:'http://10.0.2.2:5001/audit/capture','Content-Type':'application/json'});res.end('{}');return;}
  if(req.url==='/audit/capture')redirectCaptures++;
  res.setHeader('Content-Type','application/json');res.end(JSON.stringify({redirectCaptures,tlsRequests}));return;
 }
 try {
  const parts=[];let size=0;for await(const chunk of req){size+=chunk.length;if(size>512000){res.writeHead(400);res.end();return;}parts.push(chunk);}
  if(process.env.PRIPERI_SPEED_TESTS==='yes'){
   if(req.url==='/qa/inbox-full'){fullInbox=JSON.parse(Buffer.concat(parts).toString()).uid;res.end('{}');return;}
   if(fullInbox){const data=JSON.parse(Buffer.concat(parts).toString()||'{}');if(data.operation==='sendMessage'&&data.message?.recipient===fullInbox){fullInbox=null;res.writeHead(429,{'Content-Type':'application/json'});res.end(JSON.stringify({error:{code:'resource-exhausted',message:'Your contact’s inbox is full. Try later.'}}));return;}}
   if(req.url==='/qa/refresh-error'){refreshError=true;res.end('{}');return;}
   if(refreshError&&req.url.startsWith('/auth/v1/token')){refreshError=false;res.writeHead(503,{'Content-Type':'application/json'});res.end(JSON.stringify({error_code:'unexpected_failure',msg:'Temporary test outage'}));return;}
   if(req.url==='/qa/sync-delay'||req.url==='/qa/ack-delay'){nextSyncDelay=2000;nextDelayOperation=req.url==='/qa/ack-delay'?'acknowledgeMessages':'syncInbox';res.end('{}');return;}
   if(req.url==='/qa/stale-401'){nextSyncDelay=2000;nextDelayOperation='syncInbox';nextDelayStatus=401;res.end('{}');return;}
   if(req.url==='/qa/presence-delay'){nextSyncDelay=2500;nextDelayOperation='accountPresence';nextDelayStatus=0;res.end('{}');return;}
   if(req.url==='/qa/policy-error'){nextSyncDelay=1500;nextDelayOperation='getAccountPolicy';nextDelayStatus=503;res.end('{}');return;}
   if(req.url==='/qa/delay-state'){res.end(JSON.stringify({active:delayedSyncs}));return;}
   if(nextSyncDelay&&JSON.parse(Buffer.concat(parts).toString()||'{}').operation===nextDelayOperation){const delay=nextSyncDelay,status=nextDelayStatus;nextSyncDelay=0;nextDelayStatus=0;delayedSyncs++;try{await new Promise(r=>setTimeout(r,delay));}finally{delayedSyncs--;}if(status){res.writeHead(status,{'Content-Type':'application/json'});res.end(JSON.stringify({error:{code:'unauthenticated',message:'Sign in again.'}}));return;}}
  }
  const result=await backend.handle(new Request('http://10.0.2.2:5001'+req.url,{method:req.method,headers:req.headers,body:['GET','HEAD'].includes(req.method)?undefined:Buffer.concat(parts)}));
  res.writeHead(result.status,Object.fromEntries(result.headers));res.end(Buffer.from(await result.arrayBuffer()));
 }catch{res.writeHead(503);res.end('{}');}
});
await new Promise(resolve=>server.listen(5001,'127.0.0.1',resolve));
async function run(command,args) {
  return new Promise(resolve=>{
    let output=''; const child=spawn(command,args,{env:process.env,stdio:['ignore','pipe','pipe']});
    child.stdout.on('data',data=>{process.stdout.write(data);output+=data.toString();});
    child.stderr.on('data',data=>process.stderr.write(data));
    child.on('exit',code=>resolve(code || /FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed/.test(output) ? 1 : 0));
  });
}
let code=await run('../../priperi/gradlew',['-p','../../priperi',':app:assembleDebug',':app:assembleDebugAndroidTest','-PpriperiTestDevices=true','-PpriperiBackendUrl=http://10.0.2.2:5001/functions/v1/relay']);
if(!code)code=await run('adb',['-s','emulator-5554','install','-r','../../priperi/app/build/outputs/apk/debug/app-debug.apk']);
if(!code)code=await run('adb',['-s','emulator-5554','install','-r','../../priperi/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']);
if(!code)code=await run('adb',['-s','emulator-5554','shell','pm','revoke','com.priperi.debug','android.permission.POST_NOTIFICATIONS']);
if(!code&&process.env.PRIPERI_MATRIX_ONLY!=='yes'&&process.env.PRIPERI_STARTUP_ONLY!=='yes')code=await run('adb',['-s','emulator-5554','shell','am','instrument','-w','-e','delayFixture',process.env.PRIPERI_SPEED_TESTS||'no',...(process.env.PRIPERI_TEST_CLASS?['-e','class',process.env.PRIPERI_TEST_CLASS,'-e','qr','yes','-e','nfc','yes','-e','actions','yes','-e','securityAudit','yes','-e','accountControls','yes','-e','optimisation','yes','-e','approval','yes','-e','backup','yes','-e','groups','yes']:[]),'com.priperi.debug.test/androidx.test.runner.AndroidJUnitRunner']);
for(const phase of process.env.PRIPERI_STARTUP_ONLY==='yes'?['grant','deny']:process.env.PRIPERI_MATRIX_ONLY==='yes'||process.env.PRIPERI_TEST_CLASS?[]:['grant','deny']) {
 if(!code)code=await run('adb',['-s','emulator-5554','shell','pm','revoke','com.priperi.debug','android.permission.POST_NOTIFICATIONS']);
 if(!code)code=await run('adb',['-s','emulator-5554','shell','pm','clear-permission-flags','com.priperi.debug','android.permission.POST_NOTIFICATIONS','user-set','user-fixed']);
 if(!code)code=await run('adb',['-s','emulator-5554','shell','am','instrument','-w','-e','class','com.priperi.StartupPermissionTest','-e','startupPermission',phase,'com.priperi.debug.test/androidx.test.runner.AndroidJUnitRunner']);
}
if(!code&&process.env.PRIPERI_SCREEN_MATRIX==='yes') {
 await run('adb',['-s','emulator-5554','shell','rm','-rf','/sdcard/Android/data/com.priperi.debug/files/layout-previews']);
 const cases=[
  ['compact-light',720,1280,360,'1.0','0'],
  ['phone-dark',1080,2220,480,'1.3','1'],
  ['large-light',1236,2745,480,'1.0','0'],
  ['landscape-dark',1280,720,320,'1.3','1'],
  ['tablet-light',1600,2560,320,'1.0','0'],
  ['compact-large-text',720,1280,360,'1.8','1'],
 ];
 try {
  await run('adb',['-s','emulator-5554','shell','settings','put','secure','show_ime_with_hard_keyboard','1']);
  for(const [label,width,height,density,font,theme] of cases) {
   if(code)break;
   if(process.env.PRIPERI_SCREEN_CASE&&!process.env.PRIPERI_SCREEN_CASE.split(',').includes(label))continue;
   process.stdout.write('SCREEN_CASE '+JSON.stringify({label,width,height,density,font,theme})+'\n');
   code=await run('adb',['-s','emulator-5554','shell','wm','size',width+'x'+height]);
   if(!code)code=await run('adb',['-s','emulator-5554','shell','wm','density',String(density)]);
   if(!code)code=await run('adb',['-s','emulator-5554','shell','settings','put','system','font_scale',font]);
   if(!code)code=await run('adb',['-s','emulator-5554','shell','am','instrument','-w','-e','class','com.priperi.ScreenLayoutTest','-e','screenMatrix','yes','-e','screenLabel',label,'-e','theme',theme,'com.priperi.debug.test/androidx.test.runner.AndroidJUnitRunner']);
  }
  await run('adb',['-s','emulator-5554','pull','/sdcard/Android/data/com.priperi.debug/files/layout-previews',process.env.PRIPERI_EVIDENCE_DIR||'../../local-only/evidence/v2.4.0/']);
 } finally {
  await run('adb',['-s','emulator-5554','shell','wm','size','reset']);
  await run('adb',['-s','emulator-5554','shell','wm','density','reset']);
  await run('adb',['-s','emulator-5554','shell','settings','put','system','font_scale','1.0']);
  await run('adb',['-s','emulator-5554','shell','settings','put','secure','show_ime_with_hard_keyboard','0']);
 }
}
if(!code&&process.env.PRIPERI_MATRIX_ONLY!=='yes'&&process.env.PRIPERI_STARTUP_ONLY!=='yes'&&!process.env.PRIPERI_TEST_CLASS)code=await run('adb',['-s','emulator-5554','pull','/sdcard/Android/data/com.priperi.debug/files/ui-previews','../../docs/']);
server.close();if(tlsServer)tlsServer.close();await backend.db.close();process.exitCode=code;
