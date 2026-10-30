import {signupBinding} from './signup.js';
import {recoveryKinds,recoveryAction,publicRecovery} from './recovery.js';
import * as p from './policy.js';
import {accountKinds,publicTransferKinds,publicTransfer,accountAction,authSeed} from './account.js';
import {verifyAuthorized} from './delegation.js';
import {groupKinds,groupPayload} from './groups.js';

const kinds={...recoveryKinds,...accountKinds,...groupKinds,getAccount:'sync',lookupUser:'lookup',searchUsers:'lookup',renameAccount:'settings',setNotificationToken:'settings',setBlocked:'settings',approveContact:'settings',sendMessage:'send',syncInbox:'sync',acknowledgeMessage:'ack',acknowledgeMessages:'ack',deleteAccount:'settings',changePassword:'settings'};
const codes={'invalid-argument':400,'unauthenticated':401,'reauthentication-required':401,'account-expired':401,'permission-denied':403,'not-found':404,'already-exists':409,'failed-precondition':409,'contact-approval-required':409,'resource-exhausted':429,'signup-verification-required':409};
const uuid=value=>{p.requireThat(typeof value==='string'&&/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value));return value;};
export function password(value) {
 p.text(value,12,72);p.requireThat(new TextEncoder().encode(value).length<=72 && /[A-Z]/.test(value)&&/[a-z]/.test(value)&&/[0-9]/.test(value)&&/[^A-Za-z0-9\s]/.test(value),'invalid-argument','Use 12–72 characters with uppercase, lowercase, a number and a symbol.');return value;
}
export function managedPassword(secret,value) {
 p.secretHash(secret);password(value);
 // The v2 path supplies a private account-specific server seed. The old
 // installation-secret input remains only for correct-device legacy upgrade.
 // Supabase still hashes this derived credential; the client never receives it.
 return 'Pp9!'+p.hash('priperi-managed-password-v1\n'+secret+'\n'+value);
}
export function recent(claims) {return Array.isArray(claims.amr)&&claims.amr.some(a=>a.method==='password'&&Number.isFinite(a.timestamp)&&Date.now()/1000-a.timestamp>=-60&&Date.now()/1000-a.timestamp<300);}
function reply(data,status=200) {return new Response(JSON.stringify(data),{status,headers:{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer'}});}
export async function body(request) {
 p.requireThat(request.headers.get('Content-Type')?.split(';')[0].trim()==='application/json'&&!request.headers.has('Content-Encoding'));
 const length=request.headers.get('Content-Length');p.requireThat(!length||/^\d+$/.test(length)&&Number(length)<=512000);
 const reader=request.body?.getReader();p.requireThat(reader);let count=0,parts=[];
 try{for(;;){const {done,value}=await reader.read();if(done)break;count+=value.length;p.requireThat(count<=512000);parts.push(value);}}finally{await reader.cancel().catch(()=>{});}
 const bytes=new Uint8Array(count);let offset=0;for(const part of parts){bytes.set(part,offset);offset+=part.length;}
 let value;try{value=JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes));}catch{p.requireThat(false);}
 p.requireThat(value&&typeof value==='object'&&!Array.isArray(value));p.requireThat(count<=40000||value.operation==='sendGroupMessage');return value;
}
function dbError(error) {
 const message=error?.message||'';
 if(message.includes('recovery_unavailable'))return new p.PolicyError('not-found','This recovery backup is unavailable, revoked, or belongs to a changed account.');
 if(message.includes('finish_transfer_first'))return new p.PolicyError('failed-precondition','Finish receiving this account before moving it again.');
 if(message.includes('transfer_unavailable'))return new p.PolicyError('not-found','This transfer expired, was cancelled, or was already used. Start again on the old phone.');
 if(message.includes('group_changed'))return new p.PolicyError('group-changed','The group changed. Review its members before sending again.');
 if(message.includes('group_limit'))return new p.PolicyError('resource-exhausted','Groups support 20 members and up to 20 joined groups per account. Try again after freeing a place.');
 if(message.includes('invite_unavailable'))return new p.PolicyError('not-found','This invitation expired or was withdrawn. Refresh your group requests.');
 if(message.includes('last_admin'))return new p.PolicyError('failed-precondition','Choose another admin first.');
 if(message.includes('contact_approval_required'))return new p.PolicyError('contact-approval-required','Wait for this contact to approve you before sending more messages.');
 if(message.includes('no_contact_request'))return new p.PolicyError('failed-precondition','There is no message request to approve.');
 if(message.includes('not_found'))return new p.PolicyError('not-found','This account is unavailable.');
 if(message.includes('recent_password_required'))return new p.PolicyError('reauthentication-required','Enter your password again.');
 if(message.includes('denied'))return new p.PolicyError('permission-denied','Use the phone linked to this account.');
 if(message.includes('inbox_full'))return new p.PolicyError('resource-exhausted','Your contact’s inbox is full. Try later.');
 if(message.includes('conflict'))return new p.PolicyError('already-exists','Message identity conflict.');
 if(error?.code==='23505'||message.includes('name_taken'))return new p.PolicyError('already-exists','That username is taken.');
 return new p.PolicyError('unavailable','Could not finish. Please try again.');
}
export function createHandler({admin,authClient,projectUrl,push=async()=>{},background=promise=>promise.catch(()=>{}),combineSendChecks=true,signup=/** @type {{ready():void,verify(token:string,binding:string):Promise<string>,page(request:Request):Response}|null} */ (null)}) {
 async function rpc(name,args) {const result=await admin.rpc(name,args);if(result.error)throw dbError(result.error);if(result.data?.accountExpired)throw new p.PolicyError('account-expired','This account was deleted after its inactivity period.');if(result.data?.limited)throw new p.PolicyError('resource-exhausted','Please wait a few minutes and try again.');return result.data;}
 async function tokenClaims(token) {
  p.requireThat(typeof token==='string'&&token.length>0&&token.length<8185,'unauthenticated','Sign in again.');
  const {data,error}=await authClient().auth.getClaims(token);const claims=data?.claims;
  p.requireThat(!error&&claims&&claims.iss===projectUrl+'/auth/v1'&&claims.aud==='authenticated'&&claims.exp>Date.now()/1000,'unauthenticated','Sign in again.');
  p.requireThat([1,2].includes(claims.app_metadata?.priperi_auth_version),'unauthenticated','Sign in again on your registered phone to update account protection.');
  uuid(claims.sub);uuid(claims.session_id);return claims;
 }
 async function admit(data,fields){
  p.requireThat(signup,'unavailable','Account verification is not configured.');signup.ready();
  p.requireThat(typeof data.signupToken==='string','signup-verification-required','Complete a quick verification before creating an account.');
  const token_hash=await signup.verify(data.signupToken,signupBinding(fields));
  const result=await rpc('priperi_signup_admit',{token_hash,proof:fields.proof,installation_id:fields.installation,device_id_value:fields.device,account_name:fields.name,fingerprint_value:fields.fingerprint});
  p.requireThat(result.admitted===true,'permission-denied','Complete account verification again.');
 }
 async function signedIn(request) {
  const authorization=request.headers.get('Authorization')||'';p.requireThat(authorization.startsWith('Bearer ')&&authorization.length<8192,'unauthenticated','Sign in again.');
  return tokenClaims(authorization.slice(7));
 }
 return async request=>{
  try {
   if(request.method==='GET'&&new URL(request.url).pathname==='/functions/v1/relay/signup'){p.requireThat(signup,'unavailable','Account verification is not configured.');return signup.page(request);}
   p.requireThat(request.method==='POST','not-found','This action is unavailable.');
   const data=await body(request), operation=data.operation,proof=p.secretHash(data.deviceSecret);
   if(operation==='prepareReplacement') {
    const name=p.username(data.username),previous=password(data.previousPassword),fingerprint=p.validateKeys(data.hpke,data.signing),installation=p.messageId(data.installation),device=p.messageId(data.deviceId);
    p.requireThat(data.confirmLoss===true);
    const base={proof,installation_id:installation,device_id_value:device,fingerprint_value:fingerprint};
    const info=await rpc('priperi_replacement',{...base,action:'info',payload:{}});
    p.requireThat(!info.denied,'permission-denied','This account cannot be replaced here. Check your previous password and use the original phone, or review Recovery options.');
    const id=uuid(info.uid),verifier=authClient();
    const checked=await verifier.auth.signInWithPassword({email:id+'@accounts.priperi.invalid',password:managedPassword(info.seed,previous)});
    p.requireThat(!checked.error&&checked.data?.user?.id===id&&checked.data?.session,'unauthenticated','The previous account password does not match. Nothing was changed.');
    let verificationSession;
    try {
     const claims=await tokenClaims(checked.data.session.access_token);
     p.requireThat(claims.sub===id&&claims.app_metadata?.priperi_auth_version===2&&recent(claims),'unauthenticated','Confirm the previous account password again.');
     verificationSession=claims.session_id;
     const prepareArgs={...base,action:'prepare',payload:{uid:id,seed:info.seed,session:claims.session_id,username:name,hpke:data.hpke,signing:data.signing}};
     let result=await rpc('priperi_replacement',prepareArgs);
     if(result.humanRequired){await admit(data,{proof,installation,device,name,fingerprint});result=await rpc('priperi_replacement',prepareArgs);}
     p.requireThat(!result.humanRequired,'permission-denied','Complete account verification again.');
     p.requireThat(!result.nameTaken,'already-exists','Choose a different available username. The previous username stays reserved until its account is deleted.');
     p.requireThat(result.prepared===true&&!result.denied,'permission-denied','Could not prepare the new account. Nothing in your previous account was changed.');
     return reply({data:{prepared:true}});
    } finally {
     if(verificationSession)try{await rpc('priperi_replacement',{...base,action:'close',payload:{uid:id,session:verificationSession}});}catch{}
     try{if(typeof verifier.auth.signOut==='function')await verifier.auth.signOut({scope:'local'});}catch{}
    }
   }
   if(['recoveryChallenge','recoverAccount'].includes(operation))return reply({data:await publicRecovery(operation,data,proof,rpc,async(uid,seed,pass)=>{password(pass);const client=authClient(),checked=await client.auth.signInWithPassword({email:uid+'@accounts.priperi.invalid',password:managedPassword(seed,pass)});p.requireThat(!checked.error&&checked.data?.user?.id===uid&&checked.data?.session,'unauthenticated','The current app password does not match.');let session;try{const claims=await tokenClaims(checked.data.session.access_token);session=claims.session_id;p.requireThat(claims.sub===uid&&claims.app_metadata?.priperi_auth_version===2&&recent(claims),'unauthenticated','Confirm your current app password again.');return {session,close:async()=>{try{await rpc('priperi_recovery',{actor:null,session_id:null,proof,operation:'close',payload:{id:data.id,session},recent_password:false});}catch{}try{if(typeof client.auth.signOut==='function')await client.auth.signOut({scope:'local'});}catch{}}};}catch(error){if(session)try{await rpc('priperi_recovery',{actor:null,session_id:null,proof,operation:'close',payload:{id:data.id,session},recent_password:false});}catch{}try{if(typeof client.auth.signOut==='function')await client.auth.signOut({scope:'local'});}catch{}throw error;}})});
   if(publicTransferKinds.includes(operation))return reply({data:await publicTransfer(operation,data,proof,rpc,async(uid,seed,pass)=>{const verifier=authClient(),checked=await verifier.auth.signInWithPassword({email:uid+'@accounts.priperi.invalid',password:managedPassword(seed,pass)});p.requireThat(!checked.error&&checked.data?.user?.id===uid&&checked.data?.session,'unauthenticated','The current app password does not match.');try{if(typeof verifier.auth.signOut==='function')await verifier.auth.signOut({scope:'local'});}catch{}})});
   if(['deviceStatus','resetChallenge','resetAccount'].includes(operation)) {
    const fingerprint=p.validateKeys(data.hpke,data.signing),installation=p.messageId(data.installation),device=data.deviceId===undefined?null:p.messageId(data.deviceId);
    const payload={};
    if(operation==='resetAccount') {
     payload.uid=uuid(data.uid);payload.nonce=p.messageId(data.nonce);
     const profile={uid:payload.uid,fingerprint,signing:data.signing,delegations:data.delegations||[]};verifyAuthorized(data.signature,p.resetContext(payload.uid,payload.nonce,installation,fingerprint),profile);payload.delegations=profile.delegations;
    }
    const result=await rpc('priperi_device',{proof,installation_id:installation,fingerprint_value:fingerprint,device_id_value:device,action:operation==='deviceStatus'?'status':operation,payload});
    p.requireThat(!result.limited,'resource-exhausted','Please wait a few minutes and try again.');
    p.requireThat(!result.denied,'permission-denied','The secure key for this phone does not match its account. Reinstalling or clearing app data cannot recover that key.');
    return reply({data:result});
   }
   if(operation==='continue') {
    const name=data.username===undefined?null:p.username(data.username),pass=password(data.password),fingerprint=p.validateKeys(data.hpke,data.signing),installation=p.messageId(data.installation),device=data.deviceId===undefined?null:p.messageId(data.deviceId);
    const beginArgs={name,proof,installation_id:installation,hpke_key:data.hpke,signing_key:data.signing,fingerprint_value:fingerprint,expected_actor:data.expectedUid===undefined?null:uuid(data.expectedUid),device_id_value:device};
    let entry=await rpc('priperi_begin_device',beginArgs);
    if(entry.humanRequired){await admit(data,{proof,installation,device,name,fingerprint});entry=await rpc('priperi_begin_device',beginArgs);}
    p.requireThat(!entry.humanRequired,'permission-denied','Complete account verification again.');
    p.requireThat(!entry.limited,'resource-exhausted','Please wait a few minutes and try again.');
    p.requireThat(!entry.denied,'unauthenticated','Couldn’t sign in. Check your password and use the registered phone.');
    const id=uuid(entry.uid),email=id+'@accounts.priperi.invalid',seed=await authSeed(rpc,id,proof),bound=managedPassword(seed,pass);
    if(entry.pending) {
     const created=await admin.auth.admin.createUser({id,email,password:bound,email_confirm:true,app_metadata:{priperi_auth_version:2}});
     // A retry may find an Auth record created before the first response was lost.
     if(created.error && !['email_exists','user_already_exists'].includes(created.error.code))throw new p.PolicyError('unavailable','Could not create this account. Please try again.');
    }
    const client=authClient();let login=await client.auth.signInWithPassword({email,password:bound});
    if(login.error) {
     const existing=await admin.auth.admin.getUserById(id);
     // Only legacy accounts may try the old credential. A bound account never
     // falls back to a password-only login, even after a failed attempt.
     if(!existing.error&&existing.data?.user&&existing.data.user.app_metadata?.priperi_auth_version!==2) {
      const oldVersion=existing.data.user.app_metadata?.priperi_auth_version;
      const legacy=await client.auth.signInWithPassword({email,password:oldVersion===1?managedPassword(data.deviceSecret,pass):pass});
      if(!legacy.error&&legacy.data?.user?.id===id&&legacy.data?.session) {
       const upgraded=await admin.auth.admin.updateUserById(id,{password:bound,app_metadata:{priperi_auth_version:2}});
       p.requireThat(!upgraded.error,'unavailable','Could not update account protection. Please try again.');
       login=await client.auth.signInWithPassword({email,password:bound});
      }
     }
    }
    p.requireThat(!login.error&&login.data?.user?.id===id&&login.data?.session,'unauthenticated','Couldn’t sign in. Check your password and use the registered phone.');
    const profile=await rpc('priperi_finish',{actor:id,proof});const session=login.data.session;
    if(device!==null)await rpc('priperi_device',{proof,installation_id:installation,fingerprint_value:fingerprint,device_id_value:device,action:'bind',payload:{uid:id}});
    // Internal Auth identifiers are never shown in the app or returned as user details.
    return reply({data:{account:profile,session:{uid:id,access_token:session.access_token,refresh_token:session.refresh_token,expires_at:session.expires_at}}});
   }
   p.requireThat(typeof operation==='string'&&Object.hasOwn(kinds,operation),'not-found','This action is unavailable.');
   const claims=await signedIn(request),actor=claims.sub,session_id=claims.session_id;
   if(['accountPresence','getAccountPolicy'].includes(operation))return reply({data:await accountAction(operation,data,claims,proof,rpc,recent)});
   let acknowledgements;
   if(operation==='acknowledgeMessages') {
    acknowledgements=data.ids;
    p.requireThat(Array.isArray(acknowledgements)&&acknowledgements.length>0&&acknowledgements.length<=50);
    acknowledgements.forEach(p.messageId);
    p.requireThat(new Set(acknowledgements).size===acknowledgements.length);
   }
   if(operation==='deleteAccount') {
    p.requireThat(recent(claims),'reauthentication-required','Enter your password again.');
    const result=await rpc('priperi_request',{actor,session_id,proof,operation,payload:{},recent_password:true});return reply({data:result});
   }
   // Charge and fetch the current public signing profile in one committed RPC.
   // The final send still rechecks live session/proof/ownership/blocks in SQL.
   const sendContext=combineSendChecks&&['sendMessage','sendGroupMessage'].includes(operation)?await rpc('priperi_request',{actor,session_id,proof,operation:'sendContext',payload:{},recent_password:false}):null;
   const allowed=sendContext?!sendContext.attemptDenied:await rpc('priperi_attempt',{actor,session_id,proof,kind:kinds[operation]});
   p.requireThat(allowed,'permission-denied','Access is paused. Use your linked phone, or wait and try again.');
   if(acknowledgements) {
    const result=await rpc('priperi_request',{actor,session_id,proof,operation:'acknowledgeBatch',payload:{ids:acknowledgements},recent_password:false});
    p.requireThat(!result.batchDenied,'permission-denied','Access is paused. Use your linked phone, or wait and try again.');
    return reply({data:result});
   }
   if(Object.hasOwn(groupKinds,operation)) {
    const payload=await groupPayload(operation,data,actor,async()=>sendContext||rpc('priperi_request',{actor,session_id,proof,operation:'getAccount',payload:{},recent_password:false}));
    const result=await rpc('priperi_groups',{actor,session_id,proof,operation,payload});
    if(result.notifyTokens){for(const token of result.notifyTokens)background(push(token).catch(()=>{}));delete result.notifyTokens;}
    return reply({data:result});
   }
   if(Object.hasOwn(recoveryKinds,operation))return reply({data:await recoveryAction(operation,data,claims,proof,rpc,recent)});
   if(Object.hasOwn(accountKinds,operation))return reply({data:await accountAction(operation,data,claims,proof,rpc,recent)});
   if(operation==='searchUsers') {
    const query=p.username(data.query);
    return reply({data:await rpc('priperi_search',{actor,session_id,proof,query})});
   }
   const payload={};
   if(operation==='lookupUser') {if(data.username!==undefined)payload.username=p.username(data.username);else payload.uid=uuid(data.uid);}
   if(operation==='renameAccount')payload.username=p.username(data.username);
   if(operation==='setNotificationToken')payload.token=data.token===null?null:p.text(data.token,20,4096);
   if(operation==='approveContact')payload.uid=uuid(data.uid);
   if(operation==='setBlocked'){payload.uid=uuid(data.uid);p.requireThat(typeof data.blocked==='boolean');payload.blocked=data.blocked;}
   if(operation==='syncInbox'){payload.groupTags=data.groupTags||{};p.requireThat(payload.groupTags&&typeof payload.groupTags==='object'&&!Array.isArray(payload.groupTags)&&Object.keys(payload.groupTags).length<=20);for(const [id,tag] of Object.entries(payload.groupTags)){p.messageId(id);p.requireThat(typeof tag==='string'&&/^[a-f0-9]{32}$/.test(tag));}payload.receiptIds=data.receiptIds||[];p.requireThat(Array.isArray(payload.receiptIds)&&payload.receiptIds.length<=100);payload.receiptIds.forEach(p.messageId);payload.contactIds=data.contactIds||[];p.requireThat(Array.isArray(payload.contactIds)&&payload.contactIds.length<=50);payload.contactIds.forEach(uuid);p.requireThat(new Set(payload.contactIds).size===payload.contactIds.length);}
   if(operation==='acknowledgeMessage')payload.id=p.messageId(data.id);
   if(operation==='sendMessage') {
    const m=p.validateEnvelope(data.message);p.requireThat(m.sender===actor,'permission-denied','This conversation is unavailable.');uuid(m.sender);uuid(m.recipient);
    const me=sendContext||await rpc('priperi_request',{actor,session_id,proof,operation:'getAccount',payload:{},recent_password:false});verifyAuthorized(m.signature,p.context(m)+'\n'+m.ciphertext,me);
    payload.message=m;payload.digest=p.hash(p.context(m)+'\n'+m.ciphertext+'\n'+m.signature);
   }
   if(operation==='changePassword') {
    p.requireThat(recent(claims),'reauthentication-required','Enter your password again.');
    await rpc('priperi_account',{actor,session_id,proof,operation:'cancelTransfers',payload:{},recent_password:true});
    const pass=managedPassword(await authSeed(rpc,actor,proof),data.password),changed=await admin.auth.admin.updateUserById(actor,{password:pass,app_metadata:{priperi_auth_version:2}});
    p.requireThat(!changed.error,'unavailable','Couldn’t change the password. Try again.');return reply({data:{ok:true}});
   }
   const result=await rpc('priperi_request',{actor,session_id,proof,operation,payload,recent_password:recent(claims)});
   // Old migration fixtures/rolling versions can still return separate group data.
   if(operation==='syncInbox'&&!result.groupData)result.groupData=await rpc('priperi_groups',{actor,session_id,proof,operation:'syncGroups',payload:{tags:payload.groupTags}});
   if(result.notify){background(push(result.notify).catch(()=>{}));delete result.notify;}
   return reply({data:result});
  } catch(error) {
   const e=error instanceof p.PolicyError?error:new p.PolicyError('unavailable','Could not finish. Please try again.');return reply({error:{code:e.code,message:e.message}},codes[e.code]||503);
  }
 };
}
