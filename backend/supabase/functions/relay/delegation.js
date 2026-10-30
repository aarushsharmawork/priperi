import {createPublicKey,verify} from 'node:crypto';
import {Buffer} from 'node:buffer';
import * as p from './policy.js';
export function delegationContext(uid,fingerprint,parent,key){return ['priperi-key-delegation-v1',uid,fingerprint,parent,key].join('\n');}
export function signingKey(value){const encoded=p.base64(value,80,160);let key,jwk;try{key=createPublicKey({key:encoded,format:'der',type:'spki'});jwk=key.export({format:'jwk'});}catch{p.requireThat(false);}p.requireThat(jwk.kty==='EC'&&jwk.crv==='P-256'&&key.export({format:'der',type:'spki'}).equals(encoded));return key;}
const verifiedChains=new Map();
export function chainKeys(uid,fingerprint,root,chain=[]){
 p.requireThat(Array.isArray(chain)&&chain.length<=32);
 for(const c of chain){p.requireThat(c&&typeof c==='object'&&!Array.isArray(c)&&Object.keys(c).length===2&&Object.hasOwn(c,'key')&&Object.hasOwn(c,'signature'));p.base64(c.key,80,160);p.base64(c.signature,80,144);}
 p.requireThat(typeof uid==='string'&&/^[a-f0-9-]{36}$/.test(uid)&&typeof fingerprint==='string'&&/^[a-f0-9]{64}$/.test(fingerprint));p.base64(root,80,160);
 const cacheKey=p.hash([uid,fingerprint,root,chainDigest(chain)].join('\n'));if(verifiedChains.has(cacheKey))return [...verifiedChains.get(cacheKey)];
 signingKey(root);const keys=[root];
 for(const certificate of chain){
  p.requireThat(certificate&&typeof certificate==='object'&&!Array.isArray(certificate)&&Object.keys(certificate).length===2&&Object.hasOwn(certificate,'key')&&Object.hasOwn(certificate,'signature'));
  signingKey(certificate.key);p.base64(certificate.signature,80,144);p.requireThat(!keys.includes(certificate.key));
  p.requireThat(verify('sha256',Buffer.from(delegationContext(uid,fingerprint,keys.at(-1),certificate.key)),signingKey(keys.at(-1)),Buffer.from(certificate.signature,'base64')),'permission-denied','The transferred security key could not be verified.');keys.push(certificate.key);
 }if(verifiedChains.size>=128)verifiedChains.delete(verifiedChains.keys().next().value);verifiedChains.set(cacheKey,[...keys]);return keys;
}
export function verifyAuthorized(signature,context,profile){
 const chain=profile.delegations||[];p.requireThat(chain.length%2===0);const keys=chainKeys(profile.uid,profile.fingerprint,profile.signing,chain).filter((key,index)=>index%2===0);p.base64(signature,80,144);
 p.requireThat(keys.reverse().some(key=>verify('sha256',Buffer.from(context),signingKey(key),Buffer.from(signature,'base64'))),'permission-denied','The account signature could not be verified.');
}
export function chainDigest(chain){return p.hash(chain.map(c=>c.key+'\n'+c.signature).join('\n'));}
export function acceptContext(lookup,uid,claimHash,proof,installation,deviceId,signing,chain){return ['priperi-transfer-accept-v1',lookup,uid,claimHash,proof,installation,deviceId,signing,chainDigest(chain)].join('\n');}
