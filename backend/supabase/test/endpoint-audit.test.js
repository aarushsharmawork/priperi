import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {generateKeyPairSync,randomBytes,randomUUID,sign} from 'node:crypto';
import {database} from './database.js';
import * as p from '../functions/relay/policy.js';

const migration=()=>readFileSync(new URL('../migrations/202610270010_security_hardening.sql',import.meta.url),'utf8');
const systemKinds=['continue','signup','device','device_login'];
async function fresh(t,options){const f=await database({accountControls:false,...options});t.after(()=>f.db.close());return f;}
// Synthetic local SQL identities only. HTTP/JWT validation has separate handler tests;
// these probes exercise the service-only transaction boundary and migration preservation.
async function actor(f,name){
 const uid=randomUUID(),session_id=randomUUID(),proof=p.hash(randomBytes(32)),installation=randomUUID(),device=randomUUID(),keys=generateKeyPairSync('ec',{namedCurve:'prime256v1'}),signing=keys.publicKey.export({type:'spki',format:'der'}).toString('base64'),hpke='local SQL audit fixture',fingerprint=p.hash(hpke+'|'+signing);
 await f.db.query('INSERT INTO auth.users(id,email) VALUES($1,$2)',[uid,uid+'@accounts.priperi.invalid']);
 await f.db.query('INSERT INTO relay.profiles(uid,username,hpke,signing,fingerprint,device_hash,installation,device_id) VALUES($1,$2,$3,$4,$5,$6,$7,$8)',[uid,name,hpke,signing,fingerprint,proof,installation,device]);
 await f.db.query('INSERT INTO auth.sessions(id,user_id) VALUES($1,$2)',[session_id,uid]);return {uid,session_id,proof,installation,device,fingerprint,signing,keys};
}
async function rates(f,subject,count=3){for(const kind of systemKinds)await f.db.query('INSERT INTO relay.rates(subject,kind,started_at,count) VALUES($1,$2,now(),$3)',[subject,kind,count]);}
async function counts(f,subject){const rows=(await f.db.query('SELECT kind,count FROM relay.rates WHERE subject=$1 ORDER BY kind',[subject])).rows;return Object.fromEntries(rows.map(r=>[r.kind,r.count]));}
async function request(f,a,operation,payload={},recent_password=false){return f.rpc('priperi_request',{actor:a.uid,session_id:a.session_id,proof:a.proof,operation,payload,recent_password});}
function envelope(a,b,sentAt=Date.now()){
 const m={id:randomUUID(),sender:a.uid,recipient:b.uid,sentAt,senderFingerprint:a.fingerprint,recipientFingerprint:b.fingerprint,ciphertext:randomBytes(120).toString('base64')};m.signature=sign('sha256',Buffer.from(p.context(m)+'\n'+m.ciphertext),a.keys.privateKey).toString('base64');return m;
}
function sendPayload(m){return {message:m,digest:p.hash(p.context(m)+'\n'+m.ciphertext+'\n'+m.signature)};}

test('security migration moves only system counter kinds and conservatively preserves existing counts',async t=>{
 const f=await fresh(t,{securityHardening:false});await actor(f,'global');await rates(f,'global',3);
 await f.db.query("INSERT INTO relay.rates(subject,kind,started_at,count) VALUES('global','login',now(),7),('system:global','continue',now(),5)");
 await f.db.exec(migration());assert.deepEqual(await counts(f,'system:global'),{continue:8,device:3,device_login:3,signup:3});assert.deepEqual(await counts(f,'global'),{login:7});
 assert.equal((await f.db.query('SELECT count(*)::int n FROM relay.profiles')).rows[0].n,1);
});

test('a valid global username cannot reset system limits by renaming or deleting its account',async t=>{
 const f=await fresh(t),a=await actor(f,'global');await rates(f,'system:global');await f.db.query("INSERT INTO relay.rates(subject,kind,started_at,count) VALUES('global','login',now(),7)");const before=await counts(f,'system:global');
 assert.equal((await request(f,a,'renameAccount',{username:'audit_renamed'})).error,null);assert.deepEqual(await counts(f,'system:global'),before);assert.deepEqual(await counts(f,'global'),{});
 assert.equal((await request(f,a,'renameAccount',{username:'global'})).error,null);assert.equal((await request(f,a,'deleteAccount',{},true)).error,null);assert.deepEqual(await counts(f,'system:global'),before);
 assert.equal((await f.db.query('SELECT count(*)::int n FROM auth.users WHERE id=$1',[a.uid])).rows[0].n,0);
});

test('registered-phone reset for username global preserves shared limiter state',async t=>{
 const f=await fresh(t),a=await actor(f,'global');await rates(f,'system:global');const before=await counts(f,'system:global');
 const args={proof:a.proof,installation_id:a.installation,fingerprint_value:a.fingerprint,device_id_value:a.device};
 const challenge=await f.rpc('priperi_device',{...args,action:'resetChallenge',payload:{}});assert.equal(challenge.error,null);
 const result=await f.rpc('priperi_device',{...args,action:'resetAccount',payload:{uid:a.uid,nonce:challenge.data.nonce}});assert.equal(result.error,null);assert.equal(result.data.status,'deleted');
 assert.deepEqual(await counts(f,'system:global'),{...before,device:before.device+2});assert.equal((await f.db.query('SELECT count(*)::int n FROM relay.profiles WHERE uid=$1',[a.uid])).rows[0].n,0);
});

