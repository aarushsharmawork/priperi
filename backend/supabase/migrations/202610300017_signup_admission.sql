-- Signup admission: apply after016 only after configuring the verification widget and matching relay.
-- Existing accounts/messages are preserved. No counter rows are deleted or quotas raised.
BEGIN;
SELECT pg_advisory_xact_lock(774392);
DO $guard$ BEGIN
 IF (SELECT md5(prosrc) FROM pg_proc WHERE oid=to_regprocedure('public.priperi_begin_device(text,text,uuid,text,text,text,uuid,uuid)')) IS DISTINCT FROM 'd7c23ae6cd8b8723d56a4b9620e87c90' THEN RAISE EXCEPTION 'Unexpected installed priperi_begin_device; review001-016 before017'; END IF;
 IF (SELECT md5(prosrc) FROM pg_proc WHERE oid=to_regprocedure('public.priperi_begin(text,text,uuid,text,text,text,uuid)')) IS DISTINCT FROM '3101272646cf170f3acdd95a946977f7' THEN RAISE EXCEPTION 'Unexpected installed priperi_begin; review001-016 before017'; END IF;
 IF (SELECT md5(prosrc) FROM pg_proc WHERE oid=to_regprocedure('public.priperi_replacement(text,uuid,uuid,text,text,jsonb)')) IS DISTINCT FROM 'ba39e95dcaca9544661eea8445329b30' THEN RAISE EXCEPTION 'Unexpected installed priperi_replacement; review001-016 before017'; END IF;
 IF (SELECT md5(prosrc) FROM pg_proc WHERE oid=to_regprocedure('relay.erase_account(uuid)')) IS DISTINCT FROM '3ed67b42a0940612aef88ce370cd6258' THEN RAISE EXCEPTION 'Unexpected installed erase_account; review001-016 before017'; END IF;
 IF (SELECT md5(prosrc) FROM pg_proc WHERE oid=to_regprocedure('relay.sweep()')) IS DISTINCT FROM 'e4474ff5f3c6762d33046946a20df973' THEN RAISE EXCEPTION 'Unexpected installed sweep; review001-016 before017'; END IF;
END $guard$;
CREATE TABLE relay.signup_admissions (
 token_hash text PRIMARY KEY CHECK(token_hash ~ '^[a-f0-9]{64}$'),
 proof text,installation uuid,device_id uuid,name text,fingerprint text,
 expires_at timestamptz NOT NULL,used boolean NOT NULL DEFAULT false
);
ALTER TABLE relay.signup_admissions ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON relay.signup_admissions FROM PUBLIC,anon,authenticated;
CREATE INDEX signup_admissions_lookup ON relay.signup_admissions(proof,installation,name) WHERE NOT used;
CREATE OR REPLACE FUNCTION public.priperi_signup_admit(token_hash text,proof text,installation_id uuid,device_id_value uuid,account_name text,fingerprint_value text)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $admit$
BEGIN
 PERFORM pg_advisory_xact_lock(774392);
 IF token_hash IS NULL OR token_hash !~ '^[a-f0-9]{64}$' OR proof IS NULL OR proof !~ '^[a-f0-9]{64}$'
  OR installation_id IS NULL OR account_name IS NULL OR account_name !~ '^[a-z][a-z0-9_]{2,23}$'
  OR fingerprint_value IS NULL OR fingerprint_value !~ '^[a-f0-9]{64}$' THEN RAISE EXCEPTION 'denied';END IF;
 DELETE FROM relay.signup_admissions WHERE expires_at<=now();
 IF EXISTS(SELECT 1 FROM relay.signup_admissions a WHERE a.token_hash=priperi_signup_admit.token_hash) THEN RETURN '{"admitted":false}'::jsonb;END IF;
 DELETE FROM relay.signup_admissions a WHERE a.proof=priperi_signup_admit.proof AND NOT used;
 INSERT INTO relay.signup_admissions(token_hash,proof,installation,device_id,name,fingerprint,expires_at)
 VALUES(token_hash,proof,installation_id,device_id_value,account_name,fingerprint_value,now()+interval '5 minutes');
 RETURN '{"admitted":true}'::jsonb;
