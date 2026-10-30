import {generateKeyPairSync,randomBytes,sign} from 'node:crypto';
import * as p from '../functions/relay/policy.js';
import {body,password,managedPassword} from '../functions/relay/handler.js';
Deno.test('Edge runtime supports the actual key and envelope verification primitives',()=>{
 const keys=generateKeyPairSync('ec',{namedCurve:'prime256v1'}),publicKey=keys.publicKey.export({type:'spki',format:'der'}).toString('base64');
 const hpke=JSON.stringify({primaryKeyId:12,key:[{keyId:12,status:'ENABLED',outputPrefixType:'TINK',keyData:{typeUrl:'type.googleapis.com/google.crypto.tink.HpkePublicKey',keyMaterialType:'ASYMMETRIC_PUBLIC',value:randomBytes(48).toString('base64')}}]});
 if(p.validateKeys(hpke,publicKey).length!==64)throw Error('Fingerprint failed');
 const m={id:crypto.randomUUID(),sender:crypto.randomUUID(),recipient:crypto.randomUUID(),sentAt:Date.now(),senderFingerprint:'a'.repeat(64),recipientFingerprint:'b'.repeat(64),ciphertext:randomBytes(120).toString('base64'),signature:''};
 m.signature=sign('sha256',Buffer.from(p.context(m)+'\n'+m.ciphertext),keys.privateKey).toString('base64');
 p.validateEnvelope(m);p.verifyEnvelope(m,publicKey);
});
Deno.test('Edge runtime bounds JSON input and applies the managed password byte limit',async()=>{
 const result=await body(new Request('https://example.test',{method:'POST',headers:{'Content-Type':'application/json'},body:'{"operation":"continue"}'}));
 if(result.operation!=='continue')throw Error('JSON failed');
 password('Strong-Password-2026!');let rejected=false;
 try{password('Aa1!'+'é'.repeat(35));}catch{rejected=true;}
 if(!rejected)throw Error('Overlong password accepted');
});

Deno.test('Managed credential binds the password to the server-only account binding seed',()=>{
 const first=managedPassword('a'.repeat(43),'Strong-Password-2026!');
 if(first.length!==68||password(first)!==first||first===managedPassword('b'.repeat(43),'Strong-Password-2026!'))throw Error('Device factor failed');
});
Deno.test('Group context and recipient copies use the same Edge signature boundary',()=>{
 const keys=generateKeyPairSync('ec',{namedCurve:'prime256v1'}),publicKey=keys.publicKey.export({type:'spki',format:'der'}).toString('base64');
 const m={id:crypto.randomUUID(),sender:crypto.randomUUID(),recipient:crypto.randomUUID(),sentAt:Date.now(),senderFingerprint:'a'.repeat(64),recipientFingerprint:'b'.repeat(64),group:crypto.randomUUID(),groupVersion:3,ciphertext:randomBytes(120).toString('base64'),signature:''};
 m.signature=sign('sha256',Buffer.from(p.context(m)+'\n'+m.ciphertext),keys.privateKey).toString('base64');p.validateEnvelope(m,Date.now(),true);p.verifyEnvelope(m,publicKey);
 let rejected=false;try{p.verifyEnvelope({...m,groupVersion:4},publicKey);}catch{rejected=true;}if(!rejected)throw Error('Membership epoch not bound');
});

Deno.test('Delegated device signatures work in Edge while transfer-only authorizers cannot sign messages',async()=>{
 const d=await import('../functions/relay/delegation.js');const make=()=>generateKeyPairSync('ec',{namedCurve:'prime256v1'}),root=make(),temporary=make(),phone=make(),pub=(k:ReturnType<typeof make>)=>k.publicKey.export({type:'spki',format:'der'}).toString('base64'),uid=crypto.randomUUID(),fp='a'.repeat(64);
 const certificate=(parent:ReturnType<typeof make>,child:ReturnType<typeof make>)=>({key:pub(child),signature:sign('sha256',Buffer.from(d.delegationContext(uid,fp,pub(parent),pub(child))),parent.privateKey).toString('base64')});const profile={uid,fingerprint:fp,signing:pub(root),delegations:[certificate(root,temporary),certificate(temporary,phone)]};
 d.verifyAuthorized(sign('sha256',Buffer.from('message'),phone.privateKey).toString('base64'),'message',profile);let rejected=false;try{d.verifyAuthorized(sign('sha256',Buffer.from('message'),temporary.privateKey).toString('base64'),'message',profile);}catch{rejected=true;}if(!rejected)throw Error('Transfer authorizer became a message signer');
});

Deno.test('Signup verification uses bounded provider output and exact request binding in Edge',async()=>{
 const {signupProtection,signupBinding}=await import('../functions/relay/signup.js');
 const binding=signupBinding({proof:'a'.repeat(64),installation:crypto.randomUUID(),device:crypto.randomUUID(),name:'alice',fingerprint:'b'.repeat(64)});
 const provider=signupProtection({siteKey:'public-test-sitekey',secret:'server-test-secret',projectUrl:'https://example.supabase.co',fetcher:async()=>Response.json({success:true,hostname:'example.supabase.co',action:'priperi_signup',cdata:binding,challenge_ts:new Date().toISOString()})});
 if((await provider.verify('test-response-token',binding)).length!==64)throw Error('Provider proof failed');
 const page=provider.page(new Request('https://example.supabase.co/functions/v1/relay/signup?binding='+binding));
 if(!page.headers.get('Content-Type')?.startsWith('application/json')||!(await page.json()).html.includes('public-test-sitekey'))throw Error('Native verification API failed');
 let rejected=false;try{await provider.verify('test-response-token','c'.repeat(64));}catch{rejected=true;}if(!rejected)throw Error('Wrong binding accepted');
});
