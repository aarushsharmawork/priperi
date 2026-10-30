import * as p from './policy.js';
import {chainKeys,chainDigest} from './delegation.js';
const uuid=v=>{p.requireThat(typeof v==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[1-8][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/.test(v));return v;};
export const recoveryKinds={getRecoveryStatus:'settings',registerRecovery:'settings',revokeRecovery:'settings',finishRecovery:'settings'};
export function recoveryContext(id,uid,proof,target,nonce='challenge'){return ['priperi-recovery-accept-v1',id,uid,proof,target.installation,target.deviceId,target.signing,chainDigest(target.chain),nonce].join('\n');}
export async function recoveryAction(op,data,claims,proof,rpc,recent){
 const base={actor:claims.sub,session_id:claims.session_id,proof,recent_password:recent(claims)};
 if(op==='getRecoveryStatus')return rpc('priperi_recovery',{...base,operation:'status',payload:{}});
 p.requireThat(base.recent_password&&claims.app_metadata?.priperi_auth_version===2,'reauthentication-required','Confirm your current password first.');
 if(op==='revokeRecovery')return rpc('priperi_recovery',{...base,operation:'revoke',payload:{}});
 const id=uuid(data.id);if(op==='finishRecovery')return rpc('priperi_recovery',{...base,operation:'finish',payload:{id}});
 const me=await rpc('priperi_request',{...base,operation:'getAccount',payload:{}}),chain=data.chain,keys=chainKeys(me.uid,me.fingerprint,me.signing,chain),previous=me.delegations||[];
 p.requireThat(chain.length===previous.length+1&&chain.length<=31&&previous.every((v,i)=>v.key===chain[i].key&&v.signature===chain[i].signature));
 p.verifyDeviceSignature(data.signature,chainKeys(me.uid,me.fingerprint,me.signing,previous).at(-1),['priperi-recovery-register-v1',me.uid,id,proof,chainDigest(chain)].join('\n'));
 return rpc('priperi_recovery',{...base,operation:'register',payload:{id,chain}});
}
export async function publicRecovery(op,data,proof,rpc,verifyPassword){
 const base={actor:null,session_id:null,proof,recent_password:false};await rpc('priperi_recovery',{...base,operation:'publicAttempt',payload:{}});
 const id=uuid(data.id),target=data.target;p.requireThat(target&&typeof target==='object');uuid(target.installation);uuid(target.deviceId);p.base64(target.signing,80,160);
 const digest=p.hash(recoveryContext(id,uuid(data.uid),proof,target)),info=await rpc('priperi_recovery',{...base,operation:'info',payload:{id,digest}}),me=info.account;
 p.requireThat(me.uid===data.uid,'permission-denied','This recovery file does not match.');
 p.validateKeys(me.hpke,target.signing);const keys=chainKeys(me.uid,me.fingerprint,me.signing,target.chain);
 p.requireThat(target.chain.length===info.chain.length+1&&info.chain.every((c,i)=>c.key===target.chain[i].key&&c.signature===target.chain[i].signature)&&keys.at(-1)===target.signing);
 const nonce=op==='recoveryChallenge'?'challenge':uuid(data.nonce),context=recoveryContext(id,me.uid,proof,target,nonce);
 p.verifyDeviceSignature(data.recoverySignature,keys.at(-2),context);p.verifyDeviceSignature(data.signature,target.signing,context);
 if(op==='recoveryChallenge')return rpc('priperi_recovery',{...base,operation:'challenge',payload:{id,digest}});
 // Password verification has a separate committed quota; no caller-controlled proof resets it.
 await rpc('priperi_recovery',{...base,operation:'passwordAttempt',payload:{id,digest}});
 const verified=await verifyPassword(me.uid,info.seed,data.password);
 try{return await rpc('priperi_recovery',{...base,operation:'accept',payload:{id,digest,nonce,session:verified.session,target:{proof,installation:target.installation,deviceId:target.deviceId,chain:target.chain}}});}
 finally{await verified.close();}
}
