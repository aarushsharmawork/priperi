-- No client role can read or mutate these tables, even with a valid Auth JWT.
CREATE SCHEMA IF NOT EXISTS relay;
REVOKE ALL ON SCHEMA relay FROM PUBLIC,anon,authenticated;
CREATE TABLE relay.profiles (
 uid uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
 username text NOT NULL UNIQUE CHECK(username ~ '^[a-z][a-z0-9_]{2,23}$'),
 hpke text NOT NULL CHECK(length(hpke)<=4096), signing text NOT NULL CHECK(length(signing)<=512),
 fingerprint text NOT NULL CHECK(fingerprint ~ '^[a-f0-9]{64}$'), device_hash text NOT NULL UNIQUE CHECK(device_hash ~ '^[a-f0-9]{64}$'),
 installation uuid NOT NULL UNIQUE, fcm_token text CHECK(fcm_token IS NULL OR length(fcm_token)<=4096)
);
CREATE TABLE relay.pending_accounts (
 username text PRIMARY KEY CHECK(username ~ '^[a-z][a-z0-9_]{2,23}$'), uid uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
 hpke text NOT NULL CHECK(length(hpke)<=4096), signing text NOT NULL CHECK(length(signing)<=512),
 fingerprint text NOT NULL CHECK(fingerprint ~ '^[a-f0-9]{64}$'), device_hash text NOT NULL CHECK(device_hash ~ '^[a-f0-9]{64}$'),
 installation uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE relay.blocked (
 owner uuid NOT NULL REFERENCES relay.profiles(uid) ON DELETE CASCADE,
 target uuid NOT NULL REFERENCES relay.profiles(uid) ON DELETE CASCADE,
 PRIMARY KEY(owner,target), CHECK(owner<>target)
);
CREATE INDEX blocked_target ON relay.blocked(target);
CREATE TABLE relay.messages (
 id uuid PRIMARY KEY, sender uuid NOT NULL REFERENCES relay.profiles(uid) ON DELETE CASCADE,
 recipient uuid NOT NULL REFERENCES relay.profiles(uid) ON DELETE CASCADE,
 envelope jsonb NOT NULL CHECK(pg_column_size(envelope)<=32768), digest text NOT NULL CHECK(digest ~ '^[a-f0-9]{64}$'),
 expires_at timestamptz NOT NULL, CHECK(sender<>recipient)
);
CREATE INDEX messages_recipient_expiry ON relay.messages(recipient,expires_at);
CREATE INDEX messages_sender ON relay.messages(sender);
CREATE INDEX messages_expiry ON relay.messages(expires_at);
CREATE TABLE relay.receipts (
 id uuid PRIMARY KEY, sender uuid NOT NULL REFERENCES relay.profiles(uid) ON DELETE CASCADE,
 recipient uuid NOT NULL REFERENCES relay.profiles(uid) ON DELETE CASCADE,
 digest text NOT NULL CHECK(digest ~ '^[a-f0-9]{64}$'),expires_at timestamptz NOT NULL
);
CREATE INDEX receipts_sender_expiry ON relay.receipts(sender,expires_at);
CREATE INDEX receipts_recipient ON relay.receipts(recipient);
CREATE INDEX receipts_expiry ON relay.receipts(expires_at);
CREATE TABLE relay.rates (subject text NOT NULL,kind text NOT NULL,started_at timestamptz NOT NULL,count integer NOT NULL,PRIMARY KEY(subject,kind));
CREATE INDEX rates_expiry ON relay.rates(started_at);
ALTER TABLE relay.profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE relay.pending_accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE relay.blocked ENABLE ROW LEVEL SECURITY;
ALTER TABLE relay.messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE relay.receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE relay.rates ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON ALL TABLES IN SCHEMA relay FROM PUBLIC,anon,authenticated;

CREATE FUNCTION relay.profile(p relay.profiles) RETURNS jsonb LANGUAGE sql IMMUTABLE SET search_path='' AS $$
 SELECT jsonb_build_object('uid',p.uid,'username',p.username,'hpke',p.hpke,'signing',p.signing,'fingerprint',p.fingerprint)
$$;
CREATE FUNCTION relay.charge(who text,rate_kind text,maximum integer,seconds integer) RETURNS boolean LANGUAGE plpgsql SET search_path='' AS $$
DECLARE n integer;
BEGIN
 INSERT INTO relay.rates AS r(subject,kind,started_at,count) VALUES(who,rate_kind,now(),1)
 ON CONFLICT(subject,kind) DO UPDATE SET
 count=CASE WHEN r.started_at<=now()-make_interval(secs=>seconds) THEN 1 ELSE least(r.count+1,1000000) END,
 started_at=CASE WHEN r.started_at<=now()-make_interval(secs=>seconds) THEN now() ELSE r.started_at END
 RETURNING count INTO n;
 RETURN n<=maximum;
END $$;

-- Separate RPC: failed operations also consume their attempt allowance.
CREATE FUNCTION public.priperi_attempt(actor uuid,session_id uuid,proof text,kind text) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE cap integer;
BEGIN
 PERFORM pg_advisory_xact_lock(774392);
 IF NOT EXISTS(SELECT 1 FROM auth.sessions s JOIN auth.users u ON u.id=s.user_id
  JOIN relay.profiles p ON p.uid=u.id WHERE s.id=session_id AND u.id=actor AND p.device_hash=proof
  AND u.deleted_at IS NULL AND (u.banned_until IS NULL OR u.banned_until<=now())) THEN RETURN false; END IF;
 cap:=CASE kind WHEN 'lookup' THEN 20 WHEN 'send' THEN 30 WHEN 'sync' THEN 30 WHEN 'ack' THEN 120 WHEN 'settings' THEN 20 ELSE 180 END;
 RETURN relay.charge(actor::text,'attempts',180,60) AND relay.charge(actor::text,kind,cap,60);
END $$;

CREATE FUNCTION public.priperi_begin(name text,proof text,installation_id uuid,hpke_key text,signing_key text,fingerprint_value text,expected_actor uuid DEFAULT NULL)
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

CREATE FUNCTION public.priperi_finish(actor uuid,proof text) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE p relay.profiles;
BEGIN
 PERFORM pg_advisory_xact_lock(774392);
 INSERT INTO relay.profiles(uid,username,hpke,signing,fingerprint,device_hash,installation)
 SELECT uid,username,hpke,signing,fingerprint,device_hash,installation FROM relay.pending_accounts WHERE uid=actor AND device_hash=proof
 ON CONFLICT(uid) DO NOTHING;
 SELECT * INTO p FROM relay.profiles WHERE uid=actor AND device_hash=proof;
 IF NOT FOUND THEN RAISE EXCEPTION 'denied'; END IF;
 DELETE FROM relay.pending_accounts WHERE uid=actor;
 RETURN relay.profile(p);
END $$;

CREATE FUNCTION public.priperi_request(actor uuid,session_id uuid,proof text,operation text,payload jsonb,recent_password boolean DEFAULT false)
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

CREATE FUNCTION relay.sweep() RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(774392);
 DELETE FROM relay.messages WHERE expires_at<=now(); DELETE FROM relay.receipts WHERE expires_at<=now();
 DELETE FROM relay.rates WHERE started_at<=now()-interval '1 day';
 -- Abandoned creation cannot retain an Auth user indefinitely.
 DELETE FROM auth.users WHERE id IN(SELECT uid FROM relay.pending_accounts WHERE created_at<=now()-interval '1 day');
 DELETE FROM relay.pending_accounts WHERE created_at<=now()-interval '1 day';
END $$;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA relay FROM PUBLIC,anon,authenticated;
REVOKE ALL ON FUNCTION public.priperi_attempt(uuid,uuid,text,text),public.priperi_begin(text,text,uuid,text,text,text,uuid),public.priperi_finish(uuid,text),public.priperi_request(uuid,uuid,text,text,jsonb,boolean) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.priperi_attempt(uuid,uuid,text,text),public.priperi_begin(text,text,uuid,text,text,text,uuid),public.priperi_finish(uuid,text),public.priperi_request(uuid,uuid,text,text,jsonb,boolean) TO service_role;
