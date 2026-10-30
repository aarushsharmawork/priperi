-- Upgrade the earlier dashboard-applied schema; no table data is removed.
DROP FUNCTION IF EXISTS public.priperi_begin(text,text,uuid,text,text,text);

CREATE OR REPLACE FUNCTION public.priperi_begin(name text,proof text,installation_id uuid,hpke_key text,signing_key text,fingerprint_value text,expected_actor uuid DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE p relay.profiles; pending relay.pending_accounts;
BEGIN
 PERFORM pg_advisory_xact_lock(774392);
 IF NOT relay.charge('global','continue',120,60) OR NOT relay.charge(name,'login',10,600)
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
  IF NOT relay.charge('global','signup',20,3600) THEN RETURN jsonb_build_object('limited',true); END IF;
  INSERT INTO relay.pending_accounts(username,hpke,signing,fingerprint,device_hash,installation)
   VALUES(name,hpke_key,signing_key,fingerprint_value,proof,installation_id) RETURNING * INTO pending;
 END IF;
 RETURN jsonb_build_object('uid',pending.uid,'pending',true);
END $$;

CREATE OR REPLACE FUNCTION public.priperi_request(actor uuid,session_id uuid,proof text,operation text,payload jsonb,recent_password boolean DEFAULT false)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE me relay.profiles; peer relay.profiles; m jsonb; item relay.messages; receipt relay.receipts; peer_id uuid; message_id uuid; output jsonb; pending_id uuid;
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
  RETURN relay.profile(peer);
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
   RETURN '{"state":"delivered"}'::jsonb;
  END IF;
  SELECT * INTO item FROM relay.messages WHERE id=message_id;
  IF FOUND THEN
   IF item.sender<>actor OR item.recipient<>peer_id OR item.digest<>payload->>'digest' THEN RAISE EXCEPTION 'conflict'; END IF;
   RETURN '{"state":"sent"}'::jsonb;
  END IF;
  IF (SELECT count(*) FROM relay.messages WHERE recipient=peer_id AND expires_at>now())>=500 THEN RAISE EXCEPTION 'inbox_full'; END IF;
  INSERT INTO relay.messages(id,sender,recipient,envelope,digest,expires_at)
   VALUES(message_id,actor,peer_id,m,payload->>'digest',to_timestamp(((m->>'sentAt')::bigint+604800000)/1000.0));
  RETURN jsonb_build_object('state','sent','notify',peer.fcm_token);
 ELSIF operation='syncInbox' THEN
  SELECT jsonb_build_object('messages',COALESCE((SELECT jsonb_agg(x.envelope) FROM
   (SELECT envelope FROM relay.messages WHERE recipient=actor AND expires_at>now() ORDER BY expires_at,id LIMIT 50) x),'[]'::jsonb),
   'receipts',COALESCE((SELECT jsonb_agg(jsonb_build_object('id',id,'recipient',recipient)) FROM relay.receipts
   WHERE sender=actor AND expires_at>now() AND id::text IN (SELECT jsonb_array_elements_text(payload->'receiptIds'))),'[]'::jsonb)) INTO output;
  RETURN output;
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
  DELETE FROM relay.rates WHERE subject IN(actor::text,me.username,me.device_hash);
  DELETE FROM relay.pending_accounts WHERE uid=actor;
  DELETE FROM auth.users WHERE id=actor;
  RETURN '{"ok":true,"status":"deleted"}'::jsonb;
 END IF;
 RAISE EXCEPTION 'invalid_operation';
END $$;

REVOKE ALL ON FUNCTION public.priperi_attempt(uuid,uuid,text,text),public.priperi_begin(text,text,uuid,text,text,text,uuid),public.priperi_finish(uuid,text),public.priperi_request(uuid,uuid,text,text,jsonb,boolean) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.priperi_attempt(uuid,uuid,text,text),public.priperi_begin(text,text,uuid,text,text,text,uuid),public.priperi_finish(uuid,text),public.priperi_request(uuid,uuid,text,text,jsonb,boolean) TO service_role;

NOTIFY pgrst, 'reload schema';
