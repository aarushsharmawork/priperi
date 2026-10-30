// Local integration fixture only: real relay/SQL, an in-memory stand-in for
// managed Auth, no credentials, no production connection and no deployment.
import {randomUUID} from 'node:crypto';
import {database} from './database.js';
import {secretHash,hash,PolicyError} from '../functions/relay/policy.js';
import {createHandler} from '../functions/relay/handler.js';
export async function androidServer({signupProtection:protectedSignup=false}={}) {
 const admin=await database({signupProtection:protectedSignup}),records=new Map(),sessions=new Map(),refreshes=new Map(),projectUrl='http://10.0.2.2:5001';
 async function login(record) {
  const id=randomUUID(),token=randomUUID(),refresh=randomUUID(),expires=Math.floor(Date.now()/1000)+3600;
  await admin.db.query('INSERT INTO auth.sessions(id,user_id) VALUES($1,$2)',[id,record.id]);
  sessions.set(token,{app_metadata:record.app_metadata?{...record.app_metadata}:{},sub:record.id,session_id:id,iss:projectUrl+'/auth/v1',aud:'authenticated',exp:expires,amr:[{method:'password',timestamp:Math.floor(Date.now()/1000)}]});
  refreshes.set(refresh,record);return {user:{id:record.id},session:{access_token:token,refresh_token:refresh,expires_at:expires}};
 }
 admin.auth={admin:{async getUserById(id){const record=[...records.values()].find(r=>r.id===id);return record?{data:{user:{id,app_metadata:record.app_metadata}}}:{error:{message:'not found'}};},async createUser({id,email,password,app_metadata}){if(records.has(email))return {error:{code:'email_exists'}};const record={id,email,password,app_metadata};records.set(email,record);await admin.db.query('INSERT INTO auth.users(id,email) VALUES($1,$2)',[id,email]);return {data:{user:{id}}};},async updateUserById(id,{password,app_metadata}){for(const record of records.values())if(record.id===id){record.password=password;if(app_metadata)record.app_metadata=app_metadata;}return {data:{user:{id}}};}}};
 const authClient=()=>({auth:{async getClaims(token){return {data:{claims:sessions.get(token)},error:sessions.has(token)?null:{message:'invalid'}};},async signInWithPassword({email,password}){const record=records.get(email);if(!record||record.password!==password)return {error:{message:'invalid'}};return {data:await login(record)};}}});
 const issued=new Map();
 const signup=protectedSignup?{
  ready(){},
  async verify(token,binding){if(issued.get(token)!==binding)throw new PolicyError('permission-denied','Complete account verification again.');issued.delete(token);return hash(token);},
  page(request){const binding=new URL(request.url).searchParams.get('binding');if(!/^[a-f0-9]{64}$/.test(binding||''))return new Response('Bad binding',{status:400});const token='qa-human-'+randomUUID();issued.set(token,binding);return new Response(JSON.stringify({html:'<html><meta name="viewport" content="width=device-width,initial-scale=1"><body><h2>Local verification fixture</h2><button id="verify" onclick="PriperiCaptcha.postMessage('+JSON.stringify(token).replaceAll('"','&quot;')+')">Verify test signup</button><button id="iframe" onclick="let f=document.createElement(\'iframe\');f.srcdoc=\'<script>PriperiCaptcha.postMessage(&quot;forged-frame-token&quot;)<\\/script>\';document.body.appendChild(f)">Test subframe</button></body></html>'}),{headers:{'Content-Type':'application/json'}});}
 }:null;
 const handler=createHandler({admin,authClient,projectUrl,signup});
 return {db:admin.db,async handle(request){
  const token=(request.headers.get('Authorization')||'').slice(7),path=new URL(request.url).pathname;
  if(process.env.PRIPERI_ACCOUNT_TESTS==='yes'&&path==='/qa/account-expire'){
   const claims=sessions.get(token),data=await request.json();if(!claims||!data.deviceSecret)return Response.json({error:'denied'},{status:403});
   const result=await admin.db.query("UPDATE relay.profiles SET active_at=now()-make_interval(days=>idle_days)-interval '1 second' WHERE uid=$1 AND device_hash=$2 AND idle_days>0 RETURNING uid",[claims.sub,secretHash(data.deviceSecret)]);
   if(!result.rows.length)return Response.json({error:'denied'},{status:403});if(data.sweep===true)await admin.db.exec('SELECT relay.sweep()');return Response.json({ok:true});
  }
  // Fixed synthetic UI data is unrelated to the real integration accounts.
  if(token==='test-only-token')return Response.json({data:{messages:[],receipts:[],state:'sent'}});
  if(path==='/auth/v1/token') {
   const data=await request.json(),record=refreshes.get(data.refresh_token);
   if(!record || !(await admin.db.query('SELECT id FROM auth.users WHERE id=$1',[record.id])).rows.length)return Response.json({error_code:'refresh_token_not_found',msg:'Invalid Refresh Token: Refresh Token Not Found'},{status:400});
   const result=await login(record);return Response.json({...result.session,user:result.user});
  }
  if(path==='/auth/v1/logout') {
   const claims=sessions.get(token);if(claims)await admin.db.query('DELETE FROM auth.sessions WHERE id=$1',[claims.session_id]);sessions.delete(token);return new Response(null,{status:204});
  }
  return handler(request);
 }};
}
