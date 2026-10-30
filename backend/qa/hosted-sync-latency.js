// Opt-in probe: owns two random synthetic accounts only. Never uses an owner login/server key.
import {readFileSync,writeFileSync,existsSync,unlinkSync,mkdirSync} from 'node:fs';
import {randomBytes,randomUUID,generateKeyPairSync,sign} from 'node:crypto';
import assert from 'node:assert/strict';
import * as p from '../supabase/functions/relay/policy.js';
if(process.env.PRIPERI_HOSTED_SPEED_TEST!=='yes')throw Error('Explicit hosted speed-test opt-in required');
const properties=readFileSync(new URL('../../priperi/gradle.properties',import.meta.url),'utf8'),setting=n=>properties.match(new RegExp('^'+n+'=(.+)$','m'))?.[1].trim(),endpoint=setting('priperiBackendUrl'),key=setting('priperiSupabaseKey');
assert.ok(process.env.PRIPERI_EXPECTED_HOSTED_URL,'Set the explicitly expected disposable target URL');assert.equal(endpoint,process.env.PRIPERI_EXPECTED_HOSTED_URL);assert.ok(key.startsWith('sb_publishable_'));
const recovery=new URL('../../local-only/evidence/v2.9.0/live-speed-recovery.json',import.meta.url);mkdirSync(new URL('.',recovery),{recursive:true});if(existsSync(recovery))throw Error('Prior synthetic recovery needs cleanup');
const suffix=randomBytes(4).toString('hex'),password='Pp9!'+randomBytes(24).toString('base64url'),accounts=[],events=[];
const save=()=>writeFileSync(recovery,JSON.stringify(accounts.map(c=>({setup:c.setup,uid:c.account?.uid,token:c.token}))),{mode:0o600});
async function request(c,operation,payload={}){
 const start=performance.now(),response=await fetch(endpoint,{method:'POST',redirect:'error',signal:AbortSignal.timeout(30000),headers:{apikey:key,'Content-Type':'application/json',...(c.token?{Authorization:'Bearer '+c.token}:{})},body:JSON.stringify(operation==='continue'?c.setup:{operation,deviceSecret:c.setup.deviceSecret,...payload})});
 const body=await response.json();events.push({operation,status:response.status,ms:Math.round(performance.now()-start),code:body.error?.code});return {status:response.status,...body};
}
const ok=r=>{assert.equal(r.status,200,r.error?.code);return r.data;};
async function create(label){
 const keys=generateKeyPairSync('ec',{namedCurve:'prime256v1'}),signing=keys.publicKey.export({type:'spki',format:'der'}).toString('base64'),hpke=JSON.stringify({primaryKeyId:12,key:[{keyId:12,status:'ENABLED',outputPrefixType:'TINK',keyData:{typeUrl:'type.googleapis.com/google.crypto.tink.HpkePublicKey',keyMaterialType:'ASYMMETRIC_PUBLIC',value:randomBytes(48).toString('base64')}}]});
 const c={keys,setup:{operation:'continue',username:'qa_speed'+label+'_'+suffix,password,deviceSecret:randomBytes(32).toString('base64url'),installation:randomUUID(),deviceId:randomUUID(),hpke,signing}};accounts.push(c);save();const result=ok(await request(c,'continue'));c.account=result.account;c.token=result.session.access_token;save();return c;
}
let failure;
try{
 const a=await create('a'),b=await create('b'),messages=[];
 for(let i=0;i<6;i++){
  const m={id:randomUUID(),sender:a.account.uid,recipient:b.account.uid,sentAt:Date.now(),senderFingerprint:a.account.fingerprint,recipientFingerprint:b.account.fingerprint,ciphertext:randomBytes(160).toString('base64')};m.signature=sign('sha256',Buffer.from(p.context(m)+'\n'+m.ciphertext),a.keys.privateKey).toString('base64');
  assert.equal(ok(await request(a,'sendMessage',{message:m})).state,'sent');messages.push(m);
  if(i===0)ok(await request(b,'approveContact',{uid:a.account.uid}));
 }
 const inbox=ok(await request(b,'syncInbox'));assert.deepEqual(new Set(inbox.messages.map(m=>m.id)),new Set(messages.map(m=>m.id)));assert.equal(inbox.senderProfiles.length,1);assert.equal(inbox.senderProfiles[0].uid,a.account.uid);assert.ok(inbox.groupData);
 assert.equal((await request(a,'acknowledgeMessages',{ids:[messages[0].id]})).status,403);
 ok(await request(b,'acknowledgeMessages',{ids:messages.map(m=>m.id)}));assert.equal(ok(await request(b,'syncInbox')).messages.length,0);
 assert.equal(ok(await request(a,'syncInbox',{receiptIds:messages.map(m=>m.id)})).receipts.length,6);
}catch(error){failure=error;}
finally{
 for(const c of accounts)if(c.account&&!c.deleted){try{const response=ok(await request(c,'deleteAccount'));assert.equal(response.status,'deleted');c.deleted=true;save();}catch(error){failure??=error;}}
 if(accounts.length===2&&accounts.every(c=>c.deleted))unlinkSync(recovery);
}
const median=values=>values.sort((a,b)=>a-b)[Math.floor(values.length/2)];
console.log(JSON.stringify({checkedAt:new Date().toISOString(),project:new URL(endpoint).hostname,ok:!failure,events,sendMedianMs:median(events.filter(e=>e.operation==='sendMessage'&&e.status===200).map(e=>e.ms)),cleanupComplete:accounts.length===2&&accounts.every(c=>c.deleted),cleanupSubjects:accounts.map(c=>({uid:c.account?.uid,username:c.setup.username,proofHash:p.secretHash(c.setup.deviceSecret)}))},null,2));
if(failure)throw failure;
