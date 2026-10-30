/// <reference path="./runtime.d.ts" />
import { createClient } from 'npm:@supabase/supabase-js@2.118.0';
import { createHandler } from './handler.js';
import { push } from './push.js';
import { signupProtection } from './signup.js';

const projectUrl=Deno.env.get('SUPABASE_URL')!;
const options={auth:{persistSession:false,autoRefreshToken:false,detectSessionInUrl:false}};
const admin=createClient(projectUrl,Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,options);
const authClient=()=>createClient(projectUrl,Deno.env.get('SUPABASE_ANON_KEY')!,options);
const signup=signupProtection({siteKey:Deno.env.get('TURNSTILE_SITE_KEY'),secret:Deno.env.get('TURNSTILE_SECRET_KEY'),projectUrl});
Deno.serve(createHandler({admin,authClient,projectUrl,push,signup,background:promise=>EdgeRuntime.waitUntil(promise)}));
