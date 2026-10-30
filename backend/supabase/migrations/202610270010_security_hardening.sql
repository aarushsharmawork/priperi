-- Apply once after009. Security hardening only; preserve all profiles, messages and receipts.
BEGIN;
SELECT pg_advisory_xact_lock(774392);
-- 'global' is a valid username. System limiter subjects must never share that namespace.
-- Move only known system kinds; a real user's 'global'/'login' counter is left in place.
INSERT INTO relay.rates AS target(subject,kind,started_at,count)
 SELECT 'system:global',kind,started_at,count FROM relay.rates
 WHERE subject='global' AND kind IN ('continue','signup','device','device_login')
 ON CONFLICT(subject,kind) DO UPDATE SET
  started_at=greatest(target.started_at,EXCLUDED.started_at),
  count=least(1000000,target.count::bigint+EXCLUDED.count::bigint)::integer;
DELETE FROM relay.rates WHERE subject='global' AND kind IN ('continue','signup','device','device_login');

CREATE OR REPLACE FUNCTION public.priperi_begin(name text,proof text,installation_id uuid,hpke_key text,signing_key text,fingerprint_value text,expected_actor uuid DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE p relay.profiles; pending relay.pending_accounts;
BEGIN
 PERFORM pg_advisory_xact_lock(774392);
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
  IF NOT relay.charge('system:global','signup',20,3600) THEN RETURN jsonb_build_object('limited',true); END IF;
  INSERT INTO relay.pending_accounts(username,hpke,signing,fingerprint,device_hash,installation)
   VALUES(name,hpke_key,signing_key,fingerprint_value,proof,installation_id) RETURNING * INTO pending;
 END IF;
 RETURN jsonb_build_object('uid',pending.uid,'pending',true);
END $$;

CREATE OR REPLACE FUNCTION public.priperi_device(proof text,installation_id uuid,fingerprint_value text,device_id_value uuid,action text,payload jsonb DEFAULT '{}'::jsonb)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE me relay.profiles; pending relay.pending_accounts; challenge relay.reset_challenges; actor uuid;
BEGIN
 PERFORM pg_advisory_xact_lock(774392);
 IF action NOT IN ('status','resetChallenge','resetAccount','bind') THEN RAISE EXCEPTION 'denied'; END IF;
 -- A retry after confirmed reset can complete without retaining a tombstone.
 IF action='resetAccount' THEN
  actor:=(payload->>'uid')::uuid;
  IF NOT EXISTS(SELECT 1 FROM auth.users WHERE id=actor) AND NOT EXISTS(SELECT 1 FROM relay.profiles WHERE uid=actor)
   AND NOT EXISTS(SELECT 1 FROM relay.profiles WHERE installation=installation_id OR device_hash=proof OR (device_id_value IS NOT NULL AND device_id=device_id_value))
   THEN RETURN '{"status":"deleted"}'::jsonb; END IF;
 END IF;
 IF action<>'bind' AND NOT relay.charge('system:global','device',180,60) THEN RETURN '{"limited":true}'::jsonb; END IF;
 IF device_id_value IS NOT NULL AND EXISTS(SELECT 1 FROM relay.profiles WHERE device_id=device_id_value
  AND (device_hash<>proof OR installation<>installation_id OR fingerprint<>fingerprint_value)) THEN RETURN '{"denied":true}'::jsonb; END IF;
 SELECT * INTO me FROM relay.profiles WHERE installation=installation_id OR device_hash=proof OR (device_id_value IS NOT NULL AND device_id=device_id_value) LIMIT 1;
 IF FOUND THEN
  IF me.installation<>installation_id OR me.device_hash<>proof OR me.fingerprint<>fingerprint_value
   OR (me.device_id IS NOT NULL AND me.device_id IS DISTINCT FROM device_id_value) THEN RETURN '{"denied":true}'::jsonb; END IF;
  IF action<>'bind' AND NOT relay.charge(proof,CASE WHEN action='status' THEN 'device_status' ELSE 'device_reset' END,
   CASE WHEN action='status' THEN 30 ELSE 8 END,CASE WHEN action='status' THEN 60 ELSE 600 END)
   THEN RETURN '{"limited":true}'::jsonb; END IF;
  IF action='status' THEN RETURN jsonb_build_object('registered',true,'uid',me.uid,'username',me.username); END IF;
  IF action='bind' THEN
   IF device_id_value IS NULL OR me.uid<>(payload->>'uid')::uuid THEN RAISE EXCEPTION 'denied'; END IF;
   IF EXISTS(SELECT 1 FROM relay.pending_accounts WHERE device_id=device_id_value AND uid<>me.uid) THEN RAISE EXCEPTION 'denied'; END IF;
   UPDATE relay.profiles SET device_id=device_id_value WHERE uid=me.uid;
   RETURN '{"ok":true}'::jsonb;
  ELSIF action='resetChallenge' THEN
   INSERT INTO relay.reset_challenges(uid,nonce,expires_at) VALUES(me.uid,gen_random_uuid(),now()+interval '5 minutes')
    ON CONFLICT(uid) DO UPDATE SET nonce=EXCLUDED.nonce,expires_at=EXCLUDED.expires_at RETURNING * INTO challenge;
   RETURN jsonb_build_object('uid',me.uid,'nonce',challenge.nonce);
  ELSIF action='resetAccount' THEN
   IF actor<>me.uid THEN RAISE EXCEPTION 'denied'; END IF;
   SELECT * INTO challenge FROM relay.reset_challenges WHERE uid=me.uid AND nonce=(payload->>'nonce')::uuid AND expires_at>now();
   IF NOT FOUND THEN RAISE EXCEPTION 'denied'; END IF;
   -- Auth FK cascades remove sessions, queued messages, receipts, blocks and challenge.
   PERFORM relay.erase_account(me.uid);
   RETURN '{"status":"deleted"}'::jsonb;
  END IF;
 END IF;
 IF action<>'status' THEN RETURN '{"denied":true}'::jsonb; END IF;
 SELECT * INTO pending FROM relay.pending_accounts WHERE installation=installation_id OR device_hash=proof OR (device_id_value IS NOT NULL AND device_id=device_id_value) LIMIT 1;
 IF FOUND THEN
  IF pending.installation<>installation_id OR pending.device_hash<>proof OR pending.fingerprint<>fingerprint_value
   OR (pending.device_id IS NOT NULL AND pending.device_id IS DISTINCT FROM device_id_value) THEN RETURN '{"denied":true}'::jsonb; END IF;
  IF NOT relay.charge(proof,'device_status',30,60) THEN RETURN '{"limited":true}'::jsonb; END IF;
  RETURN jsonb_build_object('registered',false,'pendingUsername',pending.username);
 END IF;
 RETURN '{"registered":false}'::jsonb;
END $$;

CREATE OR REPLACE FUNCTION public.priperi_begin_device(name text,proof text,installation_id uuid,hpke_key text,signing_key text,fingerprint_value text,expected_actor uuid,device_id_value uuid)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE result jsonb; me relay.profiles; pending relay.pending_accounts;
BEGIN
 PERFORM pg_advisory_xact_lock(774392);
 IF NOT relay.charge('system:global','device_login',180,60) THEN RETURN '{"limited":true}'::jsonb; END IF;
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
END $$;

CREATE OR REPLACE FUNCTION relay.erase_account(actor uuid) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE me relay.profiles; owned uuid[];
BEGIN
 PERFORM pg_advisory_xact_lock(774392);
 SELECT * INTO me FROM relay.profiles WHERE uid=actor;
 IF NOT FOUND THEN RAISE EXCEPTION 'denied'; END IF;
 SELECT array_agg(p.uid) INTO owned FROM relay.pending_accounts p WHERE
  (p.device_hash=me.device_hash OR (p.installation=me.installation AND p.fingerprint=me.fingerprint)
   OR (me.device_id IS NOT NULL AND p.device_id=me.device_id AND p.fingerprint=me.fingerprint))
  AND NOT EXISTS(SELECT 1 FROM relay.profiles other WHERE other.uid=p.uid);
 DELETE FROM relay.rates WHERE subject IN(actor::text,me.device_hash)
  OR (kind='login' AND (subject=me.username
   OR subject IN(SELECT p.username FROM relay.pending_accounts p WHERE p.uid=ANY(owned))))
  OR subject IN(SELECT unnest(owned)::text);
 DELETE FROM relay.pending_accounts WHERE uid=actor OR uid=ANY(owned);
 DELETE FROM auth.users WHERE id=ANY(owned);
 DELETE FROM auth.users WHERE id=actor;
END $$;

CREATE OR REPLACE FUNCTION public.priperi_request(actor uuid,session_id uuid,proof text,operation text,payload jsonb,recent_password boolean DEFAULT false)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE me relay.profiles; peer relay.profiles; m jsonb; item relay.messages; receipt relay.receipts; peer_id uuid; message_id uuid; output jsonb; pending_id uuid; permission relay.contact_permissions; message_expiry timestamptz;
BEGIN
 -- One brief transaction serializes relay writes, including deletion, send and block.
 PERFORM pg_advisory_xact_lock(774392);
 -- Lost deletion responses can be retried with the already verified, recent
 -- password JWT. No identifier, tombstone or secret needs to remain afterward.
 IF operation='deleteAccount' AND recent_password AND NOT EXISTS(SELECT 1 FROM auth.users WHERE id=actor)
  AND NOT EXISTS(SELECT 1 FROM relay.profiles WHERE uid=actor) THEN RETURN '{"ok":true,"status":"deleted"}'::jsonb; END IF;
 IF NOT EXISTS(SELECT 1 FROM auth.sessions s JOIN auth.users u ON u.id=s.user_id WHERE s.id=session_id AND u.id=actor
  AND u.deleted_at IS NULL AND (u.banned_until IS NULL OR u.banned_until<=now())) THEN RAISE EXCEPTION 'denied'; END IF;
 SELECT * INTO me FROM relay.profiles WHERE uid=actor AND device_hash=proof;
 IF NOT FOUND THEN RAISE EXCEPTION 'denied'; END IF;
 IF operation='getAccount' THEN RETURN relay.profile(me);
 ELSIF operation='lookupUser' THEN
  SELECT * INTO peer FROM relay.profiles WHERE uid<>actor AND
   ((payload ? 'username' AND username=payload->>'username') OR (NOT payload ? 'username' AND uid=(payload->>'uid')::uuid));
  IF NOT FOUND OR EXISTS(SELECT 1 FROM relay.blocked WHERE owner=peer.uid AND relay.blocked.target=actor) THEN RAISE EXCEPTION 'not_found'; END IF;
  RETURN relay.profile(peer)||jsonb_build_object('contactState',relay.contact_state(actor,peer.uid));
 ELSIF operation='approveContact' THEN
  peer_id:=(payload->>'uid')::uuid;
  IF peer_id=actor OR NOT EXISTS(SELECT 1 FROM relay.profiles WHERE uid=peer_id)
   OR EXISTS(SELECT 1 FROM relay.blocked WHERE (owner=actor AND target=peer_id) OR (owner=peer_id AND target=actor)) THEN RAISE EXCEPTION 'not_found'; END IF;
  UPDATE relay.contact_permissions SET approved=true WHERE sender=peer_id AND recipient=actor AND sent_count>0;
  IF NOT FOUND THEN RAISE EXCEPTION 'no_contact_request'; END IF;
  RETURN relay.contact_state(actor,peer_id);
 ELSIF operation='renameAccount' THEN
  IF EXISTS(SELECT 1 FROM relay.pending_accounts WHERE username=payload->>'username') THEN RAISE EXCEPTION 'name_taken'; END IF;
  IF me.username<>payload->>'username' THEN DELETE FROM relay.rates WHERE subject=me.username; END IF;
  UPDATE relay.profiles SET username=payload->>'username' WHERE uid=actor; RETURN jsonb_build_object('username',payload->>'username');
 ELSIF operation='setNotificationToken' THEN
  UPDATE relay.profiles SET fcm_token=payload->>'token' WHERE uid=actor; RETURN '{"ok":true}'::jsonb;
 ELSIF operation='setBlocked' THEN
  peer_id:=(payload->>'uid')::uuid;
  IF peer_id=actor THEN RAISE EXCEPTION 'denied'; END IF;
  IF (payload->>'blocked')::boolean THEN
   INSERT INTO relay.blocked(owner,target) VALUES(actor,peer_id) ON CONFLICT DO NOTHING;
   DELETE FROM relay.messages WHERE (sender=actor AND recipient=peer_id) OR (sender=peer_id AND recipient=actor);
  ELSE DELETE FROM relay.blocked WHERE owner=actor AND relay.blocked.target=peer_id; END IF;
  RETURN '{"ok":true}'::jsonb;
 ELSIF operation='sendMessage' THEN
  m:=payload->'message'; peer_id:=(m->>'recipient')::uuid; message_id:=(m->>'id')::uuid;
  SELECT * INTO peer FROM relay.profiles WHERE uid=peer_id;
  IF NOT FOUND OR peer_id=actor OR (m->>'sender')::uuid<>actor OR m->>'senderFingerprint'<>me.fingerprint OR m->>'recipientFingerprint'<>peer.fingerprint
   OR EXISTS(SELECT 1 FROM relay.blocked WHERE (owner=actor AND relay.blocked.target=peer_id) OR (owner=peer_id AND relay.blocked.target=actor)) THEN RAISE EXCEPTION 'denied'; END IF;
  SELECT * INTO receipt FROM relay.receipts WHERE id=message_id;
  IF FOUND THEN
   IF receipt.sender<>actor OR receipt.recipient<>peer_id OR receipt.digest<>payload->>'digest' THEN RAISE EXCEPTION 'conflict'; END IF;
   RETURN jsonb_build_object('state','delivered','contactState',relay.contact_state(actor,peer_id));
  END IF;
  SELECT * INTO item FROM relay.messages WHERE id=message_id;
  IF FOUND THEN
   IF item.sender<>actor OR item.recipient<>peer_id OR item.digest<>payload->>'digest' THEN RAISE EXCEPTION 'conflict'; END IF;
   RETURN jsonb_build_object('state','sent','contactState',relay.contact_state(actor,peer_id));
  END IF;
  -- Validate again at the transaction boundary, after Edge verification/network delay.
  -- Exact already-accepted retries above retain their original idempotent result.
  message_expiry:=to_timestamp(((m->>'sentAt')::bigint+604800000)/1000.0);
  IF message_expiry IS NULL OR message_expiry<=now() OR message_expiry>now()+interval '7 days 1 minute' THEN RAISE EXCEPTION 'denied'; END IF;
  SELECT * INTO permission FROM relay.contact_permissions WHERE sender=actor AND recipient=peer_id;
  IF FOUND AND NOT permission.approved AND permission.sent_count>=3 THEN RAISE EXCEPTION 'contact_approval_required'; END IF;
  IF (SELECT count(*) FROM relay.messages WHERE recipient=peer_id)>=500 THEN RAISE EXCEPTION 'inbox_full'; END IF;
  INSERT INTO relay.messages(id,sender,recipient,envelope,digest,expires_at)
   VALUES(message_id,actor,peer_id,m,payload->>'digest',message_expiry);
  INSERT INTO relay.contact_permissions AS c(sender,recipient,sent_count) VALUES(actor,peer_id,1)
   ON CONFLICT(sender,recipient) DO UPDATE SET sent_count=least(c.sent_count+1,3);
  RETURN jsonb_build_object('state','sent','notify',peer.fcm_token,'contactState',relay.contact_state(actor,peer_id));
 ELSIF operation='syncInbox' THEN
  SELECT jsonb_build_object('messages',COALESCE((SELECT jsonb_agg(x.envelope) FROM
   (SELECT envelope FROM relay.messages WHERE recipient=actor AND expires_at>now() ORDER BY expires_at,id LIMIT 50) x),'[]'::jsonb),
   'receipts',COALESCE((SELECT jsonb_agg(jsonb_build_object('id',id,'recipient',recipient)) FROM relay.receipts
   WHERE sender=actor AND expires_at>now() AND id::text IN (SELECT jsonb_array_elements_text(payload->'receiptIds'))),'[]'::jsonb)) INTO output;
  RETURN output||jsonb_build_object('contactStates',COALESCE((SELECT jsonb_agg(relay.contact_state(actor,x.uid)) FROM
   (SELECT value::uuid AS uid FROM jsonb_array_elements_text(COALESCE(payload->'contactIds','[]'::jsonb))
    UNION SELECT (value->>'sender')::uuid FROM jsonb_array_elements(output->'messages')) x),'[]'::jsonb));
 ELSIF operation='acknowledgeMessage' THEN
  message_id:=(payload->>'id')::uuid; SELECT * INTO item FROM relay.messages WHERE id=message_id;
  IF FOUND THEN
   IF item.recipient<>actor THEN RAISE EXCEPTION 'denied'; END IF;
   INSERT INTO relay.receipts(id,sender,recipient,digest,expires_at) VALUES(item.id,item.sender,item.recipient,item.digest,item.expires_at) ON CONFLICT DO NOTHING;
   DELETE FROM relay.messages WHERE id=message_id;
  END IF;
  RETURN '{"ok":true}'::jsonb;
 ELSIF operation='deleteAccount' THEN
  IF NOT recent_password THEN RAISE EXCEPTION 'recent_password_required'; END IF;
  -- Auth, sessions, profile, both sides' queued messages, receipts and block
  -- references disappear atomically through foreign-key cascades.
  PERFORM relay.erase_account(actor);
  RETURN '{"ok":true,"status":"deleted"}'::jsonb;
 END IF;
 RAISE EXCEPTION 'invalid_operation';
END $$;

-- Existing private helper and RPC boundaries are retained; no new client grant or public endpoint.
REVOKE ALL ON FUNCTION relay.erase_account(uuid) FROM PUBLIC,anon,authenticated,service_role;
REVOKE ALL ON FUNCTION public.priperi_begin(text,text,uuid,text,text,text,uuid),public.priperi_device(text,uuid,text,uuid,text,jsonb),public.priperi_begin_device(text,text,uuid,text,text,text,uuid,uuid),public.priperi_request(uuid,uuid,text,text,jsonb,boolean) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.priperi_begin(text,text,uuid,text,text,text,uuid),public.priperi_device(text,uuid,text,uuid,text,jsonb),public.priperi_begin_device(text,text,uuid,text,text,text,uuid,uuid),public.priperi_request(uuid,uuid,text,text,jsonb,boolean) TO service_role;
-- Supabase's optional automatic-RLS event trigger is not a client RPC.
-- Keep the trigger/definition/owner intact; remove only unnecessary client execution.
DO $hardening$
BEGIN
 IF EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
  WHERE n.nspname='public' AND p.proname='rls_auto_enable' AND p.pronargs=0
   AND p.prorettype='event_trigger'::regtype AND p.prosecdef) THEN
  REVOKE EXECUTE ON FUNCTION public.rls_auto_enable() FROM PUBLIC,anon,authenticated;
 END IF;
END $hardening$;
NOTIFY pgrst, 'reload schema';
COMMIT;
