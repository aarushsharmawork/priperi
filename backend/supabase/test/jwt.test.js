import {test} from 'node:test';
import assert from 'node:assert/strict';
import {generateKeyPairSync,sign,randomUUID} from 'node:crypto';
import {createClient} from '@supabase/supabase-js';
import {createHandler} from '../functions/relay/handler.js';
const encode=v=>Buffer.from(JSON.stringify(v)).toString('base64url');
test('real Supabase SDK verifies JWT signatures and boundary rejects forged issuer and expiry',async()=>{
 const pair=generateKeyPairSync('ec',{namedCurve:'prime256v1'}),jwk={...pair.publicKey.export({format:'jwk'}),kid:'test-key',alg:'ES256',use:'sig'};
 const projectUrl='https://isolated.supabase.co',uid=randomUUID(),sessionId=randomUUID();let calls=0;
 const fetcher=async url=>{assert.ok(String(url).endsWith('/.well-known/jwks.json'));return new Response(JSON.stringify({keys:[jwk]}),{headers:{'Content-Type':'application/json'}});};
 const authClient=()=>createClient(projectUrl,'test-public-key',{auth:{persistSession:false,autoRefreshToken:false},global:{fetch:fetcher}});
 const admin={async rpc(name){calls++;return {data:name==='priperi_attempt'?true:{uid},error:null};}};
 const handler=createHandler({admin,authClient,projectUrl});
 function token(changes={}){const base=encode({alg:'ES256',kid:'test-key',typ:'JWT'})+'.'+encode({iss:projectUrl+'/auth/v1',sub:uid,aud:'authenticated',exp:Math.floor(Date.now()/1000)+3600,iat:Math.floor(Date.now()/1000),session_id:sessionId,role:'authenticated',app_metadata:{priperi_auth_version:1},...changes});return base+'.'+sign('SHA256',Buffer.from(base),{key:pair.privateKey,dsaEncoding:'ieee-p1363'}).toString('base64url');}
 const request=async jwt=>handler(new Request(projectUrl+'/functions/v1/relay',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+jwt},body:JSON.stringify({operation:'getAccount',deviceSecret:'a'.repeat(43)})}));
 assert.equal((await request(token())).status,200);assert.equal(calls,2);
 for(const bad of [token({app_metadata:{}}),token({app_metadata:{priperi_auth_version:0}}),token({iss:'https://attacker.test/auth/v1'}),token({aud:'service_role'}),token({exp:1}),token().split('.').slice(0,2).join('.')+'.'+Buffer.alloc(64).toString('base64url')])assert.equal((await request(bad)).status,401);
 assert.equal(calls,2);
});
