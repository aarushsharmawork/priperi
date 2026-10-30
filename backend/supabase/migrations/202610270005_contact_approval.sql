-- Apply once after001–004. Counters survive message/receipt expiry; account FK cascades erase them.
BEGIN;
-- Share the relay write lock so a live install cannot miss sends during backfill.
SELECT pg_advisory_xact_lock(774392);
CREATE TABLE relay.contact_permissions (
 sender uuid NOT NULL REFERENCES relay.profiles(uid) ON DELETE CASCADE,
 recipient uuid NOT NULL REFERENCES relay.profiles(uid) ON DELETE CASCADE,
 sent_count integer NOT NULL CHECK(sent_count BETWEEN 1 AND 3),
 approved boolean NOT NULL DEFAULT false,
 PRIMARY KEY(sender,recipient), CHECK(sender<>recipient)
);
CREATE INDEX contact_permissions_recipient ON relay.contact_permissions(recipient);
ALTER TABLE relay.contact_permissions ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON relay.contact_permissions FROM PUBLIC,anon,authenticated;
-- Preserve the known pre-upgrade send count without granting approval on someone's behalf.
INSERT INTO relay.contact_permissions(sender,recipient,sent_count)
 SELECT sender,recipient,least(count(*),3)::integer FROM
 (SELECT id,sender,recipient FROM relay.messages UNION SELECT id,sender,recipient FROM relay.receipts) prior
 GROUP BY sender,recipient;
CREATE FUNCTION relay.contact_state(actor uuid,peer_id uuid) RETURNS jsonb LANGUAGE sql STABLE SET search_path='' AS $$
 SELECT jsonb_build_object('uid',peer_id,
  'sentCount',COALESCE((SELECT sent_count FROM relay.contact_permissions WHERE sender=actor AND recipient=peer_id),0),
  'approved',COALESCE((SELECT approved FROM relay.contact_permissions WHERE sender=actor AND recipient=peer_id),false),
  'incomingPending',EXISTS(SELECT 1 FROM relay.contact_permissions WHERE sender=peer_id AND recipient=actor AND NOT approved))
$$;
REVOKE ALL ON FUNCTION relay.contact_state(uuid,uuid) FROM PUBLIC,anon,authenticated,service_role;
CREATE OR REPLACE FUNCTION public.priperi_request(actor uuid,session_id uuid,proof text,operation text,payload jsonb,recent_password boolean DEFAULT false)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE me relay.profiles; peer relay.profiles; m jsonb; item relay.messages; receipt relay.receipts; peer_id uuid; message_id uuid; output jsonb; pending_id uuid; permission relay.contact_permissions;
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
  SELECT * INTO permission FROM relay.contact_permissions WHERE sender=actor AND recipient=peer_id;
  IF FOUND AND NOT permission.approved AND permission.sent_count>=3 THEN RAISE EXCEPTION 'contact_approval_required'; END IF;
  IF (SELECT count(*) FROM relay.messages WHERE recipient=peer_id AND expires_at>now())>=500 THEN RAISE EXCEPTION 'inbox_full'; END IF;
  INSERT INTO relay.messages(id,sender,recipient,envelope,digest,expires_at)
   VALUES(message_id,actor,peer_id,m,payload->>'digest',to_timestamp(((m->>'sentAt')::bigint+604800000)/1000.0));
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

REVOKE ALL ON FUNCTION public.priperi_request(uuid,uuid,text,text,jsonb,boolean) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.priperi_request(uuid,uuid,text,text,jsonb,boolean) TO service_role;
NOTIFY pgrst, 'reload schema';
COMMIT;
