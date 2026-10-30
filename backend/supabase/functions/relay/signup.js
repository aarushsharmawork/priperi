import * as p from './policy.js';

export function signupBinding({proof,installation,device,name,fingerprint}) {
 return p.hash(['priperi-signup-v1',proof,installation,device||'',name,fingerprint].join('\n'));
}
/** Provider verification stays server-side; no client flag, header or embedded shared secret grants admission. */
export function signupProtection({siteKey,secret,projectUrl,fetcher=fetch,now=()=>Date.now()}) {
 const hostname=new URL(projectUrl).hostname;
 const configured=()=>typeof siteKey==='string'&&/^[A-Za-z0-9_-]{10,100}$/.test(siteKey)&&typeof secret==='string'&&secret.length>=10&&secret.length<=256;
 return {
  ready(){p.requireThat(configured(),'unavailable','New account verification is not configured yet. Existing accounts can still sign in.');},
  async verify(token,binding){
   this.ready();p.text(token,10,2048);p.requireThat(/^[a-f0-9]{64}$/.test(binding));
   let response;
   try{response=await fetcher('https://challenges.cloudflare.com/turnstile/v0/siteverify',{method:'POST',redirect:'error',signal:AbortSignal.timeout(8000),headers:{'Content-Type':'application/json'},body:JSON.stringify({secret,response:token})});}
   catch{throw new p.PolicyError('unavailable','Account verification could not finish. Try again.');}
   p.requireThat(response.ok,'unavailable','Account verification could not finish. Try again.');
   const reader=response.body?.getReader();p.requireThat(reader,'unavailable','Account verification returned an invalid response.');let count=0,parts=[];
   try{for(;;){const r=await reader.read();if(r.done)break;if((count+=r.value.length)>16384)throw new p.PolicyError('unavailable','Invalid verification response.');parts.push(r.value);}}finally{await reader.cancel().catch(()=>{});}
   let result;try{const bytes=new Uint8Array(count);let offset=0;for(const part of parts){bytes.set(part,offset);offset+=part.length;}result=JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes));}catch{throw new p.PolicyError('unavailable','Invalid verification response.');}
   const time=Date.parse(result.challenge_ts);
   p.requireThat(result.success===true&&result.hostname===hostname&&result.action==='priperi_signup'&&result.cdata===binding&&Number.isFinite(time)&&now()-time>=-30000&&now()-time<=300000,'permission-denied','Complete account verification again.');
   return p.hash(token);
  },
  page(request){
   this.ready();const url=new URL(request.url),binding=url.searchParams.get('binding');p.requireThat(binding&&/^[a-f0-9]{64}$/.test(binding));
   const nonce=crypto.randomUUID().replaceAll('-','');
   const html=`<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style nonce="${nonce}">html{color-scheme:light dark;font:16px system-ui}body{margin:0;display:grid;place-items:center;min-height:90vh}main{padding:20px;text-align:center}p{max-width:300px;line-height:1.5}</style></head><body><main><h2>One quick check</h2><p>This helps keep automated signups out of Priperi.</p><div id="check"></div><p id="status" role="status"></p></main><script nonce="${nonce}">function ready(){turnstile.render('#check',{sitekey:${JSON.stringify(siteKey)},action:'priperi_signup',cData:${JSON.stringify(binding)},callback:function(token){if(window.PriperiCaptcha)window.PriperiCaptcha.postMessage(token);else document.getElementById('status').textContent='Return to the app and try again.';},'error-callback':function(){document.getElementById('status').textContent='Could not verify. Close this check and try again.';}})}</script><script nonce="${nonce}" src="https://challenges.cloudflare.com/turnstile/v0/api.js?onload=ready&render=explicit" async defer></script></body></html>`;
   return new Response(JSON.stringify({html}),{headers:{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer','Content-Security-Policy':`default-src 'none'; script-src 'nonce-${nonce}' https://challenges.cloudflare.com; style-src 'nonce-${nonce}'; frame-src https://challenges.cloudflare.com; connect-src https://challenges.cloudflare.com; img-src data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'`}});
  }
 };
}
