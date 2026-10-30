package com.priperi;

import android.content.Intent;
import android.os.Bundle;
import android.text.*;
import android.view.View;
import android.widget.*;
import androidx.lifecycle.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.json.*;
import java.util.*;

/** Admin controls never grant membership directly: the recipient must accept an invitation. */
public final class GroupDetailsActivity extends PrivateActivity {
    private LinearLayout body;private TextView status;private String rendered="";private JSONObject group;private boolean resumed;
    private Model model;private androidx.appcompat.app.AlertDialog dialog;
    public static final class Model extends ViewModel {
        final String creationId=UUID.randomUUID().toString();final MutableLiveData<String> groupId=new MutableLiveData<>();final MutableLiveData<Boolean> busy=new MutableLiveData<>(false);String owner,name="";
        @Override protected void onCleared(){name="";}
    }
    private boolean alive(){return !isFinishing()&&!isDestroyed()&&!environmentBlocked&&resumed;}
    private final SyncCoordinator.Observer updates=message->{if(alive()){if(Session.uid()==null){requireSignIn();return;}error(message);load();}};
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);model=new ViewModelProvider(this,new ViewModelProvider.Factory(){@Override public <T extends ViewModel>T create(Class<T> type){return type.cast(new Model());}}).get(Model.class);
        if(model.owner==null)model.owner=Session.uid();if(!Objects.equals(model.owner,Session.uid())){finish();return;}
        if(getIntent().hasExtra("group"))model.groupId.setValue(getIntent().getStringExtra("group"));
        root=Ui.page(this);root.setVisibility(View.INVISIBLE);Ui.header(this,root,"Group details","Done",v->finish());body=Ui.scroll(this,root);
        model.groupId.observe(this,id->{rendered="";load();});model.busy.observe(this,busy->{if(alive()&&model.groupId.getValue()==null)creation();});
    }
    @Override protected void ready(){resumed=true;if(model.groupId.getValue()==null)creation();else load();SyncCoordinator.attach(updates);}
    @Override protected void onPause(){resumed=false;if(dialog!=null){dialog.dismiss();dialog=null;}super.onPause();SyncCoordinator.detach(updates);}
    private void creation(){
        if(model.groupId.getValue()!=null)return;body.removeAllViews();body.addView(Ui.title(this,"A place for your people",25));Ui.space(this,body,12);body.addView(Ui.text(this,"Invite up to 20 people. They choose whether to join.",15,R.color.muted));Ui.space(this,body,24);
        EditText name=Ui.input(this,body,"Group name",android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});name.setText(model.name);name.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){model.name=s.toString();}public void afterTextChanged(Editable e){}});
        com.google.android.material.button.MaterialButton create=Ui.button(this,"Create group",true,v->{String value=Ui.value(name);if(!GroupRepository.validName(value)){name.setError("Use 1–60 characters without line breaks.");return;}if(Boolean.TRUE.equals(model.busy.getValue()))return;model.busy.setValue(true);Repository.work(this,vault->{GroupRepository.create(vault,model.creationId,value);Repository.sync(vault);return model.creationId;},id->{model.busy.setValue(false);model.groupId.setValue(id);},message->{model.busy.setValue(false);if(alive())error(message);});});create.setEnabled(!Boolean.TRUE.equals(model.busy.getValue()));body.addView(create);status=Ui.status(this,body);
    }
    private void load(){String id=model.groupId.getValue();if(id==null)return;Repository.work(this,v->{JSONObject g=v.group(id);if(g==null)return null;JSONObject copy=new JSONObject(g.toString()).put("_muted",v.muted("group:"+id)).put("_rosterVerified",v.groupVerified(g)).put("_keysVerified",GroupRepository.keysVerified(v,g));JSONArray peers=copy.getJSONArray("members");for(int i=0;i<peers.length();i++)peers.getJSONObject(i).put("_verified",v.isVerified(peers.getJSONObject(i)));return copy;},g->{if(!alive())return;if(g==null){error("Refreshing group…");return;}group=g;if(g.toString().equals(rendered))return;rendered=g.toString();render();},this::error);}
    private void render(){
        body.removeAllViews();LinearLayout hero=Ui.card(this);hero.setGravity(android.view.Gravity.CENTER);hero.addView(Ui.avatar(this,group.optString("name"),76));Ui.space(this,hero,12);TextView title=Ui.title(this,group.optString("name"),25);title.setGravity(android.view.Gravity.CENTER);hero.addView(title);body.addView(hero);Ui.space(this,body,12);JSONArray members=group.optJSONArray("members");boolean active=group.optBoolean("active"),admin=false;try{admin=GroupRepository.admin(group);}catch(JSONException ignored){}
        body.addView(Ui.text(this,active?members.length()+(members.length()==1?" member · Invite only":" members · Invite only"):"You’re no longer a member",14,R.color.muted));Ui.space(this,body,16);
        body.addView(Ui.text(this,"End-to-end encrypted. New members see new messages only.",13,R.color.muted));
        if(active){Ui.space(this,body,12);body.addView(Ui.button(this,"Open group chat",true,v->startActivity(new Intent(this,ChatActivity.class).putExtra("peer","group:"+model.groupId.getValue()))));}
        if(admin){Ui.section(this,body,"Admin controls");Ui.item(this,body,"Change group name","Only admins can change this",v->rename());Ui.item(this,body,"Add members","Search contacts or usernames",v->invite());}
        Ui.section(this,body,"Notifications");Ui.item(this,body,group.optBoolean("_muted")?"Unmute group":"Mute group",group.optBoolean("_muted")?"Alerts are muted":"Choose how long to mute alerts",v->MuteControls.show(this,"group:"+model.groupId.getValue(),()->{rendered="";load();}));
        Ui.section(this,body,"Members");
        for(int i=0;i<members.length();i++){JSONObject peer=members.optJSONObject(i);String uid=peer.optString("uid"),name="@"+peer.optString("username")+(uid.equals(Session.uid())?" (you)":"");boolean canManage=admin&&active;LinearLayout row=Ui.row(this);Ui.pad(this,row,14);row.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(getColor(R.color.soft)),Ui.shape(this,getColor(R.color.surface),16),null));row.addView(Ui.avatar(this,peer.optString("username"),44));LinearLayout words=Ui.column(this);words.setPadding(Ui.dp(this,14),0,Ui.dp(this,8),0);words.addView(Ui.text(this,name,16,R.color.ink));words.addView(Ui.text(this,(peer.optBoolean("admin")?"Admin":"Member")+(uid.equals(Session.uid())?"":peer.optBoolean("_verified")?" · Key checked":" · Check key"),12,R.color.muted));row.addView(words,new LinearLayout.LayoutParams(0,-2,1));row.addView(Ui.symbol(this,"chevron",18,R.color.muted));row.setFocusable(true);row.setFilterTouchesWhenObscured(true);row.setOnClickListener(v->{Ui.tap(row);if(canManage)manage(peer);else key(peer);});body.addView(row);Ui.space(this,body,3);}
        Ui.section(this,body,"On this phone");Ui.item(this,body,"Clear group history","Other members keep their copies",v->confirm("Clear this group’s saved messages?","Messages waiting to send stay in the outbox.",()->Repository.work(this,vault->{vault.clearHistory("group:"+model.groupId.getValue());return true;},ok->error("Group history cleared."),this::error)));
        if(active){Ui.space(this,body,12);boolean alone=members.length()==1;body.addView(Ui.button(this,alone?"Delete group":"Leave group",false,v->{if(alone)deleteEmpty();else confirm("Leave this group?","You’ll need an invitation to rejoin.",()->act("leaveGroup",new JSONObject()));}));}

        status=Ui.status(this,body);
    }
    private void error(String value){if(alive()&&status!=null){status.setText(value);status.setVisibility(value.isEmpty()?View.GONE:View.VISIBLE);}}
    private void confirm(String title,String message,Runnable action){dialog=Ui.dialog(this).setTitle(title).setMessage(message).setNegativeButton("Cancel",null).setPositiveButton("Confirm",(d,w)->{if(alive())action.run();}).show();}
    private void textDialog(String title,String label,String initial,boolean username,java.util.function.Consumer<String> action){
        LinearLayout box=Ui.column(this);Ui.pad(this,box,20);EditText edit=Ui.input(this,box,label,android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(username?24:60)});edit.setText(initial);
        dialog=Ui.dialog(this).setTitle(title).setView(box).setNegativeButton("Cancel",null).setPositiveButton(username?"Find user":"Save",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{String value=Ui.value(edit);if(username?!Validation.username(value):!GroupRepository.validName(value)){edit.setError(username?"Use the full username, without @.":"Use 1–60 characters without line breaks.");return;}dialog.dismiss();action.accept(value);}));dialog.show();
    }
    private void rename(){textDialog("Change group name","Group name",group.optString("name"),false,name->{try{act("renameGroup",new JSONObject().put("name",name));}catch(JSONException e){error("Could not save the name.");}});}
    private void invite(){new MemberPicker(this,group,peer->{if(!alive())return;confirm("Invite @"+peer.optString("username")+"?","They’ll join only if they accept.",()->{try{act("inviteGroupMember",new JSONObject().put("uid",peer.getString("uid")).put("id",UUID.randomUUID().toString()));}catch(JSONException e){error("Could not send the invitation.");}});}).show();}
    private void deleteEmpty(){confirm("Delete this group?","Clears saved messages on this phone. If someone joins first, only you leave; their group stays.",()->{if(Boolean.TRUE.equals(model.busy.getValue()))return;model.busy.setValue(true);String id=model.groupId.getValue();Repository.work(this,v->{JSONObject current=v.group(id);if(!Objects.equals(model.owner,Session.uid())||current==null||!current.optBoolean("active")||current.getJSONArray("members").length()!=1)throw new SecurityException("The group changed. Review its members first.");GroupRepository.action(v,"leaveGroup",new JSONObject().put("groupId",id));v.clearHistory("group:"+id);v.hideGroup(id);Repository.sync(v);return true;},ok->{model.busy.setValue(false);if(alive()){AppNavigation.navigate(this,"groups");finish();}},message->{model.busy.setValue(false);error(message);});});}

    private void manage(JSONObject peer){boolean self=peer.optString("uid").equals(Session.uid());String[] options=self?new String[]{"Security key",group.optJSONArray("members").length()==1?"Delete group":"Leave group"}:new String[]{"Security key",peer.optBoolean("admin")?"Remove admin role":"Make admin","Remove member"};dialog=Ui.dialog(this).setTitle("@"+peer.optString("username")).setItems(options,(d,item)->{if(item==0){key(peer);return;}if(self){if(group.optJSONArray("members").length()==1){deleteEmpty();return;}confirm("Leave this group?","You’ll need an invitation to rejoin.",()->act("leaveGroup",new JSONObject()));return;}String op=item==1?"setGroupAdmin":"removeGroupMember";confirm(options[item]+"?","This changes @"+peer.optString("username")+"’s access to the group.",()->{try{act(op,new JSONObject().put("uid",peer.getString("uid")).put("admin",!peer.optBoolean("admin")));}catch(JSONException e){error("Could not update the member.");}});}).show();}
    private String memberNames(){StringBuilder names=new StringBuilder();JSONArray members=group.optJSONArray("members");for(int i=0;i<members.length();i++){if(i>0)names.append("\n");names.append("@").append(members.optJSONObject(i).optString("username"));}return names.toString();}
    private void key(JSONObject peer){String user=Session.uid();dialog=Ui.dialog(this).setTitle("@"+peer.optString("username")+"’s security key").setMessage("Compare this with their Settings → Security key in person or over a trusted call. Confirm only if every character matches.\n\n"+SettingsActivity.groupKey(peer.optString("fingerprint"))).setNegativeButton("Cancel",null).setPositiveButton("Keys match",(d,w)->Repository.work(this,v->{if(!Objects.equals(user,Session.uid()))throw new SecurityException("Sign in again.");JSONObject current=v.group(model.groupId.getValue()),member=current==null?null:GroupRepository.member(current,peer.optString("uid"));if(member==null||!member.optString("fingerprint").equals(peer.optString("fingerprint")))throw new SecurityException("The member changed. Compare keys again.");v.verifyIdentity(member);return true;},ok->{rendered="";load();},this::error)).show();}

    private void act(String operation,JSONObject payload){
        if(Boolean.TRUE.equals(model.busy.getValue())||!alive())return;String id=model.groupId.getValue();model.busy.setValue(true);
        Repository.work(this,v->{if(!Objects.equals(model.owner,Session.uid()))throw new SecurityException("Sign in again.");payload.put("groupId",id);GroupRepository.action(v,operation,payload);Repository.sync(v);return true;},ok->{model.busy.setValue(false);if(alive()){rendered="";load();error(operation.equals("inviteGroupMember")?"Invitation sent. They must accept before joining.":"Group updated.");}SyncCoordinator.request(getApplicationContext());},message->{model.busy.setValue(false);error(message);SyncCoordinator.request(getApplicationContext());});
    }
}