END $admit$;
REVOKE ALL ON FUNCTION public.priperi_signup_admit(text,text,uuid,uuid,text,text) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.priperi_signup_admit(text,text,uuid,uuid,text,text) TO service_role;
CREATE OR REPLACE FUNCTION public.priperi_begin_device(name text, proof text, installation_id uuid, hpke_key text, signing_key text, fingerprint_value text, expected_actor uuid, device_id_value uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
DECLARE result jsonb; me relay.profiles; pending relay.pending_accounts;
BEGIN
 PERFORM pg_advisory_xact_lock(774392);
 IF name IS NOT NULL AND expected_actor IS NULL
 AND NOT EXISTS(SELECT 1 FROM relay.profiles p WHERE p.device_hash=proof OR p.installation=installation_id OR (device_id_value IS NOT NULL AND p.device_id=device_id_value))
 AND NOT EXISTS(SELECT 1 FROM relay.pending_accounts p WHERE p.device_hash=proof OR p.installation=installation_id OR (device_id_value IS NOT NULL AND p.device_id=device_id_value)) THEN
  IF NOT EXISTS(SELECT 1 FROM relay.signup_admissions a WHERE NOT a.used AND a.expires_at>now()
   AND a.proof=priperi_begin_device.proof AND a.installation=installation_id AND a.name=priperi_begin_device.name
   AND a.fingerprint=fingerprint_value AND a.device_id IS NOT DISTINCT FROM device_id_value) THEN RETURN '{"humanRequired":true}'::jsonb;END IF;
 END IF;
 SELECT * INTO me FROM relay.profiles WHERE device_hash=proof AND installation=installation_id AND fingerprint=fingerprint_value;
 IF FOUND AND relay.expire_account(me.uid) THEN RETURN '{"accountExpired":true}'::jsonb;END IF;
 IF NOT relay.charge('system:global','device_login',180,60) THEN RETURN '{"limited":true}'::jsonb; END IF;

 SELECT * INTO pending FROM relay.pending_accounts WHERE device_hash=proof AND installation=installation_id AND fingerprint=fingerprint_value AND device_id=device_id_value;
 IF FOUND AND pending.replaces_uid IS NOT NULL THEN
  IF expected_actor IS NOT NULL OR pending.hpke<>hpke_key OR pending.signing<>signing_key OR pending.replacement_until<=now()
   OR NOT EXISTS(SELECT 1 FROM relay.profiles WHERE uid=pending.replaces_uid AND device_hash=pending.replaces_hash AND device_id=device_id_value)
   OR EXISTS(SELECT 1 FROM relay.transfers WHERE uid=pending.replaces_uid AND ((completed_at IS NULL AND expires_at>now()) OR (completed_at IS NOT NULL AND confirmed_at IS NULL))) THEN RETURN '{"denied":true}'::jsonb;END IF;
  RETURN jsonb_build_object('uid',pending.uid,'pending',true);
 END IF;
 IF device_id_value IS NOT NULL AND EXISTS(SELECT 1 FROM relay.profiles WHERE device_id=device_id_value
  AND (device_hash<>proof OR installation<>installation_id OR fingerprint<>fingerprint_value)) THEN RETURN '{"denied":true}'::jsonb; END IF;
 SELECT * INTO me FROM relay.profiles WHERE device_hash=proof OR installation=installation_id OR (device_id_value IS NOT NULL AND device_id=device_id_value) LIMIT 1;
 IF FOUND THEN
  IF me.device_hash<>proof OR me.installation<>installation_id OR me.fingerprint<>fingerprint_value
   OR (me.device_id IS NOT NULL AND me.device_id IS DISTINCT FROM device_id_value)
   OR (expected_actor IS NOT NULL AND expected_actor<>me.uid) THEN
   RETURN '{"denied":true}'::jsonb; END IF;
  -- Username is discovery-only. The device resolves the real current account.
  name:=me.username;
 ELSE
  IF name IS NULL OR expected_actor IS NOT NULL THEN
   RETURN '{"denied":true}'::jsonb; END IF;
  SELECT * INTO pending FROM relay.pending_accounts WHERE device_hash=proof OR installation=installation_id OR (device_id_value IS NOT NULL AND device_id=device_id_value) LIMIT 1;
  IF FOUND THEN
   IF pending.device_hash<>proof OR pending.installation<>installation_id OR pending.fingerprint<>fingerprint_value
    OR (pending.device_id IS NOT NULL AND pending.device_id IS DISTINCT FROM device_id_value) THEN RETURN '{"denied":true}'::jsonb; END IF;
   name:=pending.username;
  END IF;
 END IF;
 result:=public.priperi_begin(name,proof,installation_id,hpke_key,signing_key,fingerprint_value,expected_actor);
 IF COALESCE((result->>'pending')::boolean,false) THEN
  UPDATE relay.pending_accounts SET device_id=device_id_value WHERE uid=(result->>'uid')::uuid AND (device_id IS NULL OR device_id IS NOT DISTINCT FROM device_id_value);
  IF NOT FOUND THEN RETURN '{"denied":true}'::jsonb; END IF;
 END IF;
 RETURN result;
END $function$
;
CREATE OR REPLACE FUNCTION public.priperi_begin(name text, proof text, installation_id uuid, hpke_key text, signing_key text, fingerprint_value text, expected_actor uuid DEFAULT NULL::uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
DECLARE p relay.profiles; pending relay.pending_accounts;
BEGIN
 PERFORM pg_advisory_xact_lock(774392);
 -- Unverified fresh identities never consume shared signup/login counters or reserve usernames.
 IF expected_actor IS NULL AND NOT EXISTS(SELECT 1 FROM relay.profiles candidate WHERE candidate.username=priperi_begin.name)
 AND NOT EXISTS(SELECT 1 FROM relay.pending_accounts candidate WHERE candidate.username=priperi_begin.name) THEN
  IF NOT EXISTS(SELECT 1 FROM relay.signup_admissions a WHERE NOT a.used AND a.expires_at>now()
   AND a.proof=priperi_begin.proof AND a.installation=installation_id AND a.name=priperi_begin.name AND a.fingerprint=fingerprint_value) THEN
   RETURN '{"humanRequired":true}'::jsonb;
  END IF;
 END IF;

 IF NOT relay.charge('system:global','continue',120,60) OR NOT relay.charge(name,'login',10,600)
  OR NOT relay.charge(proof,'installation',20,600) THEN RETURN jsonb_build_object('limited',true); END IF;
 SELECT * INTO p FROM relay.profiles WHERE username=name;
 IF FOUND THEN
  IF p.device_hash<>proof OR p.installation<>installation_id OR p.fingerprint<>fingerprint_value OR (expected_actor IS NOT NULL AND p.uid<>expected_actor) THEN RETURN jsonb_build_object('denied',true); END IF;
  RETURN jsonb_build_object('uid',p.uid,'pending',false);
 END IF;
 IF expected_actor IS NOT NULL THEN RETURN jsonb_build_object('denied',true); END IF;
 IF EXISTS(SELECT 1 FROM relay.profiles WHERE device_hash=proof OR installation=installation_id) THEN RETURN jsonb_build_object('denied',true); END IF;
 SELECT * INTO pending FROM relay.pending_accounts WHERE username=name;
 IF FOUND THEN
  IF pending.device_hash<>proof OR pending.installation<>installation_id OR pending.fingerprint<>fingerprint_value THEN RETURN jsonb_build_object('denied',true); END IF;
 ELSE
  -- Admission is issued only by the server after provider verification. No shared20-account gate.
  UPDATE relay.signup_admissions a SET used=true,proof=NULL,installation=NULL,device_id=NULL,name=NULL,fingerprint=NULL
   WHERE token_hash=(SELECT token_hash FROM relay.signup_admissions c WHERE NOT c.used AND c.expires_at>now()
    AND c.proof=priperi_begin.proof AND c.installation=installation_id AND c.name=priperi_begin.name AND c.fingerprint=fingerprint_value LIMIT 1);
  IF NOT FOUND THEN RETURN '{"humanRequired":true}'::jsonb;END IF;
  INSERT INTO relay.pending_accounts(username,hpke,signing,fingerprint,device_hash,installation)
   VALUES(name,hpke_key,signing_key,fingerprint_value,proof,installation_id) RETURNING * INTO pending;
 END IF;
 RETURN jsonb_build_object('uid',pending.uid,'pending',true);
END $function$
;
CREATE OR REPLACE FUNCTION public.priperi_replacement(proof text, installation_id uuid, device_id_value uuid, fingerprint_value text, action text, payload jsonb DEFAULT '{}'::jsonb)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
DECLARE old relay.profiles; pending relay.pending_accounts; result jsonb;
BEGIN
 PERFORM pg_advisory_xact_lock(774392);
 IF proof IS NULL OR proof !~ '^[a-f0-9]{64}$' OR fingerprint_value IS NULL OR fingerprint_value !~ '^[a-f0-9]{64}$' OR installation_id IS NULL OR device_id_value IS NULL THEN RAISE EXCEPTION 'denied';END IF;
 IF action='close' THEN
  DELETE FROM auth.sessions WHERE id=(payload->>'session')::uuid AND user_id=(payload->>'uid')::uuid;
  RETURN '{"ok":true}'::jsonb;
 END IF;
 IF action='info' THEN
  IF NOT relay.charge('system:global','replacement',30,600) OR NOT relay.charge(device_id_value::text,'replacement',5,3600) THEN RETURN '{"limited":true}'::jsonb;END IF;
 ELSIF action<>'prepare' THEN RAISE EXCEPTION 'denied';END IF;
 SELECT * INTO old FROM relay.profiles WHERE device_id=device_id_value;
 IF NOT FOUND OR old.device_hash=proof OR old.installation=installation_id OR old.fingerprint=fingerprint_value OR old.auth_seed IS NULL
  OR EXISTS(SELECT 1 FROM relay.profiles WHERE device_hash=proof OR installation=installation_id)
  OR EXISTS(SELECT 1 FROM relay.transfers WHERE uid=old.uid AND ((completed_at IS NULL AND expires_at>now()) OR (completed_at IS NOT NULL AND confirmed_at IS NULL))) THEN RETURN '{"denied":true}'::jsonb;END IF;
 IF action='info' THEN RETURN jsonb_build_object('uid',old.uid,'seed',old.auth_seed);END IF;
 -- The Edge verifies this fresh password JWT's signature/issuer/audience/version.
 -- SQL independently checks its live session and the exact old binding again.
 IF old.uid IS DISTINCT FROM (payload->>'uid')::uuid OR old.auth_seed IS DISTINCT FROM payload->>'seed'
  OR NOT EXISTS(SELECT 1 FROM auth.sessions s JOIN auth.users u ON u.id=s.user_id WHERE s.id=(payload->>'session')::uuid AND s.user_id=old.uid AND u.deleted_at IS NULL AND (u.banned_until IS NULL OR u.banned_until<=now())) THEN RAISE EXCEPTION 'denied';END IF;
 IF EXISTS(SELECT 1 FROM relay.profiles WHERE username=payload->>'username') OR EXISTS(SELECT 1 FROM relay.pending_accounts WHERE username=payload->>'username' AND (device_hash<>proof OR installation<>installation_id)) THEN RETURN '{"nameTaken":true}'::jsonb;END IF;
 SELECT * INTO pending FROM relay.pending_accounts WHERE device_hash=proof OR installation=installation_id OR device_id=device_id_value LIMIT 1;
 IF FOUND AND (pending.device_hash<>proof OR pending.installation<>installation_id OR pending.fingerprint<>fingerprint_value OR pending.username<>payload->>'username') THEN RETURN '{"denied":true}'::jsonb;END IF;
 result:=public.priperi_begin(payload->>'username',proof,installation_id,payload->>'hpke',payload->>'signing',fingerprint_value,NULL);
 IF COALESCE((result->>'humanRequired')::boolean,false) THEN RETURN result;END IF;
 IF COALESCE((result->>'pending')::boolean,false) THEN
  UPDATE relay.pending_accounts SET device_id=device_id_value,replaces_uid=old.uid,replaces_hash=old.device_hash,replacement_until=now()+interval '5 minutes' WHERE uid=(result->>'uid')::uuid AND device_hash=proof;
  IF NOT FOUND THEN RAISE EXCEPTION 'denied';END IF;
 END IF;
 RETURN jsonb_build_object('prepared',COALESCE((result->>'pending')::boolean,false));
END $function$
;
CREATE OR REPLACE FUNCTION relay.erase_account(actor uuid)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
DECLARE me relay.profiles; owned uuid[];
BEGIN
 PERFORM pg_advisory_xact_lock(774392);
 SELECT * INTO me FROM relay.profiles WHERE uid=actor;
 IF NOT FOUND THEN RAISE EXCEPTION 'denied'; END IF;
 DELETE FROM relay.signup_admissions WHERE proof=me.device_hash OR device_id=me.device_id;
 SELECT array_agg(p.uid) INTO owned FROM relay.pending_accounts p WHERE
  (p.device_hash=me.device_hash OR (p.installation=me.installation AND p.fingerprint=me.fingerprint)
   OR (me.device_id IS NOT NULL AND p.device_id=me.device_id AND p.fingerprint=me.fingerprint))
  AND NOT EXISTS(SELECT 1 FROM relay.profiles other WHERE other.uid=p.uid);
 DELETE FROM relay.rates WHERE subject IN(SELECT lookup_hash FROM relay.transfers WHERE uid=actor);
 DELETE FROM relay.rates WHERE subject IN(actor::text,me.device_hash) OR (me.device_id IS NOT NULL AND subject=me.device_id::text AND kind='replacement')
  OR (kind='login' AND (subject=me.username
   OR subject IN(SELECT p.username FROM relay.pending_accounts p WHERE p.uid=ANY(owned))))
  OR subject IN(SELECT unnest(owned)::text);
 DELETE FROM relay.pending_accounts WHERE uid=actor OR uid=ANY(owned);
 DELETE FROM auth.users WHERE id=ANY(owned);
 DELETE FROM auth.users WHERE id=actor;
END $function$
;
CREATE OR REPLACE FUNCTION relay.sweep()
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
DECLARE actor uuid;
BEGIN
 PERFORM pg_advisory_xact_lock(774392);
 DELETE FROM relay.signup_admissions WHERE expires_at<=now();
 PERFORM relay.sweep_v271();
 UPDATE relay.profiles SET move_nonce=NULL,move_nonce_until=NULL,move_lookup=NULL WHERE move_nonce_until<=now();
 FOR actor IN SELECT uid FROM relay.profiles WHERE idle_days>0 AND active_at+make_interval(days=>idle_days)<=now() LOOP PERFORM relay.erase_account(actor);END LOOP;
 DELETE FROM relay.transfers WHERE completed_at IS NULL AND expires_at<=now();
END $function$
;
COMMIT;
