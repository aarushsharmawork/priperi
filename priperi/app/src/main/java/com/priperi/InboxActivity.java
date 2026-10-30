package com.priperi;

import android.content.Intent;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.json.*;
import java.util.*;

public final class InboxActivity extends PrivateActivity {
    private EditText search;
    private ImageButton clear;
    private TextView status,searchStatus;
    private MaterialButton unread,start;
    private AppNavigation navigation;private ListView list;
    private ChatAdapter adapter;
    private final List<Row> rows=new ArrayList<>();
    private List<JSONObject> contacts=new ArrayList<>(),messages=new ArrayList<>();
    private JSONArray people=new JSONArray(),invites=new JSONArray();
    private List<JSONObject> groups=new ArrayList<>();
    private boolean groupsOnly;
    private Set<String> pinnedChats=new HashSet<>();
    private final Set<String> responding=new HashSet<>();
    private boolean unreadOnly,loadingPeople;
    private int searchGeneration;
    private String lastPeopleQuery="",remoteError="";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final SyncCoordinator.Observer updates=message->{if(alive()){if(Session.uid()==null){requireSignIn();return;}showStatus(message);load();}};
    private Runnable pendingSearch;
    private androidx.appcompat.app.AlertDialog inviteDialog;
    private boolean alive(){return !isFinishing()&&!isDestroyed()&&!environmentBlocked;}
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);groupsOnly=saved!=null?saved.getBoolean("groups",false):"groups".equals(getIntent().getStringExtra("section"));if(saved!=null)unreadOnly=saved.getBoolean("unreadFilter",false);root=Ui.page(this);root.setVisibility(View.INVISIBLE);
        LinearLayout bar=Ui.row(this);bar.setPadding(Ui.dp(this,20),Ui.dp(this,10),Ui.dp(this,12),Ui.dp(this,6));ImageView brand=Ui.symbol(this,"brand",28,R.color.accent);bar.addView(brand);TextView title=Ui.title(this,"Priperi",26);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);title.setPadding(Ui.dp(this,10),0,0,0);bar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        bar.addView(Ui.iconButton(this,"qr","Add by QR",v->startActivity(new Intent(this,ContactCodeActivity.class))));
        root.addView(bar);
        LinearLayout searchBox=Ui.row(this);searchBox.setPadding(Ui.dp(this,14),0,Ui.dp(this,4),0);searchBox.setBackground(Ui.shape(this,getColor(R.color.surface),24));
        searchBox.addView(Ui.symbol(this,"search",22,R.color.muted));search=new EditText(this);search.setHint("Search");search.setContentDescription("Search chats or usernames");search.setTextSize(16);search.setTextColor(getColor(R.color.ink));search.setHintTextColor(getColor(R.color.muted));search.setBackground(null);search.setSingleLine(true);search.setSaveEnabled(false);search.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI|android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING|android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);search.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);search.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);search.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});search.setPadding(Ui.dp(this,12),Ui.dp(this,14),0,Ui.dp(this,14));searchBox.addView(search,new LinearLayout.LayoutParams(0,-2,1));clear=Ui.iconButton(this,"close","Clear search",v->search.setText(""));clear.setVisibility(View.GONE);searchBox.addView(clear);
        LinearLayout.LayoutParams searchParams=new LinearLayout.LayoutParams(-1,-2);searchParams.setMargins(Ui.dp(this,16),Ui.dp(this,8),Ui.dp(this,16),Ui.dp(this,4));root.addView(searchBox,searchParams);
        LinearLayout filters=Ui.row(this);filters.setPadding(Ui.dp(this,16),0,Ui.dp(this,16),0);unread=Ui.button(this,"Unread",false,v->{unreadOnly=!unreadOnly;queryChanged();});unread.setTextSize(13);unread.setMinHeight(Ui.dp(this,38));unread.setCornerRadius(Ui.dp(this,19));unread.setStrokeWidth(Ui.dp(this,1));unread.setStrokeColor(ColorStateList.valueOf(getColor(R.color.line)));filters.addView(unread,new LinearLayout.LayoutParams(-2,Ui.dp(this,44)));root.addView(filters);

        status=Ui.status(this,root);status.setPadding(Ui.dp(this,20),0,Ui.dp(this,20),0);status.setVisibility(View.GONE);
        searchStatus=Ui.status(this,root);searchStatus.setPadding(Ui.dp(this,20),0,Ui.dp(this,20),Ui.dp(this,6));searchStatus.setVisibility(View.GONE);
        FrameLayout area=new FrameLayout(this);root.addView(area,new LinearLayout.LayoutParams(-1,0,1));list=new ListView(this);list.setDivider(null);list.setClipToPadding(false);list.setPadding(0,0,0,Ui.dp(this,100));list.setCacheColorHint(android.graphics.Color.TRANSPARENT);adapter=new ChatAdapter();list.setAdapter(adapter);area.addView(list,new FrameLayout.LayoutParams(-1,-1));list.setOnItemClickListener((p,v,position,id)->{Row row=rows.get(position);if(row.peer==null)return;if(row.kind==4)invitation(row.peer);else if(row.kind==2)confirmKey(row.peer);else openChat(row.peer.optString("uid"));});
        list.setOnItemLongClickListener((parent,view,position,id)->{Row row=rows.get(position);if(row.kind!=1&&row.kind!=5)return false;chatOptions(row);return true;});
        start=Ui.button(this,groupsOnly?"New group":"New chat",true,v->{if(groupsOnly)startActivity(new Intent(this,GroupDetailsActivity.class));else focusSearch();});start.setIcon(new Icons("edit",getColor(R.color.on_accent)));start.setIconSize(Ui.dp(this,20));start.setIconTint(ColorStateList.valueOf(getColor(R.color.on_accent)));start.setElevation(Ui.dp(this,6));FrameLayout.LayoutParams fab=new FrameLayout.LayoutParams(-2,Ui.dp(this,54),Gravity.BOTTOM|Gravity.END);fab.setMargins(Ui.dp(this,20),0,Ui.dp(this,20),Ui.dp(this,20));area.addView(start,fab);
        navigation=new AppNavigation(this,root,groupsOnly?"groups":"chats",section->{if("settings".equals(section))AppNavigation.navigate(this,section);else selectSection("groups".equals(section));});
        root.getViewTreeObserver().addOnGlobalLayoutListener(()->{androidx.core.view.WindowInsetsCompat insets=androidx.core.view.ViewCompat.getRootWindowInsets(root);boolean keyboard=insets!=null&&insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime());int available=root.getHeight()-root.getPaddingTop()-root.getPaddingBottom();int filterVisibility=!keyboard&&available>=Ui.dp(this,420)?View.VISIBLE:View.GONE;if(filters.getVisibility()!=filterVisibility)filters.setVisibility(filterVisibility);int dockVisibility=keyboard?View.GONE:View.VISIBLE;if(navigation.view.getVisibility()!=dockVisibility)navigation.view.setVisibility(dockVisibility);if(start.getVisibility()!=dockVisibility)start.setVisibility(dockVisibility);int headerVisibility=keyboard&&available<Ui.dp(this,240)?View.GONE:View.VISIBLE;if(bar.getVisibility()!=headerVisibility)bar.setVisibility(headerVisibility);int bottom=Ui.dp(this,keyboard?16:100);if(list.getPaddingBottom()!=bottom)list.setPadding(0,0,0,bottom);});
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){queryChanged();}public void afterTextChanged(Editable value){}});
    }
    @Override protected void onSaveInstanceState(Bundle state){state.putBoolean("groups",groupsOnly);state.putBoolean("unreadFilter",unreadOnly);super.onSaveInstanceState(state);}
    @Override protected void ready(){Notices.dismiss(this);Notices.refreshToken(this);load();SyncCoordinator.attach(updates);}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);selectSection("groups".equals(intent.getStringExtra("section")));}
    private void selectSection(boolean groups){if(groupsOnly==groups&&navigation!=null)return;groupsOnly=groups;unreadOnly=false;if(search!=null){search.setHint(groups?"Search groups":"Search");search.setContentDescription(groups?"Search groups":"Search chats or usernames");search.setText("");}if(start!=null)start.setText(groups?"New group":"New chat");if(navigation!=null)navigation.select(groups?"groups":"chats",true);queryChanged();if(list!=null)Ui.fadeIn(this,list);}
    @Override protected void onPause(){if(inviteDialog!=null){inviteDialog.dismiss();inviteDialog=null;}super.onPause();SyncCoordinator.detach(updates);if(pendingSearch!=null)handler.removeCallbacks(pendingSearch);searchGeneration++;loadingPeople=false;}
    private void showStatus(String value){if(!alive())return;status.setText(value);status.setVisibility(value.isEmpty()?View.GONE:View.VISIBLE);}
    private static final class Snapshot{final List<JSONObject> contacts,messages,groups;final JSONArray invites;final Set<String> pinned;Snapshot(List<JSONObject> c,List<JSONObject> m,List<JSONObject> g,JSONArray i,Set<String> p){contacts=c;messages=m;groups=g;invites=i;pinned=p;}}
    private void load(){Repository.work(this,v->new Snapshot(v.contacts(),v.messages(null),v.groups(),v.groupInvites(),v.pinnedChats()),snapshot->{if(!alive())return;contacts=snapshot.contacts;messages=snapshot.messages;groups=snapshot.groups;invites=snapshot.invites;pinnedChats=snapshot.pinned;render();},this::showStatus);}
    private String query(){String value=search.getText().toString().trim().toLowerCase(Locale.ROOT);return value.startsWith("@")?value.substring(1):value;}
    private void queryChanged(){
        clear.setVisibility(search.length()==0?View.GONE:View.VISIBLE);int generation=++searchGeneration;if(pendingSearch!=null)handler.removeCallbacks(pendingSearch);people=new JSONArray();lastPeopleQuery="";remoteError="";loadingPeople=false;render();String value=query();
        if(groupsOnly||!Validation.username(value))return;loadingPeople=true;render();pendingSearch=()->Repository.work(this,v->Repository.search(v,value),found->{if(!alive()||generation!=searchGeneration||!value.equals(query()))return;people=found;lastPeopleQuery=value;loadingPeople=false;render();},error->{if(!alive()||generation!=searchGeneration)return;loadingPeople=false;remoteError=error;render();});handler.postDelayed(pendingSearch,500);
    }
    private void focusSearch(){search.requestFocus();getSystemService(InputMethodManager.class).showSoftInput(search,InputMethodManager.SHOW_IMPLICIT);if(!query().isEmpty())queryChanged();}
    private void render(){
        if(adapter==null||!alive())return;String q=query();rows.clear();
        navigation.requests(invites.length());
        unread.setTextColor(getColor(unreadOnly?R.color.accent:R.color.muted));unread.setBackgroundTintList(ColorStateList.valueOf(getColor(unreadOnly?R.color.soft:R.color.page)));
        search.setHint(groupsOnly?"Search groups":"Search");search.setContentDescription(groupsOnly?"Search groups":"Search chats or usernames");start.setText(groupsOnly?"New group":"New chat");
        Map<String,List<JSONObject>> grouped=new HashMap<>();for(JSONObject m:messages)grouped.computeIfAbsent(m.optString("peer"),id->new ArrayList<>()).add(m);
        List<JSONObject> sorted=new ArrayList<>(contacts);sorted.sort((a,b)->Long.compare(lastTime(grouped.get(b.optString("uid"))),lastTime(grouped.get(a.optString("uid")))));Set<String> shown=new HashSet<>();
        for(JSONObject peer:sorted){if(groupsOnly)continue;String id=peer.optString("uid");List<JSONObject> conversation=grouped.getOrDefault(id,Collections.emptyList());int count=unreadCount(peer,conversation);if(unreadOnly&&count==0)continue;
            boolean matches=q.isEmpty()||peer.optString("username").toLowerCase(Locale.ROOT).contains(q);JSONObject match=null;for(JSONObject m:conversation)if(m.optString("body").toLowerCase(Locale.ROOT).contains(q)){matches=true;match=m;}if(!matches)continue;
            JSONObject latest=conversation.isEmpty()?null:conversation.get(conversation.size()-1);if(!q.isEmpty()&&match!=null)latest=match;
            String preview=peer.optBoolean("blocked")?"Blocked":latest==null?"Start a conversation":(latest.optBoolean("outgoing")?"You: ":"")+(latest.optBoolean("deleted")?"Message deleted":latest.optString("body").replace('\n',' '));
            if(preview.length()>100)preview=preview.substring(0,100)+"…";rows.add(new Row(1,peer,"@"+peer.optString("username"),preview,lastTime(conversation),count));shown.add(id);
        }
        for(JSONObject g:groups){if(g.optBoolean("hidden"))continue;String id="group:"+g.optString("id");List<JSONObject> conversation=grouped.getOrDefault(id,Collections.emptyList());if(!g.optBoolean("active")&&conversation.isEmpty())continue;int count=unreadCount(g,conversation);if(unreadOnly&&count==0)continue;boolean matches=q.isEmpty()||g.optString("name").toLowerCase(Locale.ROOT).contains(q);for(JSONObject m:conversation)if(m.optString("body").toLowerCase(Locale.ROOT).contains(q))matches=true;if(!matches)continue;JSONObject latest=conversation.isEmpty()?null:conversation.get(conversation.size()-1);JSONObject profile=new JSONObject();try{profile.put("uid",id).put("username",g.getString("name"));}catch(JSONException ignored){continue;}rows.add(new Row(5,profile,g.optString("name"),latest==null?"Group · "+g.optJSONArray("members").length()+" members":(!g.optBoolean("active")?"Left · ":"")+(latest.optBoolean("outgoing")?"You: ":"")+(latest.optBoolean("deleted")?"Message deleted":latest.optString("body").replace('\n',' ')),lastTime(conversation),count));}
        rows.sort((a,b)->{int pinOrder=Boolean.compare(isPinned(b),isPinned(a));return pinOrder!=0?pinOrder:Long.compare(b.time,a.time);});
        if(groupsOnly){
            List<Row> pending=new ArrayList<>();for(int i=0;i<invites.length();i++){JSONObject invite=invites.optJSONObject(i);if(invite==null||(!q.isEmpty()&&!invite.optString("name").toLowerCase(Locale.ROOT).contains(q)))continue;try{JSONObject row=new JSONObject().put("uid",invite.getString("id")).put("username",invite.getString("name")).put("invite",invite);pending.add(new Row(4,row,invite.optString("name"),"From @"+invite.optString("inviter"),0,0));}catch(JSONException ignored){}}if(!pending.isEmpty()){if(!rows.isEmpty())rows.add(0,new Row(0,null,"Your groups","",0,0));pending.add(0,new Row(0,null,"Group requests","",0,0));rows.addAll(0,pending);}
        }
        if(!q.isEmpty()&&!groupsOnly){
            if(!rows.isEmpty())rows.add(0,new Row(0,null,"Chats","",0,0));boolean heading=false;
            if(lastPeopleQuery.equals(q))for(int i=0;i<people.length();i++){JSONObject peer=people.optJSONObject(i);if(peer==null||shown.contains(peer.optString("uid")))continue;if(!heading){rows.add(new Row(0,null,"People on Priperi","",0,0));heading=true;}rows.add(new Row(2,peer,"@"+peer.optString("username"),"Tap to start a private chat",0,0));}
        }
        if(rows.isEmpty()){String title=q.isEmpty()?(unreadOnly?"You’re all caught up":groupsOnly?"Your groups":"Your chats start here"):(loadingPeople?"Looking for people…":"No matches yet");String detail=q.isEmpty()?(unreadOnly?"New unread messages will appear here.":groupsOnly?"Create a group or accept an invitation.":"Search a username to start a chat."):(q.length()<3?"Use at least 3 letters to find someone new.":"Try another username or a word from a saved chat.");rows.add(new Row(3,null,title,detail,0,0));}
        String hint=!remoteError.isEmpty()?"Saved chats shown. "+remoteError:loadingPeople?"Searching people…":!groupsOnly&&!q.isEmpty()&&q.length()<3?"Type at least 3 letters to find new people.":"";searchStatus.setText(hint);searchStatus.setVisibility(hint.isEmpty()?View.GONE:View.VISIBLE);adapter.notifyDataSetChanged();
    }
    private boolean isPinned(Row row){return row.peer!=null&&pinnedChats.contains(row.peer.optString("uid"));}
    private void chatOptions(Row row){
        String peer=row.peer.optString("uid");boolean pinned=isPinned(row);
        Ui.dialog(this).setTitle(row.title).setItems(new String[]{pinned?"Unpin chat":"Pin chat"},(dialog,index)->Repository.work(this,v->{v.setChatPinned(peer,!pinned);return true;},ok->{if(!alive())return;load();Toast.makeText(this,pinned?"Chat unpinned":"Chat pinned",Toast.LENGTH_SHORT).show();},this::showStatus)).setNegativeButton("Cancel",null).show();
    }
    private static long lastTime(List<JSONObject> messages){return messages==null||messages.isEmpty()?0:messages.get(messages.size()-1).optJSONObject("envelope").optLong("sentAt");}
    private static int unreadCount(JSONObject peer,List<JSONObject> messages){int count=0;for(JSONObject m:messages)if(!m.optBoolean("outgoing")&&m.optJSONObject("envelope").optLong("sentAt")>peer.optLong("readThrough"))count++;return count;}
    private void invitation(JSONObject row){JSONObject invite=row.optJSONObject("invite");if(invite==null)return;String id=invite.optString("id");inviteDialog=Ui.dialog(this).setTitle("Join "+invite.optString("name")+"?").setMessage("From @"+invite.optString("inviter")+". Earlier messages won’t be shared.").setNeutralButton("Later",null).setNegativeButton("Decline",(d,w)->replyInvite(id,false)).setPositiveButton("Accept",(d,w)->replyInvite(id,true)).show();}
    private void replyInvite(String id,boolean accept){if(!responding.add(id))return;render();Repository.work(this,v->{GroupRepository.action(v,accept?"acceptGroupInvite":"declineGroupInvite",new JSONObject().put("id",id));return Repository.sync(v);},warning->{responding.remove(id);showStatus(warning);load();SyncCoordinator.request(getApplicationContext());},error->{responding.remove(id);showStatus(error);render();SyncCoordinator.request(getApplicationContext());});}

    private void confirmKey(JSONObject peer){Ui.dialog(this).setTitle("Chat with @"+peer.optString("username")+"?").setMessage("Messages are encrypted between your phones. You can compare security keys from chat details.").setNegativeButton("Cancel",null).setPositiveButton("Open chat",(d,w)->Repository.work(this,v->{v.pin(peer);return true;},ok->openChat(peer.optString("uid")),this::showStatus)).show();}
    private void openChat(String uid){getSystemService(InputMethodManager.class).hideSoftInputFromWindow(search.getWindowToken(),0);startActivity(new Intent(this,ChatActivity.class).putExtra("peer",uid));}
    private static final class Row{final int kind;final JSONObject peer;final String title,subtitle;final long time;final int count;Row(int k,JSONObject p,String t,String s,long at,int n){kind=k;peer=p;title=t;subtitle=s;time=at;count=n;}}
    private final class Holder{TextView avatar,title,subtitle,time,count;ImageView pin;}
    private final class ChatAdapter extends BaseAdapter{
        public int getCount(){return rows.size();}public Object getItem(int position){return rows.get(position);}public long getItemId(int position){return position;}public boolean isEnabled(int position){return rows.get(position).peer!=null;}public int getViewTypeCount(){return 4;}public int getItemViewType(int position){return rows.get(position).kind==0?0:rows.get(position).kind==3?2:rows.get(position).kind==4?3:1;}
        public View getView(int position,View recycled,ViewGroup parent){Row data=rows.get(position);
            if(data.kind==0){TextView heading=Ui.text(InboxActivity.this,data.title,13,R.color.accent);heading.setTypeface(null,Typeface.BOLD);heading.setPadding(Ui.dp(InboxActivity.this,20),Ui.dp(InboxActivity.this,16),0,Ui.dp(InboxActivity.this,8));return heading;}
            if(data.kind==3){LinearLayout wrap=Ui.column(InboxActivity.this);wrap.setGravity(Gravity.CENTER);wrap.setPadding(Ui.dp(InboxActivity.this,32),Ui.dp(InboxActivity.this,60),Ui.dp(InboxActivity.this,32),Ui.dp(InboxActivity.this,36));wrap.addView(Ui.symbol(InboxActivity.this,"chat",58,R.color.accent));Ui.space(InboxActivity.this,wrap,20);TextView title=Ui.title(InboxActivity.this,data.title,22);title.setGravity(Gravity.CENTER);wrap.addView(title);Ui.space(InboxActivity.this,wrap,10);TextView detail=Ui.text(InboxActivity.this,data.subtitle,15,R.color.muted);detail.setGravity(Gravity.CENTER);wrap.addView(detail);return wrap;}
            if(data.kind==4){LinearLayout card=Ui.card(InboxActivity.this);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);card.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1,-2));card.addView(Ui.title(InboxActivity.this,data.title,18));card.addView(Ui.text(InboxActivity.this,data.subtitle,13,R.color.muted));LinearLayout actions=Ui.row(InboxActivity.this);MaterialButton accept=Ui.button(InboxActivity.this,"Accept",true,v->invitation(data.peer)),decline=Ui.button(InboxActivity.this,"Decline",false,v->replyInvite(data.peer.optJSONObject("invite").optString("id"),false));boolean enabled=!responding.contains(data.peer.optString("uid"));accept.setEnabled(enabled);decline.setEnabled(enabled);actions.addView(accept,new LinearLayout.LayoutParams(0,-2,1));actions.addView(decline,new LinearLayout.LayoutParams(0,-2,1));card.addView(actions);card.setOnClickListener(v->invitation(data.peer));return card;}
            Holder holder;LinearLayout line;
            if(recycled instanceof LinearLayout&&recycled.getTag() instanceof Holder){line=(LinearLayout)recycled;holder=(Holder)line.getTag();}
            else{line=Ui.row(InboxActivity.this);line.setPadding(Ui.dp(InboxActivity.this,18),Ui.dp(InboxActivity.this,13),Ui.dp(InboxActivity.this,18),Ui.dp(InboxActivity.this,13));line.setBackground(new RippleDrawable(ColorStateList.valueOf(getColor(R.color.soft)),Ui.shape(InboxActivity.this,getColor(R.color.surface),0),null));holder=new Holder();holder.avatar=Ui.avatar(InboxActivity.this,"p",52);line.addView(holder.avatar);LinearLayout words=Ui.column(InboxActivity.this);words.setPadding(Ui.dp(InboxActivity.this,14),0,Ui.dp(InboxActivity.this,10),0);holder.title=Ui.text(InboxActivity.this,"",16,R.color.ink);holder.title.setTypeface(null,Typeface.BOLD);holder.title.setSingleLine(true);holder.title.setEllipsize(TextUtils.TruncateAt.END);words.addView(holder.title);Ui.space(InboxActivity.this,words,5);holder.subtitle=Ui.text(InboxActivity.this,"",14,R.color.muted);holder.subtitle.setSingleLine(true);holder.subtitle.setEllipsize(TextUtils.TruncateAt.END);words.addView(holder.subtitle);line.addView(words,new LinearLayout.LayoutParams(0,-2,1));LinearLayout meta=Ui.column(InboxActivity.this);meta.setGravity(Gravity.END);holder.time=Ui.text(InboxActivity.this,"",11,R.color.muted);holder.time.setMaxWidth(Ui.dp(InboxActivity.this,90));holder.time.setSingleLine(true);holder.time.setEllipsize(TextUtils.TruncateAt.END);meta.addView(holder.time);holder.pin=Ui.symbol(InboxActivity.this,"pin",16,R.color.muted);holder.pin.setContentDescription("Pinned chat");meta.addView(holder.pin);Ui.space(InboxActivity.this,meta,8);holder.count=Ui.text(InboxActivity.this,"",11,R.color.on_accent);holder.count.setGravity(Gravity.CENTER);holder.count.setMinWidth(Ui.dp(InboxActivity.this,24));holder.count.setMinHeight(Ui.dp(InboxActivity.this,24));holder.count.setPadding(Ui.dp(InboxActivity.this,6),Ui.dp(InboxActivity.this,2),Ui.dp(InboxActivity.this,6),Ui.dp(InboxActivity.this,2));holder.count.setBackground(Ui.shape(InboxActivity.this,getColor(R.color.accent),12));meta.addView(holder.count,new LinearLayout.LayoutParams(-2,-2));line.addView(meta,new LinearLayout.LayoutParams(Ui.dp(InboxActivity.this,Math.min(104,Math.round(58*getResources().getConfiguration().fontScale))),-2));line.setTag(holder);}
            holder.avatar.setText(data.peer.optString("username","?").substring(0,1).toUpperCase(Locale.ROOT));holder.title.setText(data.title);holder.subtitle.setText(data.subtitle);holder.time.setText(Ui.chatTime(data.time));holder.pin.setVisibility(isPinned(data)?View.VISIBLE:View.GONE);holder.count.setVisibility(data.count==0?View.GONE:View.VISIBLE);holder.count.setText(data.count>99?"99+":String.valueOf(data.count));line.setContentDescription(data.title+(isPinned(data)?". Pinned chat":"")+". "+data.subtitle+(data.count>0?". "+data.count+" unread":""));return line;
        }
    }
}
