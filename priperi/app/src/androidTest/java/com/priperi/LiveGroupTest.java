package com.priperi;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in hosted smoke: only owns three fresh, independently named synthetic accounts. */
@RunWith(AndroidJUnit4.class)
public class LiveGroupTest {
    @Test public void hostedInvitesFanoutEpochAndRemovalRemainPrivate()throws Exception {
        assumeTrue("yes".equals(InstrumentationRegistry.getArguments().getString("livegroups")));
        assertTrue(BuildConfig.ALLOW_TEST_DEVICES);assertEquals("https://YOUR_PROJECT_REF.supabase.co/functions/v1/relay",BuildConfig.BACKEND_URL);
        Context context=ApplicationProvider.getApplicationContext();String suffix=UUID.randomUUID().toString().substring(0,8),password="Qa-Groups-"+UUID.randomUUID()+"!9",gid=UUID.randomUUID().toString();
        Vault[] phones={new Vault(context,"live_group_a_"+suffix),new Vault(context,"live_group_b_"+suffix),new Vault(context,"live_group_c_"+suffix)};String[] names={"qa_ga_"+suffix,"qa_gb_"+suffix,"qa_gc_"+suffix};
        Vault a=phones[0],b=phones[1],c=phones[2];
        try {
            for(int i=0;i<phones.length;i++)Session.continueAccount(phones[i],names[i],password);
            String aid=a.account().getString("uid"),bid=b.account().getString("uid"),cid=c.account().getString("uid");Session.restore(a);GroupRepository.create(a,gid,"Hosted private circle");String ib=UUID.randomUUID().toString(),ic=UUID.randomUUID().toString();
            GroupRepository.action(a,"inviteGroupMember",new JSONObject().put("groupId",gid).put("id",ib).put("uid",bid));
            Session.restore(b);Repository.sync(b);assertTrue(b.groups().isEmpty());assertEquals(1,b.groupInvites().length());
            Session.restore(c);try{GroupRepository.action(c,"acceptGroupInvite",new JSONObject().put("id",ib));fail("Another account accepted an invitation");}catch(Session.BackendException expected){assertEquals(404,expected.status);}
            Session.restore(b);GroupRepository.action(b,"acceptGroupInvite",new JSONObject().put("id",ib));Repository.sync(b);assertTrue(b.group(gid).optBoolean("active"));
            try{GroupRepository.action(b,"renameGroup",new JSONObject().put("groupId",gid).put("name","Takeover"));fail("A member renamed the group");}catch(Session.BackendException expected){assertEquals(403,expected.status);}
            Session.restore(a);Repository.sync(a);TestIdentityTrust.sendGroup(a,gid,"Before the third member joined");Repository.sync(a);
            GroupRepository.action(a,"inviteGroupMember",new JSONObject().put("groupId",gid).put("id",ic).put("uid",cid));JSONObject stale=TestIdentityTrust.sendGroup(a,gid,"Review this old membership before resending");
            Session.restore(c);GroupRepository.action(c,"acceptGroupInvite",new JSONObject().put("id",ic));Repository.sync(c);assertEquals(0,c.messages("group:"+gid).size());
            Session.restore(a);Repository.sync(a);assertEquals("group-changed",a.message(stale.getJSONObject("envelope").getString("id")).optString("state"));
            JSONObject sent=TestIdentityTrust.sendGroup(a,gid,"A separate encrypted copy for each accepted member");JSONArray copies=new JSONArray(sent.getJSONArray("groupDeliveries").toString());assertEquals(2,copies.length());Repository.sync(a);
            Session.restore(b);Repository.sync(b);assertEquals(2,b.messages("group:"+gid).size());assertEquals("A separate encrypted copy for each accepted member",b.messages("group:"+gid).get(1).getString("body"));assertEquals(0,Repository.call(b,"syncInbox",new JSONObject()).getJSONObject("groupData").getJSONArray("messages").length());
            Session.restore(c);Repository.sync(c);assertEquals(1,c.messages("group:"+gid).size());assertEquals("A separate encrypted copy for each accepted member",c.messages("group:"+gid).get(0).getString("body"));
            Session.restore(a);GroupRepository.action(a,"sendGroupMessage",new JSONObject().put("groupId",gid).put("messages",copies));Session.restore(b);Repository.sync(b);assertEquals(2,b.messages("group:"+gid).size());
            Session.restore(a);GroupRepository.action(a,"removeGroupMember",new JSONObject().put("groupId",gid).put("uid",bid));Session.restore(b);Repository.sync(b);assertFalse(b.group(gid).optBoolean("active"));try{GroupRepository.action(b,"renameGroup",new JSONObject().put("groupId",gid).put("name","Removed takeover"));fail("Removed member retained access");}catch(Session.BackendException expected){assertEquals(403,expected.status);}
            Session.restore(c);Repository.call(c,"renameAccount",new JSONObject().put("username",names[2]+"_new"));names[2]+="_new";c.account(c.account().put("username",names[2]));Session.restore(a);Repository.sync(a);assertEquals(names[2],GroupRepository.member(a.group(gid),cid).getString("username"));assertEquals(c.identity().getString("fingerprint"),GroupRepository.member(a.group(gid),cid).getString("fingerprint"));
        } finally {
            Exception failure=null;for(int i=0;i<phones.length;i++)try{Vault phone=phones[i];if(phone.account()!=null){assertEquals(names[i],phone.account().getString("username"));Session.restore(phone);Repository.call(phone,"deleteAccount",new JSONObject());}}catch(Exception error){failure=error;}finally{phones[i].destroy();}Session.forget();if(failure!=null)throw failure;
        }
    }
}