test('direct recipient storage cap counts retained expired rows and migration does not delete them',async t=>{
 const f=await fresh(t,{securityHardening:false}),a=await actor(f,'audit_sender'),b=await actor(f,'audit_receiver');
 await f.db.query("INSERT INTO relay.messages(id,sender,recipient,envelope,digest,expires_at) SELECT gen_random_uuid(),$1,$2,'{}'::jsonb,$3,now()-interval '1 second' FROM generate_series(1,500)",[a.uid,b.uid,'a'.repeat(64)]);
 // Reproduce the old bypass: expired rows were still occupying disk but did not count.
 assert.equal((await request(f,a,'sendMessage',sendPayload(envelope(a,b)))).data.state,'sent');assert.equal((await f.db.query('SELECT count(*)::int n FROM relay.messages')).rows[0].n,501);
 await f.db.exec(migration());const rejected=await request(f,a,'sendMessage',sendPayload(envelope(a,b)));assert.match(rejected.error?.message||'',/inbox_full/);assert.equal((await f.db.query('SELECT count(*)::int n FROM relay.messages')).rows[0].n,501);
});

test('new direct inserts recheck expiry after Edge validation while exact retries remain idempotent',async t=>{
 const f=await fresh(t,{securityHardening:false}),a=await actor(f,'audit_sender'),b=await actor(f,'audit_receiver');
 // Model an Edge validation that succeeded 1s earlier with 500ms freshness margin.
 // Subsequent authenticated RPC/network work crosses expiry before the insert transaction.
 const validationTime=Date.now()-1000,m=envelope(a,b,validationTime-p.RETENTION_MS+500);p.validateEnvelope(m,validationTime);p.verifyEnvelope(m,a.signing);
 assert.equal((await request(f,a,'sendMessage',sendPayload(m))).data.state,'sent');
 await f.db.exec(migration());assert.equal((await request(f,a,'sendMessage',sendPayload(m))).data.state,'sent');
 const expired=envelope(a,b,Date.now()-p.RETENTION_MS-1),future=envelope(a,b,Date.now()+120000);
 for(const bad of [expired,future])assert.match((await request(f,a,'sendMessage',sendPayload(bad))).error?.message||'',/denied/);
 const valid=envelope(a,b);assert.equal((await request(f,a,'sendMessage',sendPayload(valid))).data.state,'sent');assert.equal((await request(f,a,'sendMessage',sendPayload(valid))).data.state,'sent');
 assert.equal((await request(f,b,'acknowledgeMessage',{id:valid.id})).error,null);assert.equal((await request(f,a,'sendMessage',sendPayload(valid))).data.state,'delivered');
});

test('security replacements retain eight service-only RPCs and thirteen private RLS tables',async t=>{
 const f=await fresh(t);
 const roles=(await f.db.query("SELECT count(*)::int n FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND p.proname LIKE 'priperi_%'")).rows[0].n;assert.equal(roles,8);
 assert.equal((await f.db.query("SELECT count(*)::int n FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND p.proname LIKE 'priperi_%' AND (has_function_privilege('anon',p.oid,'EXECUTE') OR has_function_privilege('authenticated',p.oid,'EXECUTE'))")).rows[0].n,0);
 assert.equal((await f.db.query("SELECT count(*)::int n FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='relay' AND c.relkind='r' AND c.relrowsecurity")).rows[0].n,13);
 assert.equal((await f.db.query("SELECT has_function_privilege('service_role','relay.erase_account(uuid)','EXECUTE') allowed")).rows[0].allowed,false);
 for(const signature of ['priperi_begin(text,text,uuid,text,text,text,uuid)','priperi_device(text,uuid,text,uuid,text,jsonb)','priperi_begin_device(text,text,uuid,text,text,text,uuid,uuid)']){
  const row=(await f.db.query('SELECT prosrc FROM pg_proc WHERE oid=$1::regprocedure',['public.'+signature])).rows[0];assert.ok(!row.prosrc.includes("relay.charge('global',"));assert.ok(row.prosrc.includes("relay.charge('system:global',"));
 }
});


test('optional automatic-RLS event helper loses client grants without changing its owner or definition',async t=>{
 const f=await fresh(t,{securityHardening:false});
 await f.db.exec("CREATE FUNCTION public.rls_auto_enable() RETURNS event_trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path='pg_catalog' AS $$ BEGIN RETURN; END $$");
 const before=(await f.db.query("SELECT prosrc,proowner FROM pg_proc WHERE oid='public.rls_auto_enable()'::regprocedure")).rows[0];
 assert.equal((await f.db.query("SELECT has_function_privilege('anon','public.rls_auto_enable()','EXECUTE') allowed")).rows[0].allowed,true);
 await f.db.exec(migration());
 assert.deepEqual((await f.db.query("SELECT prosrc,proowner FROM pg_proc WHERE oid='public.rls_auto_enable()'::regprocedure")).rows[0],before);
 for(const role of ['anon','authenticated'])assert.equal((await f.db.query("SELECT has_function_privilege($1,'public.rls_auto_enable()','EXECUTE') allowed",[role])).rows[0].allowed,false);
});
