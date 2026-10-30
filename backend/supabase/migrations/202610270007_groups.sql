-- Apply once after 001-006. Invite-only groups; no direct client grants.
BEGIN;
SELECT pg_advisory_xact_lock(774392);
CREATE TABLE relay.groups (
 id uuid PRIMARY KEY, name text NOT NULL CHECK(char_length(name) BETWEEN 1 AND 60 AND name !~ '[[:cntrl:]]'),
 version integer NOT NULL DEFAULT 1 CHECK(version>0)
);
CREATE TABLE relay.group_members (
 group_id uuid REFERENCES relay.groups(id) ON DELETE CASCADE,
 uid uuid REFERENCES relay.profiles(uid) ON DELETE CASCADE,
 admin boolean NOT NULL DEFAULT false, joined_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(group_id,uid)
);
CREATE INDEX group_members_uid ON relay.group_members(uid);
CREATE TABLE relay.group_invites (
 id uuid PRIMARY KEY, group_id uuid NOT NULL REFERENCES relay.groups(id) ON DELETE CASCADE,
 inviter uuid NOT NULL REFERENCES relay.profiles(uid) ON DELETE CASCADE,
 target uuid NOT NULL REFERENCES relay.profiles(uid) ON DELETE CASCADE,
 expires_at timestamptz NOT NULL DEFAULT now()+interval '7 days', UNIQUE(group_id,target), CHECK(inviter<>target)
);
CREATE INDEX group_invites_target ON relay.group_invites(target);
-- One durable idempotency marker per send, distinct encrypted copy per recipient.
CREATE TABLE relay.group_dispatch (
 id uuid PRIMARY KEY, group_id uuid NOT NULL REFERENCES relay.groups(id) ON DELETE CASCADE,
 sender uuid NOT NULL REFERENCES relay.profiles(uid) ON DELETE CASCADE,
 digest text NOT NULL CHECK(digest ~ '^[0-9a-f]{64}$'), expires_at timestamptz NOT NULL
);
CREATE TABLE relay.group_queue (
 id uuid NOT NULL REFERENCES relay.group_dispatch(id) ON DELETE CASCADE,
 group_id uuid NOT NULL REFERENCES relay.groups(id) ON DELETE CASCADE,
 sender uuid NOT NULL REFERENCES relay.profiles(uid) ON DELETE CASCADE,
 recipient uuid NOT NULL REFERENCES relay.profiles(uid) ON DELETE CASCADE,
 envelope jsonb NOT NULL, expires_at timestamptz NOT NULL,
 PRIMARY KEY(id,recipient), CHECK(sender<>recipient)
);
CREATE INDEX group_queue_recipient ON relay.group_queue(recipient,expires_at);
ALTER TABLE relay.groups ENABLE ROW LEVEL SECURITY;
ALTER TABLE relay.group_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE relay.group_invites ENABLE ROW LEVEL SECURITY;
ALTER TABLE relay.group_dispatch ENABLE ROW LEVEL SECURITY;
ALTER TABLE relay.group_queue ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON relay.groups,relay.group_members,relay.group_invites,relay.group_dispatch,relay.group_queue FROM PUBLIC,anon,authenticated;

CREATE FUNCTION relay.group_snapshot(gid uuid) RETURNS jsonb LANGUAGE sql STABLE SET search_path='' AS $$
 SELECT jsonb_build_object('id',g.id,'name',g.name,'version',g.version,'members',
 COALESCE((SELECT jsonb_agg(relay.profile(p)||jsonb_build_object('admin',m.admin) ORDER BY p.uid)
 FROM relay.group_members m JOIN relay.profiles p ON p.uid=m.uid WHERE m.group_id=g.id),'[]'::jsonb)) FROM relay.groups g WHERE g.id=gid
$$;
-- Removal (including account deletion) invalidates old outboxes, purges queued
-- copies involving that member, and promotes the oldest remaining member if needed.
CREATE FUNCTION relay.group_departure() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(774392);
 IF NOT EXISTS(SELECT 1 FROM relay.groups WHERE id=OLD.group_id) THEN RETURN OLD; END IF;
 DELETE FROM relay.group_queue WHERE group_id=OLD.group_id AND (sender=OLD.uid OR recipient=OLD.uid);
 DELETE FROM relay.group_invites WHERE group_id=OLD.group_id AND inviter=OLD.uid;
 IF NOT EXISTS(SELECT 1 FROM relay.group_members WHERE group_id=OLD.group_id) THEN
  DELETE FROM relay.groups WHERE id=OLD.group_id;
 ELSE
  UPDATE relay.groups SET version=version+1 WHERE id=OLD.group_id;
  IF NOT EXISTS(SELECT 1 FROM relay.group_members WHERE group_id=OLD.group_id AND admin) THEN
   UPDATE relay.group_members SET admin=true WHERE group_id=OLD.group_id AND uid=(SELECT uid FROM relay.group_members WHERE group_id=OLD.group_id ORDER BY joined_at,uid LIMIT 1);
  END IF;
 END IF;
 RETURN OLD;
