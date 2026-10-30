import {test} from 'node:test';
import assert from 'node:assert/strict';
import {randomBytes,randomUUID} from 'node:crypto';
import {readFileSync} from 'node:fs';
import {database} from './database.js';
import {signupBinding,signupProtection} from '../functions/relay/signup.js';
const siteKey='0x4AAAAA-public-test-only',secret='server-test-only-secret',projectUrl='https://example.supabase.co',binding='a'.repeat(64);
const verdict=extra=>({success:true,hostname:'example.supabase.co',action:'priperi_signup',cdata:binding,challenge_ts:new Date().toISOString(),...extra});
const verifier=(result,inspect)=>signupProtection({siteKey,secret,projectUrl,fetcher:async(url,options)=>{assert.equal(url,'https://challenges.cloudflare.com/turnstile/v0/siteverify');assert.equal(options.redirect,'error');const body=JSON.parse(options.body);assert.deepEqual(Object.keys(body).sort(),['response','secret']);assert.equal(body.secret,secret);if(inspect)inspect(options);return Response.json(result);}});
test('human proof requires provider success, host, action, binding and fresh time',async()=>{
 assert.equal((await verifier(verdict()).verify('valid-test-token',binding)).length,64);
 for(const bad of [{success:false},{hostname:'attacker.example'},{action:'other'},{cdata:'b'.repeat(64)},{challenge_ts:new Date(Date.now()-301000).toISOString()},{challenge_ts:new Date(Date.now()+60000).toISOString()},{challenge_ts:'bad'}])await assert.rejects(()=>verifier(verdict(bad)).verify('valid-test-token',binding));
 await assert.rejects(()=>verifier(verdict()).verify('',binding));
 assert.throws(()=>signupProtection({projectUrl}).ready());
});
test('verification transport failures/oversized output never grant admission and pages expose no secret',async()=>{
 const failed=signupProtection({siteKey,secret,projectUrl,fetcher:async()=>{throw new Error('offline');}});await assert.rejects(()=>failed.verify('valid-test-token',binding));
 const huge=signupProtection({siteKey,secret,projectUrl,fetcher:async()=>new Response(' '.repeat(16385))});await assert.rejects(()=>huge.verify('valid-test-token',binding));
 const page=verifier(verdict()).page(new Request(projectUrl+'/functions/v1/relay/signup?binding='+binding));assert.ok(page.headers.get('Content-Security-Policy').includes("frame-ancestors 'none'"));assert.ok(page.headers.get('Content-Type').startsWith('application/json'));const {html}=await page.json();assert.ok(html.includes(siteKey));assert.ok(!html.includes(secret));assert.throws(()=>verifier(verdict()).page(new Request(projectUrl+'/functions/v1/relay/signup?binding=%3Cscript%3E')));
});
function identity(i){return {name:'qa_human_'+i,proof:randomBytes(32).toString('hex'),installation_id:randomUUID(),device_id_value:randomUUID(),hpke_key:'synthetic SQL public key',signing_key:'synthetic SQL signing key',fingerprint_value:randomBytes(32).toString('hex'),expected_actor:null};}
async function admit(f,v,token=randomBytes(32).toString('hex')){const r=await f.rpc('priperi_signup_admit',{token_hash:token,proof:v.proof,installation_id:v.installation_id,device_id_value:v.device_id_value,account_name:v.name,fingerprint_value:v.fingerprint_value});assert.equal(r.error,null);return {data:r.data,token};}
async function begin(f,v){const r=await f.rpc('priperi_begin_device',v);assert.equal(r.error,null,r.error?.message);return r.data;}
test('raw rotating identifiers cannot reserve names or spend the old shared20 signup slots',async t=>{
 const f=await database({signupProtection:true});t.after(()=>f.db.close());
 for(let i=0;i<60;i++)assert.equal((await begin(f,identity(i))).humanRequired,true);
 assert.equal((await f.db.query('SELECT count(*)::int n FROM relay.pending_accounts')).rows[0].n,0);
 assert.equal((await f.db.query('SELECT count(*)::int n FROM relay.rates')).rows[0].n,0);
 for(let i=0;i<21;i++){const v=identity(i);assert.equal((await admit(f,v)).data.admitted,true);assert.equal((await begin(f,v)).pending,true);}
 assert.equal((await f.db.query('SELECT count(*)::int n FROM relay.pending_accounts')).rows[0].n,21);
});
test('admission binds identity/name/device, is one-use, and retry of a pending account remains possible',async t=>{
 const f=await database({signupProtection:true});t.after(()=>f.db.close());const v=identity(1),a=await admit(f,v);
 for(const changed of [{name:'other_name'},{device_id_value:randomUUID()},{proof:randomBytes(32).toString('hex')},{installation_id:randomUUID()},{fingerprint_value:'b'.repeat(64)}])assert.equal((await begin(f,{...v,...changed})).humanRequired,true);
 const first=await begin(f,v);assert.equal(first.pending,true);assert.equal((await begin(f,v)).uid,first.uid);
 assert.equal((await admit(f,v,a.token)).data.admitted,false);
 const used=(await f.db.query('SELECT * FROM relay.signup_admissions WHERE token_hash=$1',[a.token])).rows[0];assert.equal(used.used,true);for(const field of ['proof','installation','device_id','name','fingerprint'])assert.equal(used[field],null);
 const other=identity(2);await admit(f,other);await f.db.query("UPDATE relay.signup_admissions SET expires_at=now()-interval '1 second' WHERE proof=$1",[other.proof]);assert.equal((await begin(f,other)).humanRequired,true);
});
test('captcha admission is private and unexpected migration definitions roll back',async t=>{
 const f=await database({signupProtection:true});t.after(()=>f.db.close());const grants=await f.db.query("SELECT has_function_privilege('anon','public.priperi_signup_admit(text,text,uuid,uuid,text,text)','EXECUTE') a, has_function_privilege('authenticated','public.priperi_signup_admit(text,text,uuid,uuid,text,text)','EXECUTE') u,has_table_privilege('anon','relay.signup_admissions','SELECT') s");assert.deepEqual(grants.rows[0],{a:false,u:false,s:false});
 const other=await database();t.after(()=>other.db.close());await other.db.exec("CREATE OR REPLACE FUNCTION public.priperi_begin(name text,proof text,installation_id uuid,hpke_key text,signing_key text,fingerprint_value text,expected_actor uuid DEFAULT NULL) RETURNS jsonb LANGUAGE sql AS 'SELECT ''{}''::jsonb'");await assert.rejects(()=>other.db.exec(readFileSync(new URL('../migrations/202610300017_signup_admission.sql',import.meta.url),'utf8')));await other.db.exec('ROLLBACK');assert.equal((await other.db.query("SELECT to_regclass('relay.signup_admissions') x")).rows[0].x,null);
});
test('binding changes with any signup scope field and contains no raw password',()=>{const value={proof:binding,installation:randomUUID(),device:randomUUID(),name:'alice',fingerprint:'b'.repeat(64)},base=signupBinding(value);assert.equal(base.length,64);for(const field of Object.keys(value))assert.notEqual(signupBinding({...value,[field]:value[field]+'x'}),base);});

