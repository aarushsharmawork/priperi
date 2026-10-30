// Explicitly opt-in. No signup, login, valid device proof, owner ID or database mutation.
import {readFileSync} from 'node:fs';
import assert from 'node:assert/strict';
if(process.env.PRIPERI_HOSTED_READ_ONLY!=='yes')throw new Error('Set PRIPERI_HOSTED_READ_ONLY=yes for the configured project');
const properties=readFileSync(new URL('../../priperi/gradle.properties',import.meta.url),'utf8');
const setting=name=>properties.match(new RegExp('^'+name+'=(.+)$','m'))?.[1].trim();
const relay=setting('priperiBackendUrl'),origin=new URL(relay).origin,key=setting('priperiSupabaseKey');
assert.equal(new URL(relay).protocol,'https:');assert.ok(key?.startsWith('sb_publishable_'));
const protectedOps=['getRecoveryStatus','registerRecovery','revokeRecovery','finishRecovery','getAccountPolicy','setSelfDestruct','accountPresence','transferChallenge','prepareTransfer','cancelTransfer','finishTransfer','getAccount','lookupUser','searchUsers','renameAccount','setNotificationToken','setBlocked','approveContact','sendMessage','syncInbox','acknowledgeMessage','acknowledgeMessages','deleteAccount','changePassword','createGroup','renameGroup','inviteGroupMember','acceptGroupInvite','declineGroupInvite','removeGroupMember','setGroupAdmin','leaveGroup','sendGroupMessage','ackGroupMessages'];
const results=[];
async function call(path,data,extra={}){
 const response=await fetch(path,{method:data===undefined?'GET':'POST',redirect:'error',signal:AbortSignal.timeout(30000),headers:{apikey:key,'Content-Type':'application/json',...extra},...(data===undefined?{}:{body:JSON.stringify(data)})});
 let json;try{json=await response.json();}catch{}
 return {status:response.status,code:json?.error?.code,settings:json};
}
for(const operation of protectedOps){
 const r=await call(relay,{operation,deviceSecret:'a'.repeat(43)});assert.equal(r.status,401,operation);results.push({operation,status:r.status,code:r.code});
}
for(const operation of ['recoveryChallenge','recoverAccount','prepareReplacement','continue','deviceStatus','resetChallenge','resetAccount','readTransfer','acceptTransfer','transferSourceStatus','acknowledgeTransferSource','transferRollbackChallenge','rollbackTransfer']){
 const r=await call(relay,{operation,deviceSecret:'invalid'});assert.equal(r.status,400,operation);results.push({operation,status:r.status,code:r.code});
}
const replacementProbe=await call(relay,{operation:'prepareReplacement',deviceSecret:'a'.repeat(43),username:'INVALID'});assert.equal(replacementProbe.status,400,'New replacement route should validate input before any SQL/password work');results.push({operation:'prepareReplacement-invalid-name',status:replacementProbe.status,code:replacementProbe.code});
for(const [path,extra] of [['/rest/v1/profiles?select=uid&limit=0',{}],['/rest/v1/profiles?select=uid&limit=0',{'Accept-Profile':'relay'}],['/auth/v1/user',{}]]){
 const r=await call(origin+path,undefined,extra);assert.ok([400,401,403,404,406].includes(r.status),path+' unexpectedly accessible');results.push({route:path,schema:extra['Accept-Profile']||'default',status:r.status});
}
const r=await call(origin+'/auth/v1/settings');assert.equal(r.status,200);
const settings={disable_signup:r.settings.disable_signup,external:r.settings.external};
assert.equal(settings.disable_signup,true,'Public signup must remain disabled');
console.log(JSON.stringify({checkedAt:new Date().toISOString(),project:new URL(relay).hostname,protectedOperations:protectedOps.length,results,authSettings:settings},null,2));
