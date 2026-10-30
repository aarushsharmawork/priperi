import {test} from 'node:test';
import {readFileSync} from 'node:fs';
import assert from 'node:assert/strict';
import {generateKeyPairSync,randomBytes,randomUUID,sign} from 'node:crypto';
import {database} from './database.js';
import {createHandler,password,recent,body,managedPassword} from '../functions/relay/handler.js';
import * as p from '../functions/relay/policy.js';

async function fixture(t,options){
 const admin=await database(options);t.after(()=>admin.db.close());const records=new Map(),sessions=new Map(),projectUrl='https://demo.supabase.co';
 admin.auth={admin:{async getUserById(id){const record=[...records.values()].find(r=>r.id===id);return record?{data:{user:{id,app_metadata:record.app_metadata}}}:{error:{message:'not found'}};},async createUser({id,email,password,app_metadata}){if(records.has(email))return {error:{code:'email_exists'}};records.set(email,{id,password,app_metadata});await admin.db.query('INSERT INTO auth.users(id,email) VALUES($1,$2)',[id,email]);return {data:{user:{id}}};},async updateUserById(id,{password,app_metadata}){for(const r of records.values())if(r.id===id){r.password=password;if(app_metadata)r.app_metadata=app_metadata;}return {data:{user:{id}}};}}};
 function authClient(){return {auth:{async getClaims(token){return {data:{claims:sessions.get(token)},error:sessions.has(token)?null:{message:'invalid'}};},async signInWithPassword({email,password}){const user=records.get(email);if(!user||user.password!==password)return {error:{message:'bad login'}};const sid=randomUUID(),token=randomUUID();await admin.db.query('INSERT INTO auth.sessions(id,user_id) VALUES($1,$2)',[sid,user.id]);sessions.set(token,{app_metadata:user.app_metadata?{...user.app_metadata}:{},sub:user.id,session_id:sid,iss:projectUrl+'/auth/v1',aud:'authenticated',exp:Date.now()/1000+3600,amr:[{method:'password',timestamp:Date.now()/1000}]});return {data:{user:{id:user.id},session:{access_token:token,refresh_token:randomUUID(),expires_at:Date.now()/1000+3600}}};}}};}
 // This historical contact-migration fixture predates account_controls; keep its Auth stand-in explicit.
 if(options?.contactApproval===false){const seed=new Map(),original=admin.rpc;admin.rpc=async(name,args)=>{if(name!=='priperi_auth_seed')return original(name,args);if(!seed.has(args.actor))seed.set(args.actor,randomBytes(32).toString('base64url'));return {data:seed.get(args.actor),error:null};};}
 const handler=createHandler({admin,authClient,projectUrl,combineSendChecks:options?.contactApproval!==false&&options?.syncOptimization!==false}),users={};
 async function request(data,token){const res=await handler(new Request(projectUrl+'/functions/v1/relay',{method:'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},body:JSON.stringify(data)}));return {status:res.status,...await res.json()};}
 for(const name of ['alice','bob','eve']){
  const keys=generateKeyPairSync('ec',{namedCurve:'prime256v1'}),signing=keys.publicKey.export({type:'spki',format:'der'}).toString('base64'),hpke=JSON.stringify({primaryKeyId:12,key:[{keyId:12,status:'ENABLED',outputPrefixType:'TINK',keyData:{typeUrl:'type.googleapis.com/google.crypto.tink.HpkePublicKey',keyMaterialType:'ASYMMETRIC_PUBLIC',value:randomBytes(48).toString('base64')}}]});
  const setup={operation:'continue',username:name,password:'Strong-Password-2026!',deviceSecret:randomBytes(32).toString('base64url'),installation:randomUUID(),deviceId:randomUUID(),hpke,signing};
  const result=await request(setup);assert.equal(result.status,200,JSON.stringify(result));
  const {account,session}=result.data;users[name]={account,session,setup,keys,call:(operation,data={})=>request({operation,deviceSecret:setup.deviceSecret,...data},session.access_token)};
 }
 function envelope(overrides={}){const m={id:randomUUID(),sender:users.alice.account.uid,recipient:users.bob.account.uid,sentAt:Date.now(),senderFingerprint:users.alice.account.fingerprint,recipientFingerprint:users.bob.account.fingerprint,ciphertext:randomBytes(120).toString('base64'),...overrides};m.signature=sign('sha256',Buffer.from(p.context(m)+'\n'+m.ciphertext),users.alice.keys.privateKey).toString('base64');return m;}
 return {admin,handler,request,sessions,records,authClient,...users,envelope};
}
test('Continue creates or logs in without email; wrong password or another device cannot open the account',async t=>{
 const f=await fixture(t);const again=await f.request(f.alice.setup);assert.equal(again.status,200);assert.equal(again.data.account.uid,f.alice.account.uid);
 assert.equal((await f.request({...f.alice.setup,password:'Wrong-Password-2026!'})).status,401);
 assert.equal((await f.request({...f.alice.setup,deviceSecret:f.eve.setup.deviceSecret})).status,401);
 assert.equal((await f.request({...f.alice.setup,username:'second_account'})).data.account.uid,f.alice.account.uid);
 assert.ok(!JSON.stringify(again).includes('@'));assert.ok(!JSON.stringify(again).includes('deviceSecret'));
 assert.equal((await f.alice.call('getAccount',{deviceSecret:f.eve.setup.deviceSecret})).status,403);
});
test('only receiver can fetch and acknowledge, ciphertext is deleted atomically and retries cannot recreate it',async t=>{
 const f=await fixture(t),m=f.envelope();
 assert.equal((await f.alice.call('sendMessage',{message:m})).data.state,'sent');assert.equal((await f.alice.call('sendMessage',{message:m})).data.state,'sent');
 assert.deepEqual((await f.bob.call('syncInbox')).data.messages,[m]);assert.equal((await f.eve.call('syncInbox')).data.messages.length,0);
 assert.equal((await f.eve.call('acknowledgeMessage',{id:m.id})).status,403);
 assert.equal((await f.bob.call('acknowledgeMessage',{id:m.id})).status,200);assert.equal((await f.bob.call('acknowledgeMessage',{id:m.id})).status,200);
 assert.equal((await f.admin.db.query('SELECT count(*)::int AS n FROM relay.messages')).rows[0].n,0);
 assert.equal((await f.alice.call('sendMessage',{message:m})).data.state,'delivered');
 assert.equal((await f.alice.call('syncInbox',{receiptIds:[m.id]})).data.receipts.length,1);assert.equal((await f.eve.call('syncInbox',{receiptIds:[m.id]})).data.receipts.length,0);
 assert.equal((await f.alice.call('sendMessage',{message:f.envelope({id:m.id})})).status,409);
});
test('forged sender, modified ciphertext, changed keys, plaintext and blocking bypasses fail',async t=>{
 const f=await fixture(t),m=f.envelope();
 assert.equal((await f.eve.call('sendMessage',{message:m})).status,403);
 assert.equal((await f.alice.call('sendMessage',{message:{...m,ciphertext:randomBytes(120).toString('base64')}})).status,403);
 assert.equal((await f.alice.call('sendMessage',{message:f.envelope({recipientFingerprint:'a'.repeat(64)})})).status,403);
 assert.equal((await f.alice.call('sendMessage',{message:{...m,plaintext:'private'}})).status,400);
 assert.equal((await f.alice.call('sendMessage',{message:m})).status,200);
 assert.equal((await f.bob.call('setBlocked',{uid:f.alice.account.uid,blocked:true})).status,200);
 assert.equal((await f.bob.call('syncInbox')).data.messages.length,0);
 assert.equal((await f.alice.call('sendMessage',{message:f.envelope()})).status,403);
 assert.equal((await f.alice.call('lookupUser',{username:'bob'})).status,404);
 assert.equal((await f.bob.call('setBlocked',{uid:f.alice.account.uid,blocked:false})).status,200);
 assert.equal((await f.alice.call('sendMessage',{message:f.envelope()})).status,200);
});
test('username rename stays unique and preserves login; failed searches consume limits',async t=>{
 const f=await fixture(t);assert.equal((await f.alice.call('renameAccount',{username:'bob'})).status,409);
 assert.equal((await f.alice.call('renameAccount',{username:'new_alice'})).status,200);
 assert.equal((await f.request({...f.alice.setup,username:'new_alice'})).status,200);
 assert.equal((await f.bob.call('lookupUser',{username:'new_alice'})).data.uid,f.alice.account.uid);
 for(let i=0;i<20;i++)assert.equal((await f.eve.call('lookupUser',{username:'missing'})).status,404);
 assert.equal((await f.eve.call('lookupUser',{username:'alice'})).status,403);
});
test('deletion requires recent password; removes Auth, sessions, both-direction messages, receipts, blocks, rates and profile',async t=>{
 const f=await fixture(t);const m=f.envelope();await f.alice.call('sendMessage',{message:m});await f.bob.call('acknowledgeMessage',{id:m.id});await f.alice.call('sendMessage',{message:f.envelope()});await f.eve.call('setBlocked',{uid:f.alice.account.uid,blocked:true});
 const claims=f.sessions.get(f.alice.session.access_token);claims.amr[0].timestamp=1;
 assert.equal((await f.alice.call('deleteAccount')).status,401);assert.equal((await f.alice.call('changePassword',{password:'New-Password-2026!'})).status,401);
 claims.amr[0].timestamp=Date.now()/1000;
 assert.equal((await f.alice.call('deleteAccount')).data.status,'deleted');assert.equal((await f.alice.call('deleteAccount')).data.status,'deleted');assert.equal((await f.alice.call('syncInbox')).status,403);
 for(const [table,condition] of [['auth.users','id'],['auth.sessions','user_id'],['relay.profiles','uid']])assert.equal((await f.admin.db.query(`SELECT count(*)::int AS n FROM ${table} WHERE ${condition}=$1`,[f.alice.account.uid])).rows[0].n,0);
 for(const table of ['messages','receipts','blocked'])assert.equal((await f.admin.db.query(`SELECT count(*)::int AS n FROM relay.${table}`)).rows[0].n,0);
 assert.equal((await f.admin.db.query('SELECT count(*)::int AS n FROM relay.rates WHERE subject IN($1,$2,$3)',[f.alice.account.uid,'alice',p.secretHash(f.alice.setup.deviceSecret)])).rows[0].n,0);
 assert.equal((await f.request({...f.alice.setup,expectedUid:f.alice.account.uid})).status,401);
});
test('database roles cannot read private tables or call privileged functions; revoked sessions fail even with valid JWT',async t=>{
 const f=await fixture(t);
 for(const role of ['anon','authenticated']){
  await f.admin.db.exec('SET ROLE '+role);try{
   await assert.rejects(f.admin.db.query('SELECT * FROM relay.profiles'),/permission denied/);
   await assert.rejects(f.admin.db.query('SELECT public.priperi_finish($1,$2)',[f.alice.account.uid,p.secretHash(f.alice.setup.deviceSecret)]),/permission denied/);
  }finally{await f.admin.db.exec('RESET ROLE');}
 }
 await f.admin.db.query('DELETE FROM auth.sessions WHERE user_id=$1',[f.alice.account.uid]);assert.equal((await f.alice.call('getAccount')).status,403);
});
test('expiry sweeps abandoned accounts and queues, including their references',async t=>{
 const f=await fixture(t);await f.alice.call('sendMessage',{message:f.envelope()});
 await f.admin.db.exec("UPDATE relay.messages SET expires_at=now()-interval '1 second'");assert.equal((await f.bob.call('syncInbox')).data.messages.length,0);
 await f.admin.db.exec('SELECT relay.sweep()');assert.equal((await f.admin.db.query('SELECT count(*)::int AS n FROM relay.messages')).rows[0].n,0);
});
test('public boundary rejects missing tokens, overlong or invalid bodies; passwords do not silently truncate at bcrypt limit',async t=>{
 const f=await fixture(t);assert.equal((await f.request({operation:'getAccount',deviceSecret:f.alice.setup.deviceSecret})).status,401);
 assert.equal((await f.request({operation:'getAccount',deviceSecret:f.alice.setup.deviceSecret},'forged')).status,401);
 await assert.rejects(body(new Request('https://test',{method:'POST',headers:{'Content-Type':'application/json'},body:'x'.repeat(40001)})),{code:'invalid-argument'});
 assert.throws(()=>password('Aa1!'+'é'.repeat(35)));assert.throws(()=>password('Aa1!'+'x'.repeat(69)));assert.equal(password('Aa1!'+'x'.repeat(68)).length,72);
 assert.equal(recent({amr:[{method:'password',timestamp:1}]}),false);assert.equal(recent({amr:[{method:'otp',timestamp:Date.now()/1000}]}),false);
});
test('interrupted account creation resumes without replacing credentials or moving to another device',async t=>{
 const f=await fixture(t),setup={...f.eve.setup,username:'retry_account',installation:randomUUID(),deviceId:randomUUID(),deviceSecret:randomBytes(32).toString('base64url')};
 const original=f.admin.rpc;let failed=false;
 f.admin.rpc=async(name,args)=>{if(name==='priperi_finish'&&!failed){failed=true;return {error:{message:'temporary outage'}};}return original(name,args);};
 assert.equal((await f.request(setup)).status,503);
 assert.equal((await f.request({...setup,deviceSecret:f.alice.setup.deviceSecret})).status,401);
 assert.equal((await f.request({...setup,password:'Other-Password-2026!'})).status,401);
 const completed=await f.request(setup);assert.equal(completed.status,200);
 assert.equal((await f.admin.db.query('SELECT count(*)::int AS n FROM relay.pending_accounts')).rows[0].n,0);
 assert.equal((await f.admin.db.query("SELECT count(*)::int AS n FROM relay.profiles WHERE username='retry_account'")).rows[0].n,1);
});
test('recipient queue has a hard cap and concurrent block/send leaves no queued messages',async t=>{
 const f=await fixture(t);
 await f.admin.db.query(`INSERT INTO relay.messages(id,sender,recipient,envelope,digest,expires_at)
 SELECT gen_random_uuid(),$1,$2,'{}'::jsonb,$3,now()+interval '1 day' FROM generate_series(1,500)`,[f.alice.account.uid,f.bob.account.uid,'a'.repeat(64)]);
 assert.equal((await f.alice.call('sendMessage',{message:f.envelope()})).status,429);
 await f.admin.db.exec('DELETE FROM relay.messages');
 const [send,block]=await Promise.all([f.alice.call('sendMessage',{message:f.envelope()}),f.bob.call('setBlocked',{uid:f.alice.account.uid,blocked:true})]);
 assert.ok([200,403].includes(send.status));assert.equal(block.status,200);
 assert.equal((await f.admin.db.query('SELECT count(*)::int AS n FROM relay.messages')).rows[0].n,0);
});

test('device login ignores usernames, binds legacy accounts after password verification, and fails closed on ID collisions or missing proof',async t=>{
 const f=await fixture(t),base={...f.alice.setup,username:undefined};
 const status=await f.request({...base,operation:'deviceStatus'});assert.equal(status.data.registered,true);
 assert.equal((await f.alice.call('renameAccount',{username:'alice_changed'})).status,200);
 const result=await f.request(base);assert.equal(result.status,200);assert.equal(result.data.account.username,'alice_changed');assert.equal(result.data.account.uid,f.alice.account.uid);
 assert.equal((await f.request({...base,deviceId:undefined})).status,401);
 assert.equal((await f.request({...f.eve.setup,operation:'deviceStatus',deviceId:base.deviceId})).status,403);
 assert.equal((await f.request({...f.eve.setup,deviceId:base.deviceId})).status,401);
 assert.equal((await f.request({...base,deviceSecret:f.bob.setup.deviceSecret})).status,401);
 await f.admin.db.query('UPDATE relay.profiles SET device_id=NULL WHERE uid=$1',[f.alice.account.uid]);
 assert.equal((await f.request({...base,password:'Wrong-Password-2026!'})).status,401);
 assert.equal((await f.admin.db.query('SELECT device_id FROM relay.profiles WHERE uid=$1',[f.alice.account.uid])).rows[0].device_id,null);
 assert.equal((await f.request(base)).status,200);
 assert.equal((await f.admin.db.query('SELECT device_id FROM relay.profiles WHERE uid=$1',[f.alice.account.uid])).rows[0].device_id,base.deviceId);
});

test('combined search returns bounded public profiles, respects blocks and treats underscores literally',async t=>{
 const f=await fixture(t);
 const found=await f.bob.call('searchUsers',{query:'ali'});assert.equal(found.status,200);assert.equal(found.data.users[0].username,'alice');
 assert.deepEqual(Object.keys(found.data.users[0]).sort(),['fingerprint','hpke','signing','uid','username']);
 assert.equal((await f.bob.call('searchUsers',{query:'al'})).status,400);
 assert.equal((await f.bob.call('searchUsers',{query:'a%_'})).status,400);
 assert.equal((await f.bob.call('searchUsers',{query:'ali_'})).data.users.length,0);
 assert.equal((await f.request({operation:'searchUsers',query:'ali',deviceSecret:f.bob.setup.deviceSecret})).status,401);
 await f.alice.call('setBlocked',{uid:f.bob.account.uid,blocked:true});assert.equal((await f.bob.call('searchUsers',{query:'ali'})).data.users.length,0);
 await f.alice.call('setBlocked',{uid:f.bob.account.uid,blocked:false});
 await f.bob.call('setBlocked',{uid:f.alice.account.uid,blocked:true});assert.equal((await f.bob.call('searchUsers',{query:'ali'})).data.users.length,0);
});

function resetPayload(user,challenge,overrides={}){
 const data={...user.setup,operation:'resetAccount',uid:challenge.uid,nonce:challenge.nonce,...overrides};
 data.signature=sign('sha256',Buffer.from(p.resetContext(data.uid,data.nonce,data.installation,p.validateKeys(data.hpke,data.signing))),user.keys.privateKey).toString('base64');return data;
}
test('device-key reset rejects forged, expired and cross-account challenges, deletes controlled data and cannot replay onto a replacement account',async t=>{
 const f=await fixture(t),alice=f.alice,m=f.envelope();await alice.call('sendMessage',{message:m});await f.bob.call('acknowledgeMessage',{id:m.id});await alice.call('sendMessage',{message:f.envelope()});await f.eve.call('setBlocked',{uid:alice.account.uid,blocked:true});
 const challenge=(await f.request({...alice.setup,operation:'resetChallenge'})).data;assert.ok(challenge.nonce);
 assert.equal((await f.request({...resetPayload(alice,challenge),signature:sign('sha256',Buffer.from('forged'),f.eve.keys.privateKey).toString('base64')})).status,403);
 assert.equal((await f.request(resetPayload(alice,challenge,{uid:f.bob.account.uid}))).status,403);
 assert.equal((await f.request(resetPayload(alice,challenge,{nonce:randomUUID()}))).status,403);
 await f.admin.db.exec("UPDATE relay.reset_challenges SET expires_at=now()-interval '1 second'");
 assert.equal((await f.request(resetPayload(alice,challenge))).status,403);
 const fresh=(await f.request({...alice.setup,operation:'resetChallenge'})).data,request=resetPayload(alice,fresh);
 assert.equal((await f.request(request)).data.status,'deleted');assert.equal((await f.request(request)).data.status,'deleted');
 for(const [table,field] of [['auth.users','id'],['auth.sessions','user_id'],['relay.profiles','uid'],['relay.reset_challenges','uid']])assert.equal((await f.admin.db.query(`SELECT count(*)::int n FROM ${table} WHERE ${field}=$1`,[alice.account.uid])).rows[0].n,0);
 for(const table of ['messages','receipts','blocked'])assert.equal((await f.admin.db.query(`SELECT count(*)::int n FROM relay.${table}`)).rows[0].n,0);
 assert.equal((await f.admin.db.query('SELECT count(*)::int n FROM relay.rates WHERE subject IN($1,$2,$3)',[alice.account.uid,'alice',p.secretHash(alice.setup.deviceSecret)])).rows[0].n,0);
 assert.equal((await alice.call('getAccount')).status,403);
 assert.equal((await f.request({...alice.setup,operation:'deviceStatus'})).data.registered,false);
 assert.equal((await f.request({...alice.setup,expectedUid:alice.account.uid})).status,401);
 assert.equal((await f.admin.db.query('SELECT count(*)::int n FROM relay.rates WHERE subject IN($1,$2,$3)',[alice.account.uid,'alice',p.secretHash(alice.setup.deviceSecret)])).rows[0].n,0);
 const setup={...alice.setup,username:'alice',installation:randomUUID(),deviceSecret:randomBytes(32).toString('base64url')};
 const replacement=await f.request(setup);assert.equal(replacement.status,200);assert.notEqual(replacement.data.account.uid,alice.account.uid);
 assert.equal((await f.request(request)).status,403);
 assert.equal((await f.admin.db.query('SELECT count(*)::int n FROM relay.profiles WHERE uid=$1',[replacement.data.account.uid])).rows[0].n,1);
});

test('device discovery throttles persist, reset challenges expire and new RPCs remain service-only',async t=>{
 const f=await fixture(t);
 for(let i=0;i<30;i++)assert.equal((await f.request({...f.alice.setup,operation:'deviceStatus'})).status,200);
 assert.equal((await f.request({...f.alice.setup,operation:'deviceStatus'})).status,429);
 const challenge=await f.request({...f.bob.setup,operation:'resetChallenge'});assert.equal(challenge.status,200);
 await f.admin.db.exec("UPDATE relay.reset_challenges SET expires_at=now()-interval '1 second'; SELECT relay.sweep()");
 assert.equal((await f.admin.db.query('SELECT count(*)::int n FROM relay.reset_challenges')).rows[0].n,0);
 for(const role of ['anon','authenticated']){
  await f.admin.db.exec('SET ROLE '+role);try{
   await assert.rejects(f.admin.db.query('SELECT * FROM relay.reset_challenges'),/permission denied/);
   await assert.rejects(f.admin.db.query("SELECT public.priperi_device($1,$2,$3,$4,'status','{}')",[p.secretHash(f.alice.setup.deviceSecret),f.alice.setup.installation,f.alice.account.fingerprint,f.alice.setup.deviceId]),/permission denied/);
   await assert.rejects(f.admin.db.query('SELECT public.priperi_search($1,$2,$3,$4)',[f.alice.account.uid,randomUUID(),p.secretHash(f.alice.setup.deviceSecret),'bob']),/permission denied/);
  }finally{await f.admin.db.exec('RESET ROLE');}
 }
});

test('account deletion removes attributable Auth users and counters from interrupted legacy setups',async t=>{
 const f=await fixture(t),orphan=randomUUID(),proof=p.secretHash(f.alice.setup.deviceSecret);
 await f.admin.db.query('INSERT INTO auth.users(id,email) VALUES($1,$2)',[orphan,orphan+'@accounts.priperi.invalid']);
 await f.admin.db.query('INSERT INTO relay.pending_accounts(username,uid,hpke,signing,fingerprint,device_hash,installation) VALUES($1,$2,$3,$4,$5,$6,$7)',['old_pending_alice',orphan,f.alice.setup.hpke,f.alice.setup.signing,f.alice.account.fingerprint,proof,f.alice.setup.installation]);
 await f.admin.db.query("SELECT relay.charge($1,'login',10,60),relay.charge($2,'login',10,60)",['old_pending_alice',orphan]);
 assert.equal((await f.alice.call('deleteAccount')).data.status,'deleted');
 assert.equal((await f.admin.db.query('SELECT count(*)::int n FROM auth.users WHERE id=$1',[orphan])).rows[0].n,0);
 assert.equal((await f.admin.db.query('SELECT count(*)::int n FROM relay.pending_accounts WHERE uid=$1',[orphan])).rows[0].n,0);
 assert.equal((await f.admin.db.query('SELECT count(*)::int n FROM relay.rates WHERE subject IN($1,$2,$3)',['old_pending_alice',orphan,proof])).rows[0].n,0);
 assert.equal((await f.bob.call('getAccount')).status,200);
});

test('batched acknowledgements retain recipient ownership, per-message quotas, live sessions and idempotence',async t=>{
 const f=await fixture(t),messages=[f.envelope(),f.envelope()];
 for(const message of messages)assert.equal((await f.alice.call('sendMessage',{message})).status,200);
 const ids=messages.map(m=>m.id);
 assert.equal((await f.eve.call('acknowledgeMessages',{ids})).status,403);
 assert.equal((await f.admin.db.query('SELECT count(*)::int n FROM relay.messages')).rows[0].n,2);
 assert.equal((await f.bob.call('acknowledgeMessages',{ids})).status,200);
 assert.equal((await f.admin.db.query('SELECT count(*)::int n FROM relay.messages')).rows[0].n,0);
 assert.equal((await f.admin.db.query('SELECT count(*)::int n FROM relay.receipts')).rows[0].n,2);
 assert.equal((await f.admin.db.query("SELECT count FROM relay.rates WHERE subject=$1 AND kind='ack'",[f.bob.account.uid])).rows[0].count,2);
 assert.equal((await f.bob.call('acknowledgeMessages',{ids})).status,200);
 for(const message of messages)assert.equal((await f.alice.call('sendMessage',{message})).data.state,'delivered');
 await f.admin.db.query('DELETE FROM auth.sessions WHERE user_id=$1',[f.bob.account.uid]);
 assert.equal((await f.bob.call('acknowledgeMessages',{ids})).status,403);
});

test('batch API rejects missing/forged auth, malformed IDs, duplicates, oversized batches and exhausted message quotas',async t=>{
 const f=await fixture(t),id=randomUUID();
 const payload={operation:'acknowledgeMessages',deviceSecret:f.bob.setup.deviceSecret,ids:[id]};
 assert.equal((await f.request(payload)).status,401);assert.equal((await f.request(payload,'forged')).status,401);
 for(const ids of [[],[id,id],['bad-id'],Array.from({length:51},()=>randomUUID()),'not-an-array'])assert.equal((await f.bob.call('acknowledgeMessages',{ids})).status,400);
 await f.admin.db.query("INSERT INTO relay.rates(subject,kind,started_at,count) VALUES($1,'ack',now(),119)",[f.bob.account.uid]);
 assert.equal((await f.bob.call('acknowledgeMessages',{ids:[randomUUID(),randomUUID()]})).status,403);
 assert.equal((await f.admin.db.query("SELECT count FROM relay.rates WHERE subject=$1 AND kind='ack'",[f.bob.account.uid])).rows[0].count,121);
 for(const operation of ['__proto__','constructor','toString',['getAccount'],{},null])assert.equal((await f.bob.call(operation)).status,404);
});

test('sync and receipt batches reduce database round trips without caching authorization',async t=>{
 const f=await fixture(t),messages=[];
 for(let i=0;i<12;i++){
  const message=f.envelope();assert.equal((await f.alice.call('sendMessage',{message})).status,200);messages.push(message);
  if(i===0)assert.equal((await f.bob.call('approveContact',{uid:f.alice.account.uid})).status,200);
 }
 const calls=[],original=f.admin.rpc;f.admin.rpc=async(name,args)=>{calls.push({name,operation:args.operation});return original(name,args);};
 const inbox=await f.bob.call('syncInbox');assert.equal(inbox.status,200);assert.equal(inbox.data.messages.length,12);assert.ok(inbox.data.groupData);
 assert.equal(inbox.data.senderProfiles.length,1);assert.equal(inbox.data.senderProfiles[0].uid,f.alice.account.uid);
 assert.ok(!JSON.stringify(inbox.data.senderProfiles).includes('device_hash'));assert.ok(!JSON.stringify(inbox.data.senderProfiles).includes('fcm_token'));
 assert.equal(calls.length,2);calls.length=0;
 assert.equal((await f.bob.call('acknowledgeMessages',{ids:messages.map(m=>m.id)})).status,200);assert.equal(calls.length,2);
 assert.equal((await f.admin.db.query("SELECT count FROM relay.rates WHERE subject=$1 AND kind='ack'",[f.bob.account.uid])).rows[0].count,12);
 assert.equal((await f.bob.call('acknowledgeBatch',{ids:[messages[0].id]})).status,404);
 assert.equal((await f.bob.call('sendContext')).status,404);
 calls.length=0;assert.equal((await f.alice.call('sendMessage',{message:f.envelope()})).status,200);assert.equal(calls.length,2);
 await f.admin.db.query('DELETE FROM auth.sessions WHERE user_id=$1',[f.bob.account.uid]);
 assert.equal((await f.bob.call('syncInbox')).status,403);
});

test('receipt batch preserves prior valid progress and charges when a later ID has another recipient',async t=>{
 const f=await fixture(t),first=f.envelope(),foreign=f.envelope({recipient:f.eve.account.uid,recipientFingerprint:f.eve.account.fingerprint}),last=f.envelope();
 for(const message of [first,foreign,last])assert.equal((await f.alice.call('sendMessage',{message})).status,200);
 assert.equal((await f.bob.call('acknowledgeMessages',{ids:[first.id,foreign.id,last.id]})).status,403);
 assert.deepEqual((await f.admin.db.query('SELECT id FROM relay.receipts')).rows.map(r=>r.id),[first.id]);
 assert.equal((await f.admin.db.query('SELECT count(*)::int n FROM relay.messages')).rows[0].n,2);
 assert.equal((await f.admin.db.query("SELECT count FROM relay.rates WHERE subject=$1 AND kind='ack'",[f.bob.account.uid])).rows[0].count,2);
 await f.admin.db.query("UPDATE relay.rates SET count=119 WHERE subject=$1 AND kind='ack'",[f.bob.account.uid]);
 assert.equal((await f.bob.call('acknowledgeMessages',{ids:[last.id,foreign.id]})).status,403);
 assert.equal((await f.admin.db.query('SELECT count(*)::int n FROM relay.receipts')).rows[0].n,2);
 assert.equal((await f.admin.db.query('SELECT count(*)::int n FROM relay.messages')).rows[0].n,1);
 assert.equal((await f.admin.db.query("SELECT count FROM relay.rates WHERE subject=$1 AND kind='ack'",[f.bob.account.uid])).rows[0].count,121);
});

test('latency migration preserves existing queues and grants and is safe to reapply',async t=>{
 const f=await fixture(t,{syncOptimization:false}),message=f.envelope();assert.equal((await f.alice.call('sendMessage',{message})).status,200);
 const before=(await f.admin.db.query("SELECT oid::text,proacl::text FROM pg_proc WHERE pronamespace IN ('public'::regnamespace,'relay'::regnamespace) ORDER BY oid")).rows;
 const migration=readFileSync(new URL('../migrations/202610280013_sync_latency.sql',import.meta.url),'utf8');
 await f.admin.db.exec(migration);await f.admin.db.exec(migration);
 assert.deepEqual((await f.admin.db.query("SELECT oid::text,proacl::text FROM pg_proc WHERE pronamespace IN ('public'::regnamespace,'relay'::regnamespace) ORDER BY oid")).rows,before);
 assert.deepEqual((await f.bob.call('syncInbox')).data.messages,[message]);assert.equal((await f.bob.call('acknowledgeMessages',{ids:[message.id]})).status,200);
 assert.equal((await f.admin.db.query('SELECT count(*)::int n FROM relay.profiles')).rows[0].n,3);
});


test('managed Auth credential requires the private server binding seed and relay device factor',async t=>{
 const f=await fixture(t),user=f.alice,record=f.records.get(user.account.uid+'@accounts.priperi.invalid');
 const seed=(await f.admin.db.query('SELECT auth_seed FROM relay.profiles WHERE uid=$1',[user.account.uid])).rows[0].auth_seed,bound=managedPassword(seed,user.setup.password);
 assert.equal(password(bound),bound);assert.equal(bound.length,68);assert.equal(record.password,bound);
 assert.notEqual(record.password,user.setup.password);assert.notEqual(bound,managedPassword(f.bob.setup.deviceSecret,user.setup.password));
 assert.ok((await f.authClient().auth.signInWithPassword({email:user.account.uid+'@accounts.priperi.invalid',password:user.setup.password})).error);
 const result=await f.request(user.setup);assert.equal(result.status,200);assert.ok(!JSON.stringify(result).includes(bound));
 assert.equal((await f.request({...user.setup,password:'Wrong-Password-2026!'})).status,401);
});

test('legacy credential upgrade requires the real device and password and never downgrades a bound account',async t=>{
 const f=await fixture(t),user=f.alice,record=f.records.get(user.account.uid+'@accounts.priperi.invalid');
 record.password=user.setup.password;record.app_metadata={};f.sessions.get(user.session.access_token).app_metadata={};
 assert.equal((await user.call('getAccount')).status,401);
 assert.equal((await f.request({...user.setup,deviceSecret:f.eve.setup.deviceSecret})).status,401);
 assert.equal((await f.request({...user.setup,password:'Wrong-Password-2026!'})).status,401);
 assert.equal(record.password,user.setup.password);assert.deepEqual(record.app_metadata,{});
 const result=await f.request({...user.setup,username:undefined});assert.equal(result.status,200);
 assert.equal(record.app_metadata.priperi_auth_version,2);assert.equal(record.password,managedPassword((await f.admin.db.query('SELECT auth_seed FROM relay.profiles WHERE uid=$1',[user.account.uid])).rows[0].auth_seed,user.setup.password));
 assert.equal(f.sessions.get(result.data.session.access_token).app_metadata.priperi_auth_version,2);
 assert.equal((await f.request({...user.setup,username:undefined})).status,200);
 // A public Auth mutation cannot make the relay accept a password-only fallback.
 record.password=user.setup.password;assert.equal((await f.request(user.setup)).status,401);
});

test('first-contact cap survives receipt deletion, retries, rename and concurrent sends; only recipient can approve',async t=>{
 const f=await fixture(t),uid=f.alice.account.uid,recipient=f.bob.account.uid;
 assert.equal((await f.bob.call('approveContact',{uid})).status,409);
 const messages=Array.from({length:6},()=>f.envelope());
 const results=await Promise.all(messages.map(message=>f.alice.call('sendMessage',{message})));
 assert.equal(results.filter(r=>r.status===200).length,3);
 assert.equal(results.filter(r=>r.error?.code==='contact-approval-required').length,3);
 const sent=messages.filter((_,i)=>results[i].status===200);
 assert.equal((await f.alice.call('sendMessage',{message:sent[0]})).status,200);
 for(const message of sent)assert.equal((await f.bob.call('acknowledgeMessage',{id:message.id})).status,200);
 assert.equal((await f.alice.call('sendMessage',{message:sent[0]})).data.state,'delivered');
 await f.admin.db.exec('DELETE FROM relay.receipts');
 assert.equal((await f.alice.call('sendMessage',{message:f.envelope()})).error.code,'contact-approval-required');
 assert.equal((await f.alice.call('renameAccount',{username:'renamed_alice'})).status,200);
 assert.equal((await f.eve.call('approveContact',{uid,recipient})).status,409);
 assert.equal((await f.alice.call('approveContact',{uid:recipient,sender:uid,approved:true})).status,409);
 const state=(await f.bob.call('syncInbox',{contactIds:[uid]})).data.contactStates[0];assert.equal(state.incomingPending,true);
 assert.equal((await f.bob.call('approveContact',{uid})).status,200);
 assert.equal((await f.bob.call('approveContact',{uid})).status,200);
 assert.equal((await f.alice.call('sendMessage',{message:f.envelope()})).status,200);
 const mine=(await f.alice.call('syncInbox',{contactIds:[recipient]})).data.contactStates[0];assert.equal(mine.approved,true);assert.equal(mine.sentCount,3);
 // The accepted conversation is shared; retrying from either side is a no-op.
 assert.equal((await f.alice.call('approveContact',{uid:recipient})).status,200);
 assert.equal((await f.bob.call('setBlocked',{uid,blocked:true})).status,200);
 assert.equal((await f.bob.call('approveContact',{uid})).status,404);
 assert.equal((await f.alice.call('sendMessage',{message:f.envelope()})).status,403);
});
test('approval endpoints retain JWT, live-session, device proof and bounded contact-state inputs',async t=>{
 const f=await fixture(t),uid=f.alice.account.uid;
 await f.alice.call('sendMessage',{message:f.envelope()});
 const request={operation:'approveContact',uid,deviceSecret:f.bob.setup.deviceSecret};
 assert.equal((await f.request(request)).status,401);assert.equal((await f.request(request,'forged')).status,401);
 assert.equal((await f.request({...request,deviceSecret:f.eve.setup.deviceSecret},f.bob.session.access_token)).status,403);
 for(const contactIds of [null,'invalid',[uid,uid],['invalid'],Array.from({length:51},()=>randomUUID())]) {
  if(contactIds===null)continue;
  assert.equal((await f.bob.call('syncInbox',{contactIds})).status,400);
 }
 await f.admin.db.query('DELETE FROM auth.sessions WHERE user_id=$1',[f.bob.account.uid]);
 assert.equal((await f.bob.call('approveContact',{uid})).status,403);
});
test('account deletion cascades both directions of contact approval and keeps tables private',async t=>{
 const f=await fixture(t),a=f.alice.account.uid,b=f.bob.account.uid;
 await f.alice.call('sendMessage',{message:f.envelope()});await f.bob.call('approveContact',{uid:a});
 await f.admin.db.query('INSERT INTO relay.contact_permissions(sender,recipient,sent_count) VALUES($1,$2,2)',[b,a]);
 assert.equal((await f.admin.db.query("SELECT relrowsecurity AS rls FROM pg_class WHERE oid='relay.contact_permissions'::regclass")).rows[0].rls,true);
 assert.equal((await f.admin.db.query("SELECT has_table_privilege('authenticated','relay.contact_permissions','SELECT') AS allowed")).rows[0].allowed,false);
 assert.equal((await f.alice.call('deleteAccount')).status,200);
 assert.equal((await f.admin.db.query('SELECT count(*)::int n FROM relay.contact_permissions WHERE sender=$1 OR recipient=$1',[a])).rows[0].n,0);
 assert.equal((await f.bob.call('getAccount')).status,200);
});

test('migration preserves existing accounts and queues, counts historical sends and exposes no client grants',async t=>{
 const f=await fixture(t,{contactApproval:false}),messages=Array.from({length:5},()=>f.envelope());
 for(const message of messages)assert.equal((await f.alice.call('sendMessage',{message})).status,200);
 for(const message of messages.slice(0,2))await f.bob.call('acknowledgeMessage',{id:message.id});
 await f.admin.db.exec(readFileSync(new URL('../migrations/202610270005_contact_approval.sql',import.meta.url),'utf8'));
 const counts=(await f.admin.db.query('SELECT (SELECT count(*) FROM relay.profiles)::int profiles,(SELECT count(*) FROM relay.messages)::int messages,(SELECT count(*) FROM relay.receipts)::int receipts')).rows[0];
 assert.deepEqual(counts,{profiles:3,messages:3,receipts:2});
 assert.deepEqual((await f.admin.db.query('SELECT sent_count,approved FROM relay.contact_permissions')).rows,[{sent_count:3,approved:false}]);
 assert.equal((await f.alice.call('sendMessage',{message:f.envelope()})).error.code,'contact-approval-required');
 assert.equal((await f.alice.call('sendMessage',{message:messages[0]})).data.state,'delivered');
 for(const role of ['anon','authenticated'])assert.equal((await f.admin.db.query("SELECT has_function_privilege($1,'public.priperi_request(uuid,uuid,text,text,jsonb,boolean)','EXECUTE') AS allowed",[role])).rows[0].allowed,false);
 assert.equal((await f.bob.call('approveContact',{uid:f.alice.account.uid})).status,200);
 assert.equal((await f.alice.call('sendMessage',{message:f.envelope()})).status,200);
});

test('sync refreshes renamed contacts with pinned-key binding but does not become an unrelated or blocked directory',async t=>{
 const f=await fixture(t),a=f.alice.account.uid,b=f.bob.account.uid;
 let state=(await f.eve.call('syncInbox',{contactIds:[a]})).data.contactStates[0];assert.equal(state.username,undefined);assert.equal(state.fingerprint,undefined);
 await f.alice.call('sendMessage',{message:f.envelope()});
 await f.alice.call('renameAccount',{username:'current_alice'});
 state=(await f.bob.call('syncInbox',{contactIds:[a]})).data.contactStates[0];assert.equal(state.username,'current_alice');assert.equal(state.fingerprint,f.alice.account.fingerprint);assert.equal(state.incomingPending,true);
 await f.bob.call('setBlocked',{uid:a,blocked:true});
 state=(await f.bob.call('syncInbox',{contactIds:[a]})).data.contactStates[0];assert.equal(state.username,undefined);
 state=(await f.alice.call('syncInbox',{contactIds:[b]})).data.contactStates[0];assert.equal(state.username,undefined);
 await f.bob.call('setBlocked',{uid:a,blocked:false});state=(await f.bob.call('syncInbox',{contactIds:[a]})).data.contactStates[0];assert.equal(state.username,'current_alice');
});

async function groupFixture(t,{eve=true}={}){
 const f=await fixture(t),id=randomUUID();const created=await f.alice.call('createGroup',{id,name:'Private plans'});assert.equal(created.status,200,JSON.stringify(created));
 for(const user of eve?[f.bob,f.eve]:[f.bob]){const invite=randomUUID();assert.equal((await f.alice.call('inviteGroupMember',{groupId:id,id:invite,uid:user.account.uid})).status,200);assert.equal((await user.call('acceptGroupInvite',{id:invite})).status,200);}
 const group=(await f.alice.call('syncInbox')).data.groupData.groups[0];
 function batch({version=group.version,messageId=randomUUID(),recipients=eve?[f.bob,f.eve]:[f.bob],sender=f.alice}={}){const at=Date.now();return recipients.map(user=>{const m={id:messageId,sender:sender.account.uid,recipient:user.account.uid,sentAt:at,senderFingerprint:sender.account.fingerprint,recipientFingerprint:user.account.fingerprint,ciphertext:randomBytes(120).toString('base64'),group:id,groupVersion:version};m.signature=sign('sha256',Buffer.from(p.context(m)+'\n'+m.ciphertext),sender.keys.privateKey).toString('base64');return m;});}
 return {...f,id,group,batch};
}
test('group invitations need recipient acceptance; outsiders and ordinary members cannot grant membership or admin rights',async t=>{
 const f=await fixture(t),id=randomUUID(),invite=randomUUID();assert.equal((await f.alice.call('createGroup',{id,name:'Study circle'})).status,200);await f.alice.call('inviteGroupMember',{groupId:id,id:invite,uid:f.bob.account.uid});
 let data=(await f.bob.call('syncInbox')).data.groupData;assert.equal(data.groups.length,0);assert.equal(data.invites[0].id,invite);assert.equal(data.invites[0].members,undefined);
 assert.equal((await f.eve.call('acceptGroupInvite',{id:invite})).status,404);assert.equal((await f.bob.call('acceptGroupInvite',{id:invite})).status,200);data=(await f.bob.call('syncInbox')).data.groupData;assert.equal(data.invites.length,0);assert.equal(data.groups[0].members.length,2);
 for(const operation of ['renameGroup','inviteGroupMember','removeGroupMember','setGroupAdmin'])assert.equal((await f.bob.call(operation,{groupId:id,name:'Takeover',id:randomUUID(),uid:f.alice.account.uid,admin:true})).status,403,operation);
 assert.equal((await f.eve.call('leaveGroup',{groupId:id})).status,403);const decline=randomUUID();await f.alice.call('inviteGroupMember',{groupId:id,id:decline,uid:f.eve.account.uid});assert.equal((await f.eve.call('declineGroupInvite',{id:decline})).status,200);assert.equal((await f.eve.call('syncInbox')).data.groupData.groups.length,0);
});
test('group fanout authenticates each exact recipient, group and epoch; acknowledgments delete only that recipient copy and retry does not resurrect it',async t=>{
 const f=await groupFixture(t),messages=f.batch();
 assert.equal((await f.eve.call('sendGroupMessage',{groupId:f.id,messages})).status,403);assert.equal((await f.alice.call('sendMessage',{message:messages[0]})).status,400);
 assert.equal((await f.alice.call('sendGroupMessage',{groupId:f.id,messages:[messages[0]]})).status,403);assert.equal((await f.alice.call('sendGroupMessage',{groupId:f.id,messages:[messages[0],messages[0]]})).status,400);
 assert.equal((await f.alice.call('sendGroupMessage',{groupId:f.id,messages:[{...messages[0],group:randomUUID()},messages[1]]})).status,403);assert.equal((await f.alice.call('sendGroupMessage',{groupId:f.id,messages:[{...messages[0],ciphertext:randomBytes(120).toString('base64')},messages[1]]})).status,403);
 const result=await f.alice.call('sendGroupMessage',{groupId:f.id,messages});assert.equal(result.status,200,JSON.stringify(result));assert.equal(result.data.notifyTokens,undefined);
 assert.deepEqual((await f.bob.call('syncInbox')).data.groupData.messages,[messages[0]]);assert.deepEqual((await f.eve.call('syncInbox')).data.groupData.messages,[messages[1]]);
 const id=messages[0].id;await f.eve.call('ackGroupMessages',{ids:[id]});assert.equal((await f.admin.db.query('SELECT count(*)::int AS n FROM relay.group_queue WHERE recipient=$1',[f.bob.account.uid])).rows[0].n,1);await f.bob.call('ackGroupMessages',{ids:[id]});
 assert.equal((await f.alice.call('sendGroupMessage',{groupId:f.id,messages:messages.toReversed()})).status,200);assert.equal((await f.admin.db.query('SELECT count(*)::int AS n FROM relay.group_queue')).rows[0].n,0);assert.equal((await f.alice.call('sendGroupMessage',{groupId:f.id,messages:f.batch({messageId:id})})).status,409);
});
test('group membership changes reject stale outboxes, preserve exact committed retries and purge removed members; new members get no older copies',async t=>{
 const f=await groupFixture(t,{eve:false}),first=f.batch(),stale=f.batch();await f.alice.call('sendGroupMessage',{groupId:f.id,messages:first});const invite=randomUUID();await f.alice.call('inviteGroupMember',{groupId:f.id,id:invite,uid:f.eve.account.uid});await f.eve.call('acceptGroupInvite',{id:invite});
 assert.equal((await f.eve.call('syncInbox')).data.groupData.messages.length,0);assert.equal((await f.alice.call('sendGroupMessage',{groupId:f.id,messages:stale})).error.code,'group-changed');assert.equal((await f.alice.call('sendGroupMessage',{groupId:f.id,messages:first})).status,200);
 await f.alice.call('removeGroupMember',{groupId:f.id,uid:f.bob.account.uid});assert.equal((await f.bob.call('syncInbox')).data.groupData.groups.length,0);assert.equal((await f.bob.call('syncInbox')).data.groupData.messages.length,0);assert.equal((await f.bob.call('sendGroupMessage',{groupId:f.id,messages:f.batch({sender:f.bob,recipients:[f.alice],version:f.group.version+2})})).status,403);
});
test('group admins can rename/promote/remove and departures cannot orphan admin rights',async t=>{
 const f=await groupFixture(t);assert.equal((await f.alice.call('renameGroup',{groupId:f.id,name:'New name'})).status,200);assert.equal((await f.alice.call('setGroupAdmin',{groupId:f.id,uid:f.alice.account.uid,admin:false})).status,409);assert.equal((await f.alice.call('setGroupAdmin',{groupId:f.id,uid:f.bob.account.uid,admin:true})).status,200);assert.equal((await f.bob.call('setGroupAdmin',{groupId:f.id,uid:f.alice.account.uid,admin:false})).status,200);assert.equal((await f.alice.call('renameGroup',{groupId:f.id,name:'Denied'})).status,403);await f.bob.call('leaveGroup',{groupId:f.id});
 const state=(await f.eve.call('syncInbox')).data.groupData.groups[0];assert.equal(state.name,'New name');assert.equal(state.members.filter(m=>m.admin).length,1);await f.eve.call('leaveGroup',{groupId:f.id});await f.alice.call('leaveGroup',{groupId:f.id});assert.equal((await f.admin.db.query('SELECT count(*)::int AS n FROM relay.groups')).rows[0].n,0);
});
test('group blocks discard pair copies while other recipients still receive; account deletion erases references and promotes an admin',async t=>{
 const f=await groupFixture(t);await f.alice.call('sendGroupMessage',{groupId:f.id,messages:f.batch()});await f.bob.call('setBlocked',{uid:f.alice.account.uid,blocked:true});assert.equal((await f.bob.call('syncInbox')).data.groupData.messages.length,0);await f.alice.call('sendGroupMessage',{groupId:f.id,messages:f.batch()});assert.equal((await f.bob.call('syncInbox')).data.groupData.messages.length,0);assert.equal((await f.eve.call('syncInbox')).data.groupData.messages.length,2);
 await f.alice.call('deleteAccount');for(const [table,column] of [['group_members','uid'],['group_invites','inviter'],['group_invites','target'],['group_dispatch','sender'],['group_queue','sender'],['group_queue','recipient']])assert.equal((await f.admin.db.query(`SELECT count(*)::int AS n FROM relay.${table} WHERE ${column}=$1`,[f.alice.account.uid])).rows[0].n,0);assert.equal((await f.bob.call('syncInbox')).data.groupData.groups[0].members.filter(m=>m.admin).length,1);await f.bob.call('deleteAccount');await f.eve.call('deleteAccount');for(const table of ['groups','group_members','group_invites','group_dispatch','group_queue'])assert.equal((await f.admin.db.query(`SELECT count(*)::int AS n FROM relay.${table}`)).rows[0].n,0);
});
test('group tables and RPC stay private; wrong device and revoked sessions cannot use group controls',async t=>{
 const f=await groupFixture(t);for(const role of ['anon','authenticated']){await f.admin.db.exec('SET ROLE '+role);try{for(const table of ['groups','group_members','group_invites','group_dispatch','group_queue'])await assert.rejects(f.admin.db.query(`SELECT * FROM relay.${table}`),/permission denied/);await assert.rejects(f.admin.db.query('SELECT public.priperi_groups($1,$2,$3,$4,$5)',[f.alice.account.uid,f.sessions.get(f.alice.session.access_token).session_id,p.secretHash(f.alice.setup.deviceSecret),'syncGroups','{}']),/permission denied/);}finally{await f.admin.db.exec('RESET ROLE');}}
 assert.equal((await f.alice.call('leaveGroup',{groupId:f.id,deviceSecret:f.eve.setup.deviceSecret})).status,403);await f.admin.db.query('DELETE FROM auth.sessions WHERE user_id=$1',[f.alice.account.uid]);assert.equal((await f.alice.call('renameGroup',{groupId:f.id,name:'Revoked'})).status,403);
});
test('group name/capacity/expiry limits hold and sweep removes encrypted copies and retry markers',async t=>{
 const f=await groupFixture(t,{eve:false});for(const name of ['', 'x'.repeat(61),'line\nbreak',' padded','bad\u0085name'])assert.equal((await f.alice.call('renameGroup',{groupId:f.id,name})).status,400);
 const invite=randomUUID();await f.alice.call('inviteGroupMember',{groupId:f.id,id:invite,uid:f.eve.account.uid});await f.alice.call('sendGroupMessage',{groupId:f.id,messages:f.batch()});await f.admin.db.exec("UPDATE relay.group_invites SET expires_at=now()-interval '1 second'; UPDATE relay.group_dispatch SET expires_at=now()-interval '1 second'; SELECT relay.sweep()");assert.equal((await f.eve.call('acceptGroupInvite',{id:invite})).status,404);for(const table of ['group_invites','group_dispatch','group_queue'])assert.equal((await f.admin.db.query(`SELECT count(*)::int AS n FROM relay.${table}`)).rows[0].n,0);
 await f.admin.db.exec("DELETE FROM relay.rates WHERE kind='settings'");for(let i=0;i<19;i++)assert.equal((await f.alice.call('createGroup',{id:randomUUID(),name:'Group '+i})).status,200);await f.admin.db.exec("DELETE FROM relay.rates WHERE kind='settings'");assert.equal((await f.alice.call('createGroup',{id:randomUUID(),name:'Too many'})).status,429);
});
test('group concurrent joins cannot exceed twenty members or bypass pending invitation limits',async t=>{
 const f=await fixture(t),id=randomUUID(),bobInvite=randomUUID(),eveInvite=randomUUID();await f.alice.call('createGroup',{id,name:'Capacity test'});await f.alice.call('inviteGroupMember',{groupId:id,id:bobInvite,uid:f.bob.account.uid});await f.alice.call('inviteGroupMember',{groupId:id,id:eveInvite,uid:f.eve.account.uid});
 // A near-capacity snapshot exercises the final transactional check even if
 // pending reservations from an earlier state exceed the remaining place.
 for(let i=0;i<18;i++){const uid=randomUUID();await f.admin.db.query('INSERT INTO auth.users(id) VALUES($1)',[uid]);await f.admin.db.query('INSERT INTO relay.profiles(uid,username,hpke,signing,fingerprint,device_hash,installation) VALUES($1,$2,$3,$4,$5,$6,$7)',[uid,'filler_'+i,f.alice.account.hpke,f.alice.account.signing,f.alice.account.fingerprint,p.hash(uid),randomUUID()]);await f.admin.db.query('INSERT INTO relay.group_members(group_id,uid) VALUES($1,$2)',[id,uid]);}
 const results=await Promise.all([f.bob.call('acceptGroupInvite',{id:bobInvite}),f.eve.call('acceptGroupInvite',{id:eveInvite})]);assert.deepEqual(results.map(r=>r.status).sort(),[200,429]);assert.equal((await f.admin.db.query('SELECT count(*)::int AS n FROM relay.group_members WHERE group_id=$1',[id])).rows[0].n,20);
 assert.equal((await f.alice.call('inviteGroupMember',{groupId:id,id:randomUUID(),uid:f.bob.account.uid})).status>=400,true);
});
test('group roster cache omits unchanged snapshots but refreshes a renamed member without changing keys',async t=>{
 const f=await groupFixture(t),tag=f.group.tag;assert.match(tag,/^[a-f0-9]{32}$/);let data=(await f.alice.call('syncInbox',{groupTags:{[f.id]:tag}})).data.groupData;assert.deepEqual(data.activeIds,[f.id]);assert.equal(data.groups.length,0);
 await f.bob.call('renameAccount',{username:'renamed_group_member'});data=(await f.alice.call('syncInbox',{groupTags:{[f.id]:tag}})).data.groupData;assert.equal(data.groups.length,1);const bob=data.groups[0].members.find(m=>m.uid===f.bob.account.uid);assert.equal(bob.username,'renamed_group_member');assert.equal(bob.fingerprint,f.bob.account.fingerprint);assert.notEqual(data.groups[0].tag,tag);
 assert.equal((await f.alice.call('syncInbox',{groupTags:{[f.id]:'bad'}})).status,400);
});
test('group storage caps include expired rows until sweep; backdated message timestamps cannot bypass them',async t=>{
 const f=await groupFixture(t),messages=f.batch();await f.admin.db.query("INSERT INTO relay.group_dispatch(id,group_id,sender,digest,expires_at) SELECT gen_random_uuid(),$1,$2,repeat('a',64),now()-interval '1 second' FROM generate_series(1,2000)",[f.id,f.alice.account.uid]);
 assert.equal((await f.alice.call('sendGroupMessage',{groupId:f.id,messages})).status,429);assert.equal((await f.admin.db.query('SELECT count(*)::int AS n FROM relay.group_dispatch')).rows[0].n,2000);
 await f.admin.db.exec('SELECT relay.sweep()');assert.equal((await f.alice.call('sendGroupMessage',{groupId:f.id,messages})).status,200);
});

test('every protected relay action rejects absent/forged JWT, wrong installation proof and revoked sessions',async t=>{
 const f=await fixture(t);
 const operations=['getAccountPolicy','setSelfDestruct','accountPresence','transferChallenge','prepareTransfer','cancelTransfer','finishTransfer','getAccount','lookupUser','searchUsers','renameAccount','setNotificationToken','setBlocked','approveContact','sendMessage','syncInbox','acknowledgeMessage','acknowledgeMessages','deleteAccount','changePassword','createGroup','renameGroup','inviteGroupMember','acceptGroupInvite','declineGroupInvite','removeGroupMember','setGroupAdmin','leaveGroup','sendGroupMessage','ackGroupMessages'];
 for(const operation of operations){
  const data={operation,deviceSecret:f.alice.setup.deviceSecret,ids:[randomUUID()]};
  assert.equal((await f.request(data)).status,401,operation+' without JWT');
  assert.equal((await f.request(data,'forged')).status,401,operation+' forged JWT');
  assert.equal((await f.request({...data,deviceSecret:f.eve.setup.deviceSecret},f.alice.session.access_token)).status,403,operation+' wrong device');
 }
 await f.admin.db.query('DELETE FROM auth.sessions WHERE user_id=$1',[f.alice.account.uid]);
 for(const operation of operations)assert.equal((await f.alice.call(operation,{ids:[randomUUID()]})).status,403,operation+' revoked session');
 assert.equal((await f.admin.db.query('SELECT count(*)::int n FROM relay.profiles')).rows[0].n,3);
});

test('actor fields, malformed and prototype operation names cannot become an identity or arbitrary RPC',async t=>{
 const f=await fixture(t),actor=f.eve.account.uid;
 const account=await f.alice.call('getAccount',{actor,uid:actor,session_id:randomUUID(),proof:p.secretHash(f.eve.setup.deviceSecret),recent_password:true});
 assert.equal(account.data.uid,f.alice.account.uid);
 for(const operation of ['__proto__','constructor','toString','priperi_request','syncGroups',null,{},['getAccount']])assert.equal((await f.alice.call(operation)).status,404);
 const before=(await f.admin.db.query('SELECT count(*)::int n FROM relay.pending_accounts')).rows[0].n;
 for(const operation of ['continue','deviceStatus','resetChallenge','resetAccount','readTransfer','acceptTransfer','transferSourceStatus','acknowledgeTransferSource','transferRollbackChallenge','rollbackTransfer'])assert.equal((await f.request({operation,deviceSecret:f.alice.setup.deviceSecret})).status,400,operation+' malformed public body');
 assert.equal((await f.admin.db.query('SELECT count(*)::int n FROM relay.pending_accounts')).rows[0].n,before);
 for(const [field,value] of [['iss','https://attacker.invalid/auth/v1'],['aud','service_role'],['exp',1],['app_metadata',{priperi_auth_version:3}]]){
  const claims=f.sessions.get(f.alice.session.access_token),old=claims[field];claims[field]=value;
  assert.equal((await f.alice.call('getAccount')).status,401,field);claims[field]=old;
 }
});

async function transferOffer(f){
 const {delegationContext}=await import('../functions/relay/delegation.js');
 const a=f.alice,temp=generateKeyPairSync('ec',{namedCurve:'prime256v1'}),temporary=temp.publicKey.export({type:'spki',format:'der'}).toString('base64');
 const chain=[{key:temporary,signature:sign('sha256',Buffer.from(delegationContext(a.account.uid,a.account.fingerprint,a.setup.signing,temporary)),a.keys.privateKey).toString('base64')}];
 const lookup=p.hash(randomBytes(32)),claimSecret=randomBytes(32).toString('base64url'),claimHash=p.hash(claimSecret),capsule=randomBytes(160).toString('base64');
 const nonce=(await a.call('transferChallenge',{lookup})).data.nonce;
 const signature=sign('sha256',Buffer.from(['priperi-transfer-prepare-v1',a.account.uid,lookup,nonce,claimHash,p.hash(capsule),temporary].join('\n')),a.keys.privateKey).toString('base64');
 const prepared=await a.call('prepareTransfer',{lookup,nonce,claimHash,capsule,chain,signature});assert.equal(prepared.status,200,JSON.stringify(prepared));
 return {a,temp,temporary,chain,lookup,claimSecret,claimHash,capsule,prepare:{lookup,nonce,claimHash,capsule,chain,signature}};
}
async function transferTarget(offer){
 const {delegationContext,acceptContext}=await import('../functions/relay/delegation.js');
 const keys=generateKeyPairSync('ec',{namedCurve:'prime256v1'}),signing=keys.publicKey.export({type:'spki',format:'der'}).toString('base64'),deviceSecret=randomBytes(32).toString('base64url'),target={signing,installation:randomUUID(),deviceId:randomUUID(),chain:[...offer.chain,{key:signing,signature:sign('sha256',Buffer.from(delegationContext(offer.a.account.uid,offer.a.account.fingerprint,offer.temporary,signing)),offer.temp.privateKey).toString('base64')}]};
 const context=acceptContext(offer.lookup,offer.a.account.uid,offer.claimHash,p.secretHash(deviceSecret),target.installation,target.deviceId,signing,target.chain);
 return {keys,deviceSecret,target,operation:'acceptTransfer',lookup:offer.lookup,claimSecret:offer.claimSecret,signature:sign('sha256',Buffer.from(context),keys.privateKey).toString('base64')};
}
async function finishMoved(f,offer,receiver,token){const {chainDigest}=await import('../functions/relay/delegation.js');const context=['priperi-transfer-finish-v1',offer.lookup,offer.a.account.uid,p.secretHash(receiver.deviceSecret),chainDigest(receiver.target.chain)].join('\n');return f.request({operation:'finishTransfer',lookup:offer.lookup,deviceSecret:receiver.deviceSecret,signature:sign('sha256',Buffer.from(context),receiver.keys.privateKey).toString('base64')},token);}
async function movedLogin(f,receiver){return f.request({...f.alice.setup,installation:receiver.target.installation,deviceId:receiver.target.deviceId,deviceSecret:receiver.deviceSecret,delegations:receiver.target.chain});}
test('one-use transfer preserves identity/username/queues, atomically revokes old device, and retry is bound to the same receiver',async t=>{
 const f=await fixture(t);await f.alice.call('sendMessage',{message:f.envelope()});const offer=await transferOffer(f),receiver=await transferTarget(offer);
 const read=await f.request({operation:'readTransfer',lookup:offer.lookup,deviceSecret:receiver.deviceSecret});assert.equal(read.status,200);assert.equal(read.data.capsule,offer.capsule);assert.ok(!JSON.stringify(read.data).includes('auth_seed'));
 const moved=await f.request(receiver);assert.equal(moved.status,200,JSON.stringify(moved));assert.equal(moved.data.account.uid,f.alice.account.uid);assert.equal(moved.data.account.username,'alice');assert.equal(moved.data.account.fingerprint,f.alice.account.fingerprint);assert.equal(moved.data.account.delegations.length,2);
 assert.equal((await f.alice.call('getAccount')).status,403);assert.equal((await f.request(f.alice.setup)).status,401);
 assert.equal((await f.request({operation:'transferSourceStatus',lookup:offer.lookup,deviceSecret:f.alice.setup.deviceSecret})).data.complete,false);
 const status=await f.request({operation:'transferSourceStatus',lookup:offer.lookup,deviceSecret:f.alice.setup.deviceSecret});assert.equal(status.data.complete,false);
 assert.equal((await f.request(receiver)).status,200);assert.equal((await f.request(await transferTarget(offer))).status,403);
 const newSetup={...f.alice.setup,installation:receiver.target.installation,deviceId:receiver.target.deviceId,deviceSecret:receiver.deviceSecret,delegations:receiver.target.chain};const login=await f.request(newSetup);assert.equal(login.status,200,JSON.stringify(login));
 assert.equal((await finishMoved(f,offer,receiver,login.data.session.access_token)).status,200);assert.equal((await f.request({operation:'transferSourceStatus',lookup:offer.lookup,deviceSecret:f.alice.setup.deviceSecret})).data.complete,true);
 assert.equal((await f.admin.db.query('SELECT count(*)::int n FROM relay.messages')).rows[0].n,1);
 assert.equal((await f.request({operation:'readTransfer',lookup:offer.lookup,deviceSecret:receiver.deviceSecret})).status,404);
 const record=f.records.get(f.alice.account.uid+'@accounts.priperi.invalid');assert.notEqual(record.password,managedPassword(f.alice.setup.deviceSecret,f.alice.setup.password));assert.notEqual(record.password,managedPassword(receiver.deviceSecret,f.alice.setup.password));
 const temporaryMessage=f.envelope();temporaryMessage.signature=sign('sha256',Buffer.from(p.context(temporaryMessage)+'\n'+temporaryMessage.ciphertext),offer.temp.privateKey).toString('base64');assert.equal((await f.request({operation:'sendMessage',deviceSecret:receiver.deviceSecret,message:temporaryMessage},login.data.session.access_token)).status,403);
 const m=f.envelope();m.signature=sign('sha256',Buffer.from(p.context(m)+'\n'+m.ciphertext),receiver.keys.privateKey).toString('base64');assert.equal((await f.request({operation:'sendMessage',deviceSecret:receiver.deviceSecret,message:m},login.data.session.access_token)).status,200);
});
test('transfer rejects missing/fake auth, stale password, bad signature, wrong claim, expired ticket, other-account and same-device targets',async t=>{
 const f=await fixture(t);for(const operation of ['transferChallenge','prepareTransfer','cancelTransfer','finishTransfer','setSelfDestruct','getAccountPolicy','accountPresence']){assert.equal((await f.request({operation,deviceSecret:f.alice.setup.deviceSecret})).status,401);assert.equal((await f.alice.call(operation,{deviceSecret:f.bob.setup.deviceSecret})).status,403);}
 const offer=await transferOffer(f),receiver=await transferTarget(offer);
 assert.equal((await f.request({...receiver,claimSecret:randomBytes(32).toString('base64url')})).status,403);
 assert.equal((await f.request({...receiver,signature:sign('sha256',Buffer.from('wrong'),f.eve.keys.privateKey).toString('base64')})).status,403);
 const bad=await transferTarget(offer);bad.target.chain[1].signature=receiver.target.chain[1].signature;assert.equal((await f.request(bad)).status,403);
 await f.admin.db.exec("UPDATE relay.transfers SET expires_at=now()-interval '1 second'");assert.equal((await f.request(receiver)).status,404);assert.equal((await f.alice.call('getAccount')).status,200);
 await f.admin.db.exec('SELECT relay.sweep()');assert.equal((await f.admin.db.query('SELECT count(*)::int n FROM relay.transfers')).rows[0].n,0);
 const claims=f.sessions.get(f.alice.session.access_token);claims.amr[0].timestamp=1;assert.equal((await f.alice.call('prepareTransfer',{lookup:p.hash(randomBytes(32))})).status,401);
});
test('concurrent receivers cannot move an account twice or overwrite another registered device',async t=>{
 const f=await fixture(t),offer=await transferOffer(f),first=await transferTarget(offer),second=await transferTarget(offer);
 const {acceptContext}=await import('../functions/relay/delegation.js');const colliding=await transferTarget(offer);colliding.target.deviceId=f.bob.setup.deviceId;colliding.signature=sign('sha256',Buffer.from(acceptContext(offer.lookup,f.alice.account.uid,offer.claimHash,p.secretHash(colliding.deviceSecret),colliding.target.installation,colliding.target.deviceId,colliding.target.signing,colliding.target.chain)),colliding.keys.privateKey).toString('base64');assert.equal((await f.request(colliding)).status,403);
 const results=await Promise.all([f.request(first),f.request(second)]);assert.deepEqual(results.map(x=>x.status).sort(),[200,403]);assert.equal((await f.bob.call('getAccount')).status,200);
});
test('inactivity is opt-in, requires recent password, uses server time and background inbox calls do not extend it',async t=>{
 const f=await fixture(t);assert.equal((await f.alice.call('getAccountPolicy')).data.days,0);
 for(const days of [-1,2,366,1.5,'7',null])assert.equal((await f.alice.call('setSelfDestruct',{days})).status,400);
 const claims=f.sessions.get(f.alice.session.access_token);claims.amr[0].timestamp=1;assert.equal((await f.alice.call('setSelfDestruct',{days:1})).status,401);claims.amr[0].timestamp=Date.now()/1000;
 assert.equal((await f.alice.call('setSelfDestruct',{days:1})).data.days,1);await f.admin.db.query("UPDATE relay.profiles SET active_at=now()-interval '12 hours' WHERE uid=$1",[f.alice.account.uid]);
 const before=(await f.admin.db.query('SELECT active_at FROM relay.profiles WHERE uid=$1',[f.alice.account.uid])).rows[0].active_at;assert.equal((await f.alice.call('syncInbox')).status,200);assert.equal(String((await f.admin.db.query('SELECT active_at FROM relay.profiles WHERE uid=$1',[f.alice.account.uid])).rows[0].active_at),String(before));
 assert.equal((await f.alice.call('accountPresence')).status,200);assert.ok(new Date((await f.admin.db.query('SELECT active_at FROM relay.profiles WHERE uid=$1',[f.alice.account.uid])).rows[0].active_at)>new Date(before));
 await f.admin.db.query("UPDATE relay.profiles SET active_at=now()-interval '1 day 1 second' WHERE uid=$1",[f.alice.account.uid]);const gone=await f.alice.call('accountPresence');assert.equal(gone.status,401);assert.equal(gone.error.code,'account-expired');assert.equal((await f.admin.db.query('SELECT count(*)::int n FROM auth.users WHERE id=$1',[f.alice.account.uid])).rows[0].n,0);assert.equal((await f.bob.call('getAccount')).status,200);
});
test('scheduled inactivity deletion cascades transfer/group/contact data and Never does not expire',async t=>{
 const f=await fixture(t),offer=await transferOffer(f);await f.alice.call('sendMessage',{message:f.envelope()});await f.alice.call('setSelfDestruct',{days:7});await f.bob.call('setSelfDestruct',{days:365});
 await f.admin.db.exec("UPDATE relay.profiles SET active_at=now()-interval '8 days' WHERE username='alice';UPDATE relay.profiles SET active_at=now()-interval '366 days' WHERE username IN('bob','eve');SELECT relay.sweep()");
 assert.equal((await f.admin.db.query('SELECT count(*)::int n FROM relay.profiles')).rows[0].n,1);assert.equal((await f.admin.db.query('SELECT count(*)::int n FROM relay.transfers')).rows[0].n,0);assert.equal((await f.admin.db.query('SELECT count(*)::int n FROM relay.messages')).rows[0].n,0);assert.equal((await f.eve.call('getAccount')).status,200);
 for(const role of ['anon','authenticated']){await f.admin.db.exec('SET ROLE '+role);try{await assert.rejects(f.admin.db.query('SELECT * FROM relay.transfers'),/permission denied/);await assert.rejects(f.admin.db.query("SELECT public.priperi_auth_seed($1,$2,$3)",[f.eve.account.uid,p.secretHash(f.eve.setup.deviceSecret),randomBytes(32).toString('base64url')]),/permission denied/);}finally{await f.admin.db.exec('RESET ROLE');}}
});


test('cancelled, expired or consumed prepare signatures cannot restart a move; exact live retry does not extend its deadline',async t=>{
 const f=await fixture(t),offer=await transferOffer(f);const expiry=(await f.admin.db.query('SELECT expires_at FROM relay.transfers WHERE uid=$1',[f.alice.account.uid])).rows[0].expires_at;
 assert.equal((await f.alice.call('prepareTransfer',offer.prepare)).status,200);assert.equal(String((await f.admin.db.query('SELECT expires_at FROM relay.transfers WHERE uid=$1',[f.alice.account.uid])).rows[0].expires_at),String(expiry));
 assert.equal((await f.alice.call('cancelTransfer',{lookup:offer.lookup})).status,200);assert.equal((await f.alice.call('prepareTransfer',offer.prepare)).status,403);
 const next=await f.alice.call('transferChallenge',{lookup:offer.lookup});assert.ok(next.data.nonce);assert.equal((await f.alice.call('prepareTransfer',offer.prepare)).status,403);
});


test('unfinished move can return to the original phone only with its signing key, original proof, current password and fresh nonce',async t=>{
 const f=await fixture(t),offer=await transferOffer(f),receiver=await transferTarget(offer);assert.equal((await f.request(receiver)).status,200);const login=await movedLogin(f,receiver);assert.equal(login.status,200);
 const challenge=await f.request({operation:'transferRollbackChallenge',lookup:offer.lookup,deviceSecret:f.alice.setup.deviceSecret});assert.equal(challenge.status,200);
 const packet={operation:'rollbackTransfer',lookup:offer.lookup,deviceSecret:f.alice.setup.deviceSecret,password:f.alice.setup.password,nonce:challenge.data.nonce};packet.signature=sign('sha256',Buffer.from(['priperi-transfer-rollback-v1',offer.lookup,f.alice.account.uid,packet.nonce,p.secretHash(packet.deviceSecret)].join('\n')),f.alice.keys.privateKey).toString('base64');
 assert.equal((await f.request({...packet,password:'Wrong-Password-2026!'})).status,401);assert.equal((await f.request({...packet,signature:sign('sha256',Buffer.from('forged'),f.eve.keys.privateKey).toString('base64')})).status,403);
 const returned=await f.request(packet);assert.equal(returned.status,200,JSON.stringify(returned));assert.equal(returned.data.rolledBack,true);assert.ok(!JSON.stringify(returned).includes('authSeed'));assert.equal((await f.request(f.alice.setup)).status,200);assert.equal((await movedLogin(f,receiver)).status,401);assert.equal((await f.request(packet)).status,404);
});

test('receiver confirmation requires its current signing key, closes rollback, and source cleanup removes the bounded receipt',async t=>{
 const f=await fixture(t),offer=await transferOffer(f),receiver=await transferTarget(offer);await f.request(receiver);const login=await movedLogin(f,receiver);
 assert.equal((await f.request({operation:'finishTransfer',lookup:offer.lookup,deviceSecret:receiver.deviceSecret,signature:sign('sha256',Buffer.from('forged'),f.alice.keys.privateKey).toString('base64')},login.data.session.access_token)).status,403);
 assert.equal((await finishMoved(f,offer,receiver,login.data.session.access_token)).status,200);assert.equal((await f.request({operation:'transferRollbackChallenge',lookup:offer.lookup,deviceSecret:f.alice.setup.deviceSecret})).status,403);
 assert.equal((await f.request({operation:'acknowledgeTransferSource',lookup:offer.lookup,deviceSecret:f.alice.setup.deviceSecret})).status,200);assert.equal((await f.admin.db.query('SELECT count(*)::int n FROM relay.transfers')).rows[0].n,0);assert.equal((await f.admin.db.query('SELECT count(*)::int n FROM relay.rates WHERE subject=$1',[offer.lookup])).rows[0].n,0);assert.equal((await f.request({...f.alice.setup,deviceSecret:receiver.deviceSecret,installation:receiver.target.installation,deviceId:receiver.target.deviceId})).status,200);
});

test('receiver confirmation and source rollback cannot both win',async t=>{
 const f=await fixture(t),offer=await transferOffer(f),receiver=await transferTarget(offer);await f.request(receiver);const login=await movedLogin(f,receiver),challenge=await f.request({operation:'transferRollbackChallenge',lookup:offer.lookup,deviceSecret:f.alice.setup.deviceSecret});
 const packet={operation:'rollbackTransfer',lookup:offer.lookup,deviceSecret:f.alice.setup.deviceSecret,password:f.alice.setup.password,nonce:challenge.data.nonce};packet.signature=sign('sha256',Buffer.from(['priperi-transfer-rollback-v1',offer.lookup,f.alice.account.uid,packet.nonce,p.secretHash(packet.deviceSecret)].join('\n')),f.alice.keys.privateKey).toString('base64');
 const results=await Promise.all([finishMoved(f,offer,receiver,login.data.session.access_token),f.request(packet)]);assert.deepEqual(results.map(x=>x.status).sort(),[200,403]);
});


test('an overdue account cannot renew its timer through unfinished-transfer rollback',async t=>{
 const f=await fixture(t);await f.alice.call('setSelfDestruct',{days:1});const offer=await transferOffer(f),receiver=await transferTarget(offer);assert.equal((await f.request(receiver)).status,200);
 await f.admin.db.query("UPDATE relay.profiles SET active_at=now()-interval '25 hours' WHERE uid=$1",[f.alice.account.uid]);const result=await f.request({operation:'transferRollbackChallenge',lookup:offer.lookup,deviceSecret:f.alice.setup.deviceSecret});assert.equal(result.status,401);assert.equal(result.error.code,'account-expired');assert.equal((await f.admin.db.query('SELECT count(*)::int n FROM relay.profiles WHERE uid=$1',[f.alice.account.uid])).rows[0].n,0);
});

test('receiver finish can be retried after source acknowledgement removed its receipt',async t=>{
 const f=await fixture(t),offer=await transferOffer(f),receiver=await transferTarget(offer);await f.request(receiver);const login=await movedLogin(f,receiver);assert.equal((await finishMoved(f,offer,receiver,login.data.session.access_token)).status,200);assert.equal((await f.request({operation:'acknowledgeTransferSource',lookup:offer.lookup,deviceSecret:f.alice.setup.deviceSecret})).status,200);assert.equal((await finishMoved(f,offer,receiver,login.data.session.access_token)).status,200);
});


test('all account-control tables/helpers/RPCs remain private with fixed SQL search paths',async t=>{
 const f=await fixture(t);const tables=(await f.admin.db.query("SELECT relname,relrowsecurity FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='relay' AND c.relkind='r'")).rows;assert.equal(tables.length,15);assert.ok(tables.every(x=>x.relrowsecurity));
 const functions=(await f.admin.db.query("SELECT p.oid,p.proname,p.prosecdef,p.proconfig FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE (n.nspname='public' AND p.proname LIKE 'priperi_%') OR (n.nspname='relay' AND p.proname IN('expire_account','sweep','sweep_v271'))")).rows;assert.equal(functions.filter(x=>x.proname.startsWith('priperi_')).length,13);
 for(const role of ['anon','authenticated']){for(const fn of functions){assert.equal((await f.admin.db.query("SELECT has_function_privilege($1,$2::oid,'EXECUTE') AS allowed",[role,fn.oid])).rows[0].allowed,false);assert.equal(fn.prosecdef,true);assert.ok(fn.proconfig.some(x=>x==='search_path=""'));}assert.equal((await f.admin.db.query("SELECT has_table_privilege($1,'relay.transfers','SELECT,INSERT,UPDATE,DELETE') AS allowed",[role])).rows[0].allowed,false);}
 const profile=(await f.alice.call('getAccount')).data;for(const key of ['auth_seed','authSeed','device_hash','installation','idle_days','active_at','move_nonce'])assert.ok(!Object.hasOwn(profile,key));
});


test('failed public transfer capabilities consume an independent shared quota even when the later RPC rolls back',async t=>{
 const f=await fixture(t),offer=await transferOffer(f),receiver=await transferTarget(offer);assert.equal((await f.request({...receiver,claimSecret:randomBytes(32).toString('base64url')})).status,403);assert.equal((await f.admin.db.query("SELECT count FROM relay.rates WHERE subject='system:global' AND kind='transfer_public'")).rows[0].count,1);
 await f.admin.db.exec("UPDATE relay.rates SET count=180 WHERE subject='system:global' AND kind='transfer_public'");for(const operation of ['readTransfer','acceptTransfer','transferSourceStatus','acknowledgeTransferSource','transferRollbackChallenge','rollbackTransfer'])assert.equal((await f.request({operation,lookup:offer.lookup,deviceSecret:receiver.deviceSecret})).status,429,operation);assert.equal((await f.alice.call('getAccount')).status,200);
});

test('rollback clears abandoned receiver counters while preserving unrelated device limits',async t=>{
 const f=await fixture(t),offer=await transferOffer(f),receiver=await transferTarget(offer);await f.request(receiver);assert.equal((await movedLogin(f,receiver)).status,200);const receiverHash=p.secretHash(receiver.deviceSecret),count=async hash=>(await f.admin.db.query('SELECT count(*)::int n FROM relay.rates WHERE subject=$1',[hash])).rows[0].n;assert.ok(await count(receiverHash)>0);const bobHash=p.secretHash(f.bob.setup.deviceSecret),bobBefore=await count(bobHash);
 const nonce=(await f.request({operation:'transferRollbackChallenge',lookup:offer.lookup,deviceSecret:f.alice.setup.deviceSecret})).data.nonce,packet={operation:'rollbackTransfer',lookup:offer.lookup,nonce,deviceSecret:f.alice.setup.deviceSecret,password:f.alice.setup.password};packet.signature=sign('sha256',Buffer.from(['priperi-transfer-rollback-v1',offer.lookup,f.alice.account.uid,nonce,p.secretHash(packet.deviceSecret)].join('\n')),f.alice.keys.privateKey).toString('base64');assert.equal((await f.request(packet)).status,200);assert.equal(await count(receiverHash),0);assert.equal(await count(bobHash),bobBefore);assert.equal((await f.request(f.alice.setup)).status,200);
});

function replacementPacket(f,name='fresh_alice'){
 const keys=generateKeyPairSync('ec',{namedCurve:'prime256v1'});
 return {...f.eve.setup,operation:'prepareReplacement',username:name,deviceSecret:randomBytes(32).toString('base64url'),installation:randomUUID(),deviceId:f.alice.setup.deviceId,signing:keys.publicKey.export({type:'spki',format:'der'}).toString('base64'),previousPassword:f.alice.setup.password,confirmLoss:true};
}
const replacementLogin=packet=>({...packet,operation:'continue',password:'Fresh-Password-2026!'});
test('replacement requires old password and explicit consent; ID, JWT or claimed UID alone cannot change an account',async t=>{
 const f=await fixture(t),packet=replacementPacket(f),before=(await f.admin.db.query('SELECT * FROM relay.profiles WHERE uid=$1',[f.alice.account.uid])).rows[0];
 for(const body of [{...packet,previousPassword:'Wrong-Password-2026!'},{...packet,confirmLoss:false},{...packet,deviceId:randomUUID(),uid:f.alice.account.uid},{...packet,previousPassword:undefined,authenticated:true}])assert.notEqual((await f.request(body,f.alice.session.access_token)).status,200);
 assert.deepEqual((await f.admin.db.query('SELECT * FROM relay.profiles WHERE uid=$1',[f.alice.account.uid])).rows[0],before);
 assert.equal((await f.request(replacementLogin(packet))).status,401);
 assert.equal((await f.alice.call('getAccount')).status,200);
});
test('replacement starts seven days only on successful signup, never returns old identity/history, and sweep preserves the new account',async t=>{
 const f=await fixture(t),packet=replacementPacket(f),old=f.alice.account.uid;
 await f.bob.call('sendMessage',{message:(()=>{const m=f.envelope({sender:f.bob.account.uid,recipient:old,senderFingerprint:f.bob.account.fingerprint,recipientFingerprint:f.alice.account.fingerprint});m.signature=sign('sha256',Buffer.from(p.context(m)+'\n'+m.ciphertext),f.bob.keys.privateKey).toString('base64');return m;})()});
 const sessionsBefore=(await f.admin.db.query('SELECT count(*)::int n FROM auth.sessions WHERE user_id=$1',[old])).rows[0].n;
 const prepared=await f.request(packet);assert.equal(prepared.status,200,JSON.stringify(prepared));assert.deepEqual(prepared.data,{prepared:true});
 assert.equal((await f.admin.db.query('SELECT idle_days FROM relay.profiles WHERE uid=$1',[old])).rows[0].idle_days,0);
 assert.equal((await f.admin.db.query('SELECT count(*)::int n FROM auth.sessions WHERE user_id=$1',[old])).rows[0].n,sessionsBefore);
 const status=await f.request({...packet,operation:'deviceStatus'});assert.equal(status.data.replacement,true);assert.equal(status.data.pendingUsername,packet.username);
 const created=await f.request(replacementLogin(packet));assert.equal(created.status,200,JSON.stringify(created));assert.notEqual(created.data.account.uid,old);assert.notEqual(created.data.account.fingerprint,f.alice.account.fingerprint);
 const policy=(await f.admin.db.query('SELECT idle_days,device_id,active_at FROM relay.profiles WHERE uid=$1',[old])).rows[0];assert.equal(policy.idle_days,7);assert.equal(policy.device_id,null);assert.ok(Date.now()-new Date(policy.active_at).getTime()<10000);
 assert.equal((await f.alice.call('getAccount')).status,403);assert.equal((await f.admin.db.query('SELECT count(*)::int n FROM auth.sessions WHERE user_id=$1',[old])).rows[0].n,0);
 const inbox=await f.request({operation:'syncInbox',deviceSecret:packet.deviceSecret},created.data.session.access_token);assert.deepEqual(inbox.data.messages,[]);
 await f.admin.db.exec('SELECT relay.sweep()');assert.equal((await f.admin.db.query('SELECT count(*)::int n FROM relay.profiles WHERE uid=$1',[old])).rows[0].n,1);
 await f.admin.db.query("UPDATE relay.profiles SET active_at=now()-interval '7 days 1 second' WHERE uid=$1",[old]);await f.admin.db.exec('SELECT relay.sweep()');
 for(const [table,field] of [['auth.users','id'],['relay.profiles','uid'],['auth.sessions','user_id']])assert.equal((await f.admin.db.query(`SELECT count(*)::int n FROM ${table} WHERE ${field}=$1`,[old])).rows[0].n,0);
 assert.equal((await f.admin.db.query('SELECT count(*)::int n FROM relay.messages WHERE sender=$1 OR recipient=$1',[old])).rows[0].n,0);
 assert.equal((await f.request({operation:'getAccount',deviceSecret:packet.deviceSecret},created.data.session.access_token)).status,200);
 assert.equal((await f.request(replacementLogin(packet))).data.account.uid,created.data.account.uid);
});
test('replacement password failures have committed shared quotas even when new identity changes',async t=>{
 const f=await fixture(t);
 for(let i=0;i<5;i++)assert.equal((await f.request({...replacementPacket(f),previousPassword:'Wrong-Password-2026!'})).status,401);
 assert.equal((await f.request(replacementPacket(f))).status,429);
 assert.equal((await f.admin.db.query('SELECT idle_days FROM relay.profiles WHERE uid=$1',[f.alice.account.uid])).rows[0].idle_days,0);
});
test('expired or stale replacement grant cannot detach old phone or start cleanup; reauthorization and exact retries are safe',async t=>{
 const f=await fixture(t),packet=replacementPacket(f);assert.equal((await f.request(packet)).status,200);
 await f.admin.db.exec("UPDATE relay.pending_accounts SET replacement_until=now()-interval '1 second'");assert.equal((await f.request(replacementLogin(packet))).status,401);
 assert.equal((await f.admin.db.query('SELECT idle_days FROM relay.profiles WHERE uid=$1',[f.alice.account.uid])).rows[0].idle_days,0);
 assert.equal((await f.request(packet)).status,200);assert.equal((await f.request(packet)).status,200);
 const created=await f.request(replacementLogin(packet));assert.equal(created.status,200);assert.equal((await f.request(replacementLogin(packet))).data.account.uid,created.data.account.uid);
 assert.equal((await f.admin.db.query('SELECT count(*)::int n FROM relay.pending_accounts')).rows[0].n,0);
});
test('replacement cannot steal an existing username or another pending installation and remains service-only',async t=>{
 const f=await fixture(t),packet=replacementPacket(f);assert.equal((await f.request({...packet,username:'bob'})).status,409);
 assert.equal((await f.request(packet)).status,200);assert.equal((await f.request(replacementPacket(f,'other_fresh'))).status,403);
 for(const role of ['anon','authenticated'])assert.equal((await f.admin.db.query("SELECT has_function_privilege($1,'public.priperi_replacement(text,uuid,uuid,text,text,jsonb)','EXECUTE') AS allowed",[role])).rows[0].allowed,false);
 const old=(await f.admin.db.query('SELECT idle_days,device_id FROM relay.profiles WHERE uid=$1',[f.alice.account.uid])).rows[0];assert.equal(old.idle_days,0);assert.equal(old.device_id,f.alice.setup.deviceId);
});
test('failure during new-account commit rolls old binding and inactivity back atomically',async t=>{
 const f=await fixture(t),packet=replacementPacket(f);assert.equal((await f.request(packet)).status,200);
 await f.admin.db.exec(`CREATE FUNCTION relay.fail_new_profile() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.username='fresh_alice' THEN RAISE EXCEPTION 'injected failure';END IF;RETURN NEW;END $$;CREATE TRIGGER fail_new_profile BEFORE INSERT ON relay.profiles FOR EACH ROW EXECUTE FUNCTION relay.fail_new_profile();`);
 assert.equal((await f.request(replacementLogin(packet))).status,503);let old=(await f.admin.db.query('SELECT idle_days,device_id FROM relay.profiles WHERE uid=$1',[f.alice.account.uid])).rows[0];assert.equal(old.idle_days,0);assert.equal(old.device_id,f.alice.setup.deviceId);assert.equal((await f.alice.call('getAccount')).status,200);
 await f.admin.db.exec('DROP TRIGGER fail_new_profile ON relay.profiles');assert.equal((await f.request(replacementLogin(packet))).status,200);
});
test('new account deletion removes replacement counters without erasing the old grace-period account',async t=>{
 const f=await fixture(t),packet=replacementPacket(f);await f.request(packet);const created=await f.request(replacementLogin(packet));assert.equal(created.status,200);
 assert.equal((await f.request({operation:'deleteAccount',deviceSecret:packet.deviceSecret},created.data.session.access_token)).status,200);
 assert.equal((await f.admin.db.query("SELECT count(*)::int n FROM relay.rates WHERE subject=$1 AND kind='replacement'",[packet.deviceId])).rows[0].n,0);
 assert.equal((await f.admin.db.query('SELECT idle_days FROM relay.profiles WHERE uid=$1',[f.alice.account.uid])).rows[0].idle_days,7);
});
test('replacement refuses a live account move and a binding changed after password preparation',async t=>{
 const f=await fixture(t);await transferOffer(f);assert.equal((await f.request(replacementPacket(f))).status,403);
 await f.alice.call('cancelTransfer',{lookup:(await f.admin.db.query('SELECT lookup_hash FROM relay.transfers WHERE uid=$1',[f.alice.account.uid])).rows[0].lookup_hash});
 const packet=replacementPacket(f);assert.equal((await f.request(packet)).status,200);await f.admin.db.query('UPDATE relay.profiles SET device_hash=$1 WHERE uid=$2',[p.hash(randomBytes(32)),f.alice.account.uid]);
 assert.equal((await f.request(replacementLogin(packet))).status,401);assert.equal((await f.admin.db.query('SELECT idle_days FROM relay.profiles WHERE uid=$1',[f.alice.account.uid])).rows[0].idle_days,0);
});
test('migration014 refuses an unexpected installed routine and rolls back without adding columns',async t=>{
 const f=await fixture(t,{reinstallReplacement:false});await f.admin.db.exec("CREATE OR REPLACE FUNCTION public.priperi_finish(actor uuid,proof text) RETURNS jsonb LANGUAGE sql AS $$ SELECT '{}'::jsonb $$");
 await assert.rejects(f.admin.db.exec(readFileSync(new URL('../migrations/202610280014_reinstall_replacement.sql',import.meta.url),'utf8')),/Unexpected prior definition/);await f.admin.db.exec('ROLLBACK');
 assert.equal((await f.admin.db.query("SELECT count(*)::int n FROM information_schema.columns WHERE table_schema='relay' AND table_name='pending_accounts' AND column_name='replaces_uid'")).rows[0].n,0);assert.equal((await f.alice.call('getAccount')).status,200);
});

test('one recipient acceptance opens both directions without inventing reverse send counts',async t=>{
 const f=await fixture(t),a=f.alice.account.uid,b=f.bob.account.uid;
 const bobMessage=()=>{const m=f.envelope({sender:b,recipient:a,senderFingerprint:f.bob.account.fingerprint,recipientFingerprint:f.alice.account.fingerprint});m.signature=sign('sha256',Buffer.from(p.context(m)+'\n'+m.ciphertext),f.bob.keys.privateKey).toString('base64');return m;};
 for(let i=0;i<3;i++)assert.equal((await f.alice.call('sendMessage',{message:f.envelope()})).status,200);
 assert.equal((await f.alice.call('approveContact',{uid:b})).status,409,'Initiator cannot accept their own unanswered request');
 const accepted=await f.bob.call('approveContact',{uid:a});assert.equal(accepted.status,200);assert.equal(accepted.data.approved,true);assert.equal(accepted.data.incomingPending,false);assert.equal(accepted.data.sentCount,0);
 assert.equal((await f.admin.db.query('SELECT count(*)::int n FROM relay.contact_permissions WHERE sender=$1 AND recipient=$2',[b,a])).rows[0].n,0);
 for(let i=0;i<5;i++){assert.equal((await f.alice.call('sendMessage',{message:f.envelope()})).status,200);assert.equal((await f.bob.call('sendMessage',{message:bobMessage()})).status,200);}
 for(const [who,peer] of [[f.alice,b],[f.bob,a]]){const state=(await who.call('syncInbox',{contactIds:[peer]})).data.contactStates.find(c=>c.uid===peer);assert.equal(state.approved,true);assert.equal(state.incomingPending,false);assert.equal(state.sentCount,3);}
 assert.equal((await f.eve.call('approveContact',{uid:a})).status,409);assert.equal((await f.bob.call('setBlocked',{uid:a,blocked:true})).status,200);assert.equal((await f.alice.call('sendMessage',{message:f.envelope()})).status,403);assert.equal((await f.bob.call('sendMessage',{message:bobMessage()})).status,403);
});
test('simultaneous introductions need only one acceptance and cannot approve unrelated conversations',async t=>{
 const f=await fixture(t),a=f.alice.account.uid,b=f.bob.account.uid,e=f.eve.account.uid;
 for(let i=0;i<3;i++){await f.alice.call('sendMessage',{message:f.envelope()});const m=f.envelope({sender:b,recipient:a,senderFingerprint:f.bob.account.fingerprint,recipientFingerprint:f.alice.account.fingerprint});m.signature=sign('sha256',Buffer.from(p.context(m)+'\n'+m.ciphertext),f.bob.keys.privateKey).toString('base64');assert.equal((await f.bob.call('sendMessage',{message:m})).status,200);}
 const accepted=await Promise.all([f.bob.call('approveContact',{uid:a}),f.bob.call('approveContact',{uid:a})]);assert.ok(accepted.every(r=>r.status===200&&r.data.approved));
 assert.equal((await f.alice.call('lookupUser',{uid:b})).data.contactState.incomingPending,false);assert.equal((await f.bob.call('lookupUser',{uid:a})).data.contactState.incomingPending,false);
 assert.equal((await f.alice.call('approveContact',{uid:e,recipient:b})).status,409);assert.equal((await f.eve.call('lookupUser',{uid:a})).data.contactState.approved,false);
});
test('migration016 honours past one-way approvals without rewriting account message or counter rows',async t=>{
 const f=await fixture(t,{conversationApproval:false}),a=f.alice.account.uid,b=f.bob.account.uid;
 await f.alice.call('sendMessage',{message:f.envelope()});await f.bob.call('approveContact',{uid:a});
 assert.equal((await f.bob.call('lookupUser',{uid:a})).data.contactState.approved,false);
 const before=(await f.admin.db.query("SELECT (SELECT jsonb_agg(to_jsonb(p) ORDER BY sender,recipient) FROM relay.contact_permissions p) permissions,(SELECT count(*)::int FROM relay.messages) messages,(SELECT count(*)::int FROM relay.profiles) profiles,(SELECT count(*)::int FROM relay.rates) rates")).rows[0];
 await f.admin.db.exec(readFileSync(new URL('../migrations/202610280016_conversation_approval.sql',import.meta.url),'utf8'));
 const after=(await f.admin.db.query("SELECT (SELECT jsonb_agg(to_jsonb(p) ORDER BY sender,recipient) FROM relay.contact_permissions p) permissions,(SELECT count(*)::int FROM relay.messages) messages,(SELECT count(*)::int FROM relay.profiles) profiles,(SELECT count(*)::int FROM relay.rates) rates")).rows[0];assert.deepEqual(after,before);
 for(const [who,peer] of [[f.alice,b],[f.bob,a]])assert.equal((await who.call('lookupUser',{uid:peer})).data.contactState.approved,true);
 for(const role of ['anon','authenticated','service_role'])assert.equal((await f.admin.db.query("SELECT has_function_privilege($1,'relay.contact_state(uuid,uuid)','EXECUTE') ok",[role])).rows[0].ok,false);
 for(const role of ['anon','authenticated'])assert.equal((await f.admin.db.query("SELECT has_function_privilege($1,'public.priperi_request(uuid,uuid,text,text,jsonb,boolean)','EXECUTE') ok",[role])).rows[0].ok,false);
});
test('migration016 rejects unexpected installed routines before changing either approval definition',async t=>{
 const f=await fixture(t,{conversationApproval:false});await f.admin.db.exec("CREATE OR REPLACE FUNCTION relay.contact_state(actor uuid,peer_id uuid) RETURNS jsonb LANGUAGE sql STABLE SET search_path='' AS $$ SELECT '{}'::jsonb $$");
 const before=(await f.admin.db.query("SELECT md5(prosrc) hash FROM pg_proc WHERE oid='public.priperi_request(uuid,uuid,text,text,jsonb,boolean)'::regprocedure")).rows[0].hash;
 await assert.rejects(f.admin.db.exec(readFileSync(new URL('../migrations/202610280016_conversation_approval.sql',import.meta.url),'utf8')),/Unexpected approval definitions/);await f.admin.db.exec('ROLLBACK');
 assert.equal((await f.admin.db.query("SELECT md5(prosrc) hash FROM pg_proc WHERE oid='public.priperi_request(uuid,uuid,text,text,jsonb,boolean)'::regprocedure")).rows[0].hash,before);
});