test('relay requests cannot spoof verification flags; valid proof creates an account and ordinary sign-in does not need it again',async t=>{
 const {androidServer}=await import('./android-server.js');const {generateKeyPairSync}=await import('node:crypto');const server=await androidServer({signupProtection:true});t.after(()=>server.db.close());
 const keys=generateKeyPairSync('ec',{namedCurve:'prime256v1'}),data={operation:'continue',username:'qa_protected',password:'Protected-Password-2026!',deviceSecret:randomBytes(32).toString('base64url'),installation:randomUUID(),deviceId:randomUUID(),signing:keys.publicKey.export({type:'spki',format:'der'}).toString('base64'),hpke:JSON.stringify({primaryKeyId:12,key:[{keyId:12,status:'ENABLED',outputPrefixType:'TINK',keyData:{typeUrl:'type.googleapis.com/google.crypto.tink.HpkePublicKey',keyMaterialType:'ASYMMETRIC_PUBLIC',value:randomBytes(48).toString('base64')}}]})};
 async function call(value){const r=await server.handle(new Request('http://10.0.2.2:5001/functions/v1/relay',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(value)}));return {status:r.status,body:await r.json()};}
 assert.equal((await call({...data,humanVerified:true,captchaPassed:true})).body.error.code,'signup-verification-required');assert.equal((await server.db.query('SELECT count(*)::int n FROM relay.pending_accounts')).rows[0].n,0);
 assert.equal((await call({...data,signupToken:'fabricated-token'})).status,403);
 const fp=(await import('../functions/relay/policy.js')).hash(data.hpke+'|'+data.signing),scope=signupBinding({proof:(await import('../functions/relay/policy.js')).secretHash(data.deviceSecret),installation:data.installation,device:data.deviceId,name:data.username,fingerprint:fp});
 const page=await server.handle(new Request('http://10.0.2.2:5001/functions/v1/relay/signup?binding='+scope));const token=(await page.text()).match(/qa-human-[a-f0-9-]+/)[0];assert.equal((await call({...data,signupToken:token})).status,200);assert.equal((await call(data)).status,200);
 assert.equal((await call({...data,deviceSecret:randomBytes(32).toString('base64url')})).status,401);
});
test('claimed login/administrator/activity flags cannot replace a server session',async t=>{
 const {androidServer}=await import('./android-server.js');const server=await androidServer();t.after(()=>server.db.close());
 for(const operation of ['getAccount','sendMessage','deleteAccount','createGroup']){const r=await server.handle(new Request('http://10.0.2.2:5001/functions/v1/relay',{method:'POST',headers:{'Content-Type':'application/json','X-App-Verified':'true'},body:JSON.stringify({operation,deviceSecret:randomBytes(32).toString('base64url'),authenticated:true,admin:true,skipLogin:true})}));assert.equal(r.status,401);}
});