END $$;
CREATE TRIGGER group_departure AFTER DELETE ON relay.group_members FOR EACH ROW EXECUTE FUNCTION relay.group_departure();
CREATE FUNCTION relay.group_block() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 DELETE FROM relay.group_queue WHERE (sender=NEW.owner AND recipient=NEW.target) OR (sender=NEW.target AND recipient=NEW.owner);
 DELETE FROM relay.group_invites WHERE (inviter=NEW.owner AND target=NEW.target) OR (inviter=NEW.target AND target=NEW.owner);
 RETURN NEW;
END $$;
CREATE TRIGGER group_block AFTER INSERT ON relay.blocked FOR EACH ROW EXECUTE FUNCTION relay.group_block();
CREATE FUNCTION public.priperi_groups(actor uuid,session_id uuid,proof text,operation text,payload jsonb)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE me relay.profiles; gid uuid; target_id uuid; g relay.groups; member relay.group_members; inv relay.group_invites;
 batch jsonb; env jsonb; msgid uuid; existing relay.group_dispatch; output jsonb; tokens jsonb:='[]'::jsonb; exp timestamptz;
BEGIN
 PERFORM pg_advisory_xact_lock(774392);
 IF NOT EXISTS(SELECT 1 FROM auth.sessions s JOIN auth.users u ON u.id=s.user_id WHERE s.id=session_id AND u.id=actor AND u.deleted_at IS NULL AND (u.banned_until IS NULL OR u.banned_until<=now())) THEN RAISE EXCEPTION 'denied'; END IF;
 SELECT * INTO me FROM relay.profiles WHERE uid=actor AND device_hash=proof;
 IF NOT FOUND THEN RAISE EXCEPTION 'denied'; END IF;
 IF operation='syncGroups' THEN
  RETURN jsonb_build_object('groups',COALESCE((SELECT jsonb_agg(relay.group_snapshot(m.group_id) ORDER BY m.group_id) FROM relay.group_members m WHERE m.uid=actor),'[]'::jsonb),
   'invites',COALESCE((SELECT jsonb_agg(jsonb_build_object('id',i.id,'groupId',gr.id,'name',gr.name,'inviter',p.username,'expiresAt',extract(epoch FROM i.expires_at)*1000) ORDER BY i.expires_at)
    FROM relay.group_invites i JOIN relay.groups gr ON gr.id=i.group_id JOIN relay.profiles p ON p.uid=i.inviter
    JOIN relay.group_members a ON a.group_id=i.group_id AND a.uid=i.inviter AND a.admin
    WHERE i.target=actor AND i.expires_at>now() AND NOT EXISTS(SELECT 1 FROM relay.blocked WHERE (owner=actor AND target=i.inviter) OR (owner=i.inviter AND target=actor))),'[]'::jsonb),
   'messages',COALESCE((SELECT jsonb_agg(q.envelope ORDER BY q.expires_at,q.id) FROM
    (SELECT q.* FROM relay.group_queue q WHERE q.recipient=actor AND q.expires_at>now()
     AND EXISTS(SELECT 1 FROM relay.group_members WHERE group_id=q.group_id AND uid=actor)
     AND NOT EXISTS(SELECT 1 FROM relay.blocked WHERE (owner=actor AND target=q.sender) OR (owner=q.sender AND target=actor))
     ORDER BY q.expires_at,q.id LIMIT 25) q),'[]'::jsonb));
 ELSIF operation='ackGroupMessages' THEN
  IF jsonb_typeof(payload->'ids')<>'array' OR jsonb_array_length(payload->'ids') NOT BETWEEN 1 AND 25 THEN RAISE EXCEPTION 'denied'; END IF;
  DELETE FROM relay.group_queue WHERE recipient=actor AND id::text IN (SELECT jsonb_array_elements_text(payload->'ids'));
  RETURN '{"ok":true}'::jsonb;
 ELSIF operation='createGroup' THEN
  IF (SELECT count(*) FROM relay.group_members WHERE uid=actor)>=20 THEN RAISE EXCEPTION 'group_limit'; END IF;
  gid:=(payload->>'id')::uuid;
  SELECT * INTO g FROM relay.groups WHERE id=gid;
  IF FOUND THEN
   IF NOT EXISTS(SELECT 1 FROM relay.group_members WHERE group_id=gid AND uid=actor AND admin) THEN RAISE EXCEPTION 'denied'; END IF;
  ELSE
   INSERT INTO relay.groups(id,name) VALUES(gid,payload->>'name');
   INSERT INTO relay.group_members(group_id,uid,admin) VALUES(gid,actor,true);
  END IF;
  RETURN relay.group_snapshot(gid);
 ELSIF operation IN ('acceptGroupInvite','declineGroupInvite') THEN
  SELECT * INTO inv FROM relay.group_invites WHERE id=(payload->>'id')::uuid AND target=actor AND expires_at>now();
  IF NOT FOUND THEN RAISE EXCEPTION 'invite_unavailable'; END IF;
  IF operation='acceptGroupInvite' THEN
   IF NOT EXISTS(SELECT 1 FROM relay.group_members WHERE group_id=inv.group_id AND uid=inv.inviter AND admin)
    OR EXISTS(SELECT 1 FROM relay.blocked WHERE (owner=actor AND target=inv.inviter) OR (owner=inv.inviter AND target=actor)) THEN RAISE EXCEPTION 'invite_unavailable'; END IF;
   IF (SELECT count(*) FROM relay.group_members WHERE uid=actor)>=20 OR (SELECT count(*) FROM relay.group_members WHERE group_id=inv.group_id)>=20 THEN RAISE EXCEPTION 'group_limit'; END IF;
   INSERT INTO relay.group_members(group_id,uid) VALUES(inv.group_id,actor);
   UPDATE relay.groups SET version=version+1 WHERE id=inv.group_id;
  END IF;
  DELETE FROM relay.group_invites WHERE id=inv.id;
  RETURN '{"ok":true}'::jsonb;
 END IF;
 gid:=(payload->>'groupId')::uuid;
 SELECT * INTO g FROM relay.groups WHERE id=gid;
 SELECT * INTO member FROM relay.group_members WHERE group_id=gid AND uid=actor;
 IF member.uid IS NULL OR g.id IS NULL THEN RAISE EXCEPTION 'denied'; END IF;
 IF operation='leaveGroup' THEN
  DELETE FROM relay.group_members WHERE group_id=gid AND uid=actor;
  RETURN '{"ok":true}'::jsonb;
 ELSIF operation='sendGroupMessage' THEN
  batch:=payload->'messages';msgid:=(batch->0->>'id')::uuid;
  IF jsonb_typeof(batch)<>'array' OR jsonb_array_length(batch) NOT BETWEEN 1 AND 19 THEN RAISE EXCEPTION 'denied'; END IF;
  SELECT * INTO existing FROM relay.group_dispatch WHERE id=msgid;
  IF FOUND THEN
   IF existing.sender<>actor OR existing.group_id<>gid OR existing.digest<>payload->>'digest' THEN RAISE EXCEPTION 'conflict'; END IF;
   RETURN '{"state":"sent"}'::jsonb;
  END IF;
  IF g.version<>(payload->>'version')::integer THEN RAISE EXCEPTION 'group_changed'; END IF;
  IF jsonb_array_length(batch)<>(SELECT count(*)-1 FROM relay.group_members WHERE group_id=gid)
   OR (SELECT count(DISTINCT value->>'recipient') FROM jsonb_array_elements(batch))<>jsonb_array_length(batch) THEN RAISE EXCEPTION 'denied'; END IF;
  exp:=to_timestamp(((batch->0->>'sentAt')::bigint+604800000)/1000.0);
  IF exp<=now() OR exp>now()+interval '7 days 1 minute' THEN RAISE EXCEPTION 'denied'; END IF;
  FOR env IN SELECT value FROM jsonb_array_elements(batch) LOOP
   target_id:=(env->>'recipient')::uuid;
   IF (env->>'id')::uuid<>msgid OR env->>'sender'<>actor::text OR env->>'senderFingerprint'<>me.fingerprint OR env->>'group'<>gid::text
    OR (env->>'groupVersion')::integer<>g.version OR env->>'sentAt'<>batch->0->>'sentAt'
    OR target_id=actor OR NOT EXISTS(SELECT 1 FROM relay.group_members m JOIN relay.profiles p ON p.uid=m.uid WHERE m.group_id=gid AND m.uid=target_id AND p.fingerprint=env->>'recipientFingerprint') THEN RAISE EXCEPTION 'denied'; END IF;
   IF (SELECT count(*) FROM relay.group_queue WHERE recipient=target_id AND expires_at>now())>=500 THEN RAISE EXCEPTION 'inbox_full'; END IF;
  END LOOP;
  IF (SELECT count(*) FROM relay.group_dispatch WHERE sender=actor AND expires_at>now())>=2000
   OR (SELECT count(*) FROM relay.group_dispatch WHERE expires_at>now())>=20000
   OR (SELECT count(*) FROM relay.group_queue WHERE expires_at>now())+jsonb_array_length(batch)>10000 THEN RAISE EXCEPTION 'inbox_full'; END IF;
  INSERT INTO relay.group_dispatch(id,group_id,sender,digest,expires_at) VALUES(msgid,gid,actor,payload->>'digest',exp);
  FOR env IN SELECT value FROM jsonb_array_elements(batch) LOOP
   target_id:=(env->>'recipient')::uuid;
   IF NOT EXISTS(SELECT 1 FROM relay.blocked WHERE (owner=actor AND target=target_id) OR (owner=target_id AND target=actor)) THEN
    INSERT INTO relay.group_queue(id,group_id,sender,recipient,envelope,expires_at) VALUES(msgid,gid,actor,target_id,env,exp);
    tokens:=tokens||COALESCE((SELECT jsonb_build_array(fcm_token) FROM relay.profiles WHERE uid=target_id AND fcm_token IS NOT NULL),'[]'::jsonb);
   END IF;
  END LOOP;
  RETURN jsonb_build_object('state','sent','notifyTokens',tokens);
 END IF;
 IF NOT member.admin THEN RAISE EXCEPTION 'denied'; END IF;
 IF operation='renameGroup' THEN
  UPDATE relay.groups SET name=payload->>'name',version=version+1 WHERE id=gid;
 ELSIF operation='inviteGroupMember' THEN
  target_id:=(payload->>'uid')::uuid;
  IF target_id=actor OR NOT EXISTS(SELECT 1 FROM relay.profiles WHERE uid=target_id)
   OR EXISTS(SELECT 1 FROM relay.group_members WHERE group_id=gid AND uid=target_id)
   OR EXISTS(SELECT 1 FROM relay.blocked WHERE (owner=actor AND target=target_id) OR (owner=target_id AND target=actor)) THEN RAISE EXCEPTION 'denied'; END IF;
  IF (SELECT count(*) FROM relay.group_members WHERE group_id=gid)+(SELECT count(*) FROM relay.group_invites WHERE group_id=gid AND expires_at>now())>=20
   OR (SELECT count(*) FROM relay.group_invites WHERE target=target_id AND expires_at>now())>=50 THEN RAISE EXCEPTION 'group_limit'; END IF;
  DELETE FROM relay.group_invites WHERE group_id=gid AND target=target_id AND expires_at<=now();
  INSERT INTO relay.group_invites(id,group_id,inviter,target) VALUES((payload->>'id')::uuid,gid,actor,target_id) ON CONFLICT(group_id,target) DO NOTHING;
  RETURN jsonb_build_object('ok',true,'notifyTokens',COALESCE((SELECT jsonb_build_array(fcm_token) FROM relay.profiles WHERE uid=target_id AND fcm_token IS NOT NULL),'[]'::jsonb));
 ELSIF operation='removeGroupMember' THEN
  target_id:=(payload->>'uid')::uuid;
  DELETE FROM relay.group_members WHERE group_id=gid AND uid=target_id;
  DELETE FROM relay.group_invites WHERE group_id=gid AND target=target_id;
 ELSIF operation='setGroupAdmin' THEN
  target_id:=(payload->>'uid')::uuid;
  IF NOT (payload->>'admin')::boolean AND EXISTS(SELECT 1 FROM relay.group_members WHERE group_id=gid AND uid=target_id AND admin)
   AND (SELECT count(*) FROM relay.group_members WHERE group_id=gid AND admin)<=1 THEN RAISE EXCEPTION 'last_admin'; END IF;
  UPDATE relay.group_members SET admin=(payload->>'admin')::boolean WHERE group_id=gid AND uid=target_id;
  IF NOT FOUND THEN RAISE EXCEPTION 'denied'; END IF;
  IF NOT (payload->>'admin')::boolean THEN DELETE FROM relay.group_invites WHERE group_id=gid AND inviter=target_id; END IF;
  UPDATE relay.groups SET version=version+1 WHERE id=gid;
 ELSE RAISE EXCEPTION 'invalid_operation'; END IF;
 RETURN '{"ok":true}'::jsonb;
END $$;
-- The existing hourly expiry keeps all temporary group data bounded too.
ALTER FUNCTION relay.sweep() RENAME TO sweep_v24;
CREATE FUNCTION relay.sweep() RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(774392);
 PERFORM relay.sweep_v24();
 DELETE FROM relay.group_dispatch WHERE expires_at<=now();
 DELETE FROM relay.group_invites WHERE expires_at<=now();
END $$;
REVOKE ALL ON FUNCTION relay.group_snapshot(uuid),relay.group_departure(),relay.group_block(),relay.sweep(),relay.sweep_v24() FROM PUBLIC,anon,authenticated,service_role;
REVOKE ALL ON FUNCTION public.priperi_groups(uuid,uuid,text,text,jsonb) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.priperi_groups(uuid,uuid,text,text,jsonb) TO service_role;
NOTIFY pgrst, 'reload schema';
COMMIT;
