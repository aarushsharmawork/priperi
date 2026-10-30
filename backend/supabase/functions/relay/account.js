import {randomBytes} from 'node:crypto';
import * as p from './policy.js';
import {acceptContext,chainKeys,chainDigest,verifyAuthorized} from './delegation.js';
export const accountKinds={getAccountPolicy:'sync',setSelfDestruct:'settings',accountPresence:'sync',transferChallenge:'settings',prepareTransfer:'settings',cancelTransfer:'settings',finishTransfer:'settings'};
export const publicTransferKinds=['readTransfer','acceptTransfer','transferSourceStatus','acknowledgeTransferSource','transferRollbackChallenge','rollbackTransfer'];
const hash=value=>{p.requireThat(typeof value==='string'&&/^[a-f0-9]{64}$/.test(value));return value;};
export const authSeed=async(rpc,actor,proof)=>rpc('priperi_auth_seed',{actor,proof,candidate:randomBytes(32).toString('base64url')});
export async function publicTransfer(operation,data,proof,rpc,checkPassword){
 const lookup=hash(data.lookup),base={actor:null,session_id:null,proof,recent_password:false};
 // Separate transaction: a rejected capability/signature cannot roll this counter back.
 await rpc('priperi_transfer',{...base,operation:'publicAttempt',payload:{lookup}});
 if(operation==='readTransfer'||operation==='transferSourceStatus'||operation==='acknowledgeTransferSource')return rpc('priperi_transfer',{...base,operation:operation==='readTransfer'?'read':operation==='transferSourceStatus'?'sourceStatus':'sourceAck',payload:{lookup}});
 if(operation==='transferRollbackChallenge')return rpc('priperi_transfer',{...base,operation:'sourceChallenge',payload:{lookup}});
 if(operation==='rollbackTransfer'){
  const nonce=p.messageId(data.nonce),info=await rpc('priperi_transfer',{...base,operation:'rollbackInfo',payload:{lookup,nonce}});
  p.verifyDeviceSignature(data.signature,info.sourceKey,['priperi-transfer-rollback-v1',lookup,info.uid,nonce,proof].join('\n'));
  await checkPassword(info.uid,info.authSeed,data.password);
  return rpc('priperi_transfer',{...base,operation:'rollback',payload:{lookup,nonce}});
 }
 const claimHash=p.hash(p.text(data.claimSecret,43,43));p.secretHash(data.claimSecret);
 const info=await rpc('priperi_transfer',{...base,operation:'claimInfo',payload:{lookup,claimHash}}),account=info.account;
 const target=data.target;p.requireThat(target&&typeof target==='object');p.messageId(target.installation);p.messageId(target.deviceId);p.validateKeys(account.hpke,target.signing);
 const chain=target.chain;chainKeys(account.uid,account.fingerprint,account.signing,chain);
 p.requireThat(chain.length===info.chain.length+1&&chain.slice(0,-1).every((c,i)=>c.key===info.chain[i].key&&c.signature===info.chain[i].signature)&&chain.at(-1).key===target.signing,'permission-denied','This transfer was not approved by the old phone.');
 const context=acceptContext(lookup,account.uid,claimHash,proof,target.installation,target.deviceId,target.signing,chain);
 p.verifyDeviceSignature(data.signature,target.signing,context);
 return rpc('priperi_transfer',{...base,operation:'accept',payload:{lookup,claimHash,digest:p.hash(context),target:{proof,installation:target.installation,deviceId:target.deviceId,chain}}});
}
export async function accountAction(operation,data,claims,proof,rpc,recent){
 const base={actor:claims.sub,session_id:claims.session_id,proof,recent_password:recent(claims)};
 if(['getAccountPolicy','setSelfDestruct','accountPresence'].includes(operation)){
  const payload={};if(operation==='setSelfDestruct'){p.requireThat(Number.isInteger(data.days)&&[0,1,7,365].includes(data.days));payload.days=data.days;}
  return rpc('priperi_account',{...base,operation:operation==='getAccountPolicy'?'getPolicy':operation==='setSelfDestruct'?'setPolicy':'presence',payload});
 }
 const lookup=hash(data.lookup);
 if(operation==='finishTransfer'){
  const me=await rpc('priperi_request',{...base,operation:'getAccount',payload:{}}),keys=chainKeys(me.uid,me.fingerprint,me.signing,me.delegations||[]);
  p.verifyDeviceSignature(data.signature,keys.at(-1),['priperi-transfer-finish-v1',lookup,me.uid,proof,chainDigest(me.delegations||[])].join('\n'));
  return rpc('priperi_transfer',{...base,operation:'confirm',payload:{lookup}});
 }
 if(operation==='cancelTransfer')return rpc('priperi_transfer',{...base,operation:'cancel',payload:{lookup}});
 p.requireThat(base.recent_password&&claims.app_metadata?.priperi_auth_version===2,'reauthentication-required','Confirm your current password on this phone first.');
 if(operation==='transferChallenge')return rpc('priperi_transfer',{...base,operation:'challenge',payload:{lookup}});
 const me=await rpc('priperi_request',{...base,operation:'getAccount',payload:{}});
 const chain=data.chain;chainKeys(me.uid,me.fingerprint,me.signing,chain);
 const previous=me.delegations||[];p.requireThat(chain.length===previous.length+1&&chain.length<=31&&previous.every((c,i)=>c.key===chain[i].key&&c.signature===chain[i].signature));
 const capsule=p.text(data.capsule,64,32768),claimHash=hash(data.claimHash),nonce=p.messageId(data.nonce);
 verifyAuthorized(data.signature,['priperi-transfer-prepare-v1',me.uid,lookup,nonce,claimHash,p.hash(capsule),chain.at(-1).key].join('\n'),me);
 return rpc('priperi_transfer',{...base,operation:'prepare',payload:{lookup,nonce,capsule,claimHash,chain}});
}
