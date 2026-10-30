package com.priperi;

import android.os.*;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.button.MaterialButton;
import org.json.JSONObject;
import java.util.*;
import androidx.lifecycle.*;

public final class ChatActivity extends PrivateActivity {
    private String peerId;
    private boolean groupChat;
    private JSONObject group;
    private JSONObject peer;
    private LinearLayout history;
    private ScrollView scroll;
    private TextView status, heading,subtitle,compactHeading;
    private EditText composer;
    private SendButton send;
    private boolean sending,approving,compactLayout,collapseReply;
    private volatile boolean viewing;
    private volatile long viewEpoch;
    private int remaining=3;
    private TextView allowance;private ImageButton mute;
    private LinearLayout requestCard;
    private MaterialButton approve;
    private androidx.appcompat.app.AlertDialog approvalDialog;
    private Draft draft;
    private LinearLayout replyBar;private TextView replyLabel,replyHeading;
    private final Map<String,JSONObject> visibleMessages=new HashMap<>();
    private androidx.appcompat.app.AlertDialog actionDialog;
    private EditText actionInput;private View selectedBubble;
    private final Map<String,View> messageViews=new HashMap<>();
    /** Rotation state stays in memory, never plaintext saved-instance storage. */
    private static final class Draft extends ViewModel {
        final MutableLiveData<String> text=new MutableLiveData<>("");
        final MutableLiveData<Boolean> saving=new MutableLiveData<>(false);
        String owner,peer,replyTo;
        @Override protected void onCleared(){text.setValue("");replyTo=null;owner=null;peer=null;}
    }
    private String rendered = "";
    private final SyncCoordinator.Observer updates = message -> {
        if (!isFinishing() && !isDestroyed() && !environmentBlocked) {
            if (Session.uid() == null) { requireSignIn(); return; }
            showStatus(message);load();
        }
    };
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved); peerId = getIntent().getStringExtra("peer"); if (peerId == null) { finish(); return; }
        groupChat=peerId.startsWith("group:");
        draft=new ViewModelProvider(this,new ViewModelProvider.Factory(){@Override public <T extends ViewModel> T create(Class<T> modelClass){return modelClass.cast(new Draft());}}).get(Draft.class);
        if(!Objects.equals(draft.owner,Session.uid())||!Objects.equals(draft.peer,peerId)){draft.owner=Session.uid();draft.peer=peerId;draft.replyTo=null;draft.text.setValue("");draft.saving.setValue(false);}
        root = Ui.page(this); root.setVisibility(View.INVISIBLE);
        LinearLayout bar = Ui.row(this);bar.setPadding(Ui.dp(this,4),Ui.dp(this,7),Ui.dp(this,6),Ui.dp(this,7));bar.setBackgroundColor(getColor(R.color.surface));bar.addView(Ui.iconButton(this,"back","Back",v->finish()));
        TextView avatar=Ui.avatar(this,"p",42);avatar.setTag("peer-avatar");bar.addView(avatar);LinearLayout words=Ui.column(this);words.setPadding(Ui.dp(this,12),0,0,0);
        heading = Ui.text(this, "Chat", 17, R.color.ink); heading.setTypeface(null, android.graphics.Typeface.BOLD);heading.setSingleLine(true);heading.setEllipsize(android.text.TextUtils.TruncateAt.END);words.addView(heading);subtitle=Ui.text(this,"End-to-end encrypted",11,R.color.muted);subtitle.setSingleLine(true);subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);words.addView(subtitle);bar.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        avatar.setContentDescription(groupChat?"Group details":"Contact details");avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);avatar.setFocusable(true);avatar.setOnClickListener(v->details());words.setOnClickListener(v->details());words.setContentDescription(groupChat?"Group details":"Contact details");words.setFocusable(true);
        mute=Ui.iconButton(this,"bell","Mute conversation",v->MuteControls.show(this,peerId,this::load));bar.addView(mute);if(!groupChat)bar.addView(Ui.iconButton(this,"more","Chat details",v->details()));root.addView(bar);
        compactHeading=Ui.text(this,"Chat",14,R.color.ink);compactHeading.setSingleLine(true);compactHeading.setEllipsize(android.text.TextUtils.TruncateAt.END);compactHeading.setPadding(Ui.dp(this,18),Ui.dp(this,2),Ui.dp(this,18),Ui.dp(this,2));compactHeading.setVisibility(View.GONE);root.addView(compactHeading);
        status = Ui.text(this, "", 12, R.color.muted); status.setPadding(Ui.dp(this, 24), 0, Ui.dp(this, 24), Ui.dp(this, 10)); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); status.setVisibility(View.GONE);root.addView(status);
        allowance=Ui.text(this,"",12,R.color.muted);allowance.setSingleLine(true);allowance.setEllipsize(android.text.TextUtils.TruncateAt.END);allowance.setPadding(Ui.dp(this,18),Ui.dp(this,4),Ui.dp(this,18),Ui.dp(this,4));
        requestCard=Ui.row(this);requestCard.setBackgroundColor(getColor(R.color.soft));Ui.pad(this,requestCard,8);TextView request=Ui.text(this,"New contact",14,R.color.ink);requestCard.addView(request,new LinearLayout.LayoutParams(0,-2,1));approve=Ui.button(this,"Approve contact",false,v->approve());requestCard.addView(approve);requestCard.setVisibility(View.GONE);root.addView(requestCard);
        scroll = new ScrollView(this); scroll.setFillViewport(true); history = Ui.column(this); Ui.pad(this, history, 18);scroll.setBackground(new ConversationSurface(this)); scroll.addView(history); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        allowance.setBackground(Ui.shape(this,getColor(R.color.soft),14));allowance.setSingleLine(false);allowance.setMaxLines(3);LinearLayout.LayoutParams limitParams=new LinearLayout.LayoutParams(-1,-2);limitParams.setMargins(Ui.dp(this,12),Ui.dp(this,4),Ui.dp(this,12),Ui.dp(this,2));allowance.setPadding(Ui.dp(this,14),Ui.dp(this,9),Ui.dp(this,14),Ui.dp(this,9));root.addView(allowance,limitParams);
        LinearLayout dock=Ui.column(this);dock.setBackground(Ui.shape(this,getColor(R.color.surface),24));LinearLayout.LayoutParams dockParams=new LinearLayout.LayoutParams(-1,-2);dockParams.setMargins(Ui.dp(this,12),Ui.dp(this,4),Ui.dp(this,12),Ui.dp(this,10));root.addView(dock,dockParams);
        replyBar=Ui.row(this);replyBar.setPadding(Ui.dp(this,10),Ui.dp(this,5),0,Ui.dp(this,5));replyBar.setBackground(Ui.shape(this,getColor(R.color.soft),14));LinearLayout.LayoutParams replyParams=new LinearLayout.LayoutParams(-1,-2);replyParams.setMargins(Ui.dp(this,8),Ui.dp(this,8),Ui.dp(this,8),0);dock.addView(replyBar,replyParams);
        View accent=new View(this);accent.setBackground(Ui.shape(this,getColor(R.color.accent),2));replyBar.addView(accent,new LinearLayout.LayoutParams(Ui.dp(this,3),Ui.dp(this,34)));
        LinearLayout quote=Ui.column(this);quote.setPadding(Ui.dp(this,10),0,0,0);replyHeading=Ui.text(this,"Reply",11,R.color.accent);replyHeading.setTypeface(null,android.graphics.Typeface.BOLD);replyHeading.setSingleLine(true);replyHeading.setEllipsize(android.text.TextUtils.TruncateAt.END);quote.addView(replyHeading);replyLabel=Ui.text(this,"",13,R.color.ink);replyLabel.setSingleLine(true);replyLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);quote.addView(replyLabel);replyBar.addView(quote,new LinearLayout.LayoutParams(0,-2,1));ImageButton cancelReply=Ui.iconButton(this,"close","Cancel reply",v->{draft.replyTo=null;updateReply();});Ui.pad(this,cancelReply,16);replyBar.addView(cancelReply);replyBar.setVisibility(View.GONE);
        LinearLayout compose = Ui.row(this); Ui.pad(this, compose, 6);dock.addView(compose);
        composer = new EditText(this); composer.setHint("Message"); composer.setTextSize(16); composer.setTextColor(getColor(R.color.ink)); composer.setHintTextColor(getColor(R.color.muted));
        composer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        composer.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI|android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        composer.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS); composer.setSaveEnabled(false); composer.setMaxLines(4); composer.setMinHeight(Ui.dp(this,48));composer.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4000)}); Ui.pad(this, composer, 12);
        composer.setBackground(Ui.shape(this, getColor(R.color.surface), 20)); compose.addView(composer, new LinearLayout.LayoutParams(0, -2, 1));
        send = new SendButton(this);send.setOnClickListener(v->send());LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48));params.gravity=Gravity.BOTTOM;params.setMarginStart(Ui.dp(this,6));compose.setGravity(Gravity.BOTTOM);compose.addView(send,params);
        composer.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){if(!Objects.equals(draft.text.getValue(),s.toString()))draft.text.setValue(s.toString());updateSend();}public void afterTextChanged(Editable value){}});
        draft.text.observe(this,value->{if(!composer.getText().toString().equals(value)){composer.setText(value);composer.setSelection(composer.length());}updateSend();});
        draft.saving.observe(this,value->{sending=Boolean.TRUE.equals(value);updateSend();});
        root.getViewTreeObserver().addOnGlobalLayoutListener(()->{
            int available=root.getHeight()-root.getPaddingTop()-root.getPaddingBottom();
            androidx.core.view.WindowInsetsCompat insets=androidx.core.view.ViewCompat.getRootWindowInsets(root);
            boolean keyboard=insets!=null&&insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime());
            float font=getResources().getConfiguration().fontScale;boolean compact=keyboard&&available<Ui.dp(this,Math.round(280+Math.max(0,font-1)*160));
            compactLayout=keyboard||available<Ui.dp(this,350);collapseReply=available<Ui.dp(this,Math.round(240+Math.max(0,font-1)*100));applyContactPolicy();updateReply();
            int composeVertical=Ui.dp(this,compact?0:6);
            if(compose.getPaddingTop()!=composeVertical)compose.setPadding(Ui.dp(this,6),composeVertical,Ui.dp(this,6),composeVertical);
            int headerVisibility=compact?View.GONE:View.VISIBLE;if(bar.getVisibility()!=headerVisibility)bar.setVisibility(headerVisibility);
            int compactVisibility=compact&&available>=Ui.dp(this,140)?View.VISIBLE:View.GONE;if(compactHeading.getVisibility()!=compactVisibility)compactHeading.setVisibility(compactVisibility);
            int lines=available<Ui.dp(this,280)?1:available<Ui.dp(this,420)||getResources().getConfiguration().fontScale>=1.5f?2:4;
            if(composer.getMaxLines()!=lines)composer.setMaxLines(lines);
            int width=history.getWidth()-history.getPaddingLeft()-history.getPaddingRight();
            int max=Math.max(Ui.dp(this,80),Math.min(Ui.dp(this,360),Math.round(width*.84f))-Ui.dp(this,24));
            for(TextView text:messageBodies)if(text.getMaxWidth()!=max)text.setMaxWidth(max);
        });
    }
    @Override protected void ready() { viewing=true;load(); SyncCoordinator.attach(updates,true); }
    @Override protected void onPause() { viewing=false;viewEpoch++;if(approvalDialog!=null){approvalDialog.dismiss();approvalDialog=null;}if(actionDialog!=null){actionDialog.dismiss();actionDialog=null;}actionInput=null;super.onPause(); SyncCoordinator.detach(updates); }
    private static final class Snapshot { JSONObject peer; List<JSONObject> messages; int slots;long through;Snapshot(JSONObject p,List<JSONObject> m,int slots,long through){peer=p;messages=m;this.slots=slots;this.through=through;} }
    private final List<TextView> messageBodies=new ArrayList<>();
    private void updateSend(){if(send!=null&&composer!=null)send.ready(peer!=null&&!peer.optBoolean("blocked")&&!sending&&remaining>0&&Validation.message(composer.getText().toString()));}
    private void load() {
        long epoch=viewEpoch,generation=Session.generation();String owner=Session.uid();
        Repository.work(this, v -> {List<JSONObject> all=v.records(peerId),messages=new ArrayList<>(all);messages.removeIf(m->m.optBoolean("hidden")||m.optBoolean("localDeleted"));long through=0;for(JSONObject m:messages)if(!m.optBoolean("outgoing"))through=Math.max(through,m.getJSONObject("envelope").getLong("sentAt"));JSONObject profile;
            if(groupChat){JSONObject g=v.group(peerId.substring(6));if(g==null)return new Snapshot(null,messages,0,through);profile=new JSONObject(g.toString()).put("uid",peerId).put("username",g.getString("name")).put("blocked",!g.optBoolean("active")).put("contactState",new JSONObject().put("approved",true));}
            else{profile=v.contact(peerId);}if(profile!=null)profile=new JSONObject(profile.toString()).put("_muted",v.muted(peerId)).put("_verified",groupChat?v.groupVerified(profile):v.isVerified(profile));return new Snapshot(profile,messages,profile==null?0:Repository.remaining(profile,all),through);}, snapshot -> {
            if(!viewing||epoch!=viewEpoch||generation!=Session.generation()||!Objects.equals(owner,Session.uid())||!privateContentAvailable())return;
            if (isFinishing() || isDestroyed() || snapshot.peer == null) { if (snapshot.peer == null) finish(); return; }
            // Only the snapshot actually presented by this live screen counts
            // as read. A paused/rotated/account-replaced load has no read effect.
            if(snapshot.through>snapshot.peer.optLong("readThrough"))Repository.work(this,v->{if(viewing&&epoch==viewEpoch&&generation==Session.generation()&&Objects.equals(owner,Session.uid())){if(groupChat)v.groupRead(peerId.substring(6),snapshot.through);else v.markRead(peerId,snapshot.through);}return true;},null,this::showStatus);
            peer = snapshot.peer;if(groupChat)group=peer;mute.setContentDescription(peer.optBoolean("_muted")?"Unmute conversation":"Mute conversation");mute.setImageDrawable(new Icons(peer.optBoolean("_muted")?"bell-off":"bell",getColor(peer.optBoolean("_muted")?R.color.muted:R.color.ink)));heading.setText((groupChat?"":"@") + peer.optString("username"));compactHeading.setText(heading.getText());composer.setContentDescription("Message to "+(groupChat?"":"@")+peer.optString("username"));send.setTooltipText("Send to "+(groupChat?"":"@")+peer.optString("username"));
            ((TextView)root.findViewWithTag("peer-avatar")).setText(peer.optString("username","p").substring(0,1).toUpperCase(Locale.ROOT));subtitle.setText(groupChat?(peer.optBoolean("blocked")?"You’re no longer a member":peer.optJSONArray("members").length()+" members · Encrypted"):peer.optBoolean("blocked")?"Blocked":peer.optBoolean("_verified")?"End-to-end encrypted":"Security key not checked");
            remaining=snapshot.slots;visibleMessages.clear();for(JSONObject m:snapshot.messages)visibleMessages.put(m.optJSONObject("envelope").optString("id"),m);updateReply();composer.setEnabled(!peer.optBoolean("blocked"));applyContactPolicy();updateSend();
            String marker = snapshot.messages.toString(); if (marker.equals(rendered)) return;boolean atBottom=history.getHeight()-scroll.getScrollY()-scroll.getHeight()<Ui.dp(this,100);int previousScroll=scroll.getScrollY();boolean animateArrivals=!rendered.isEmpty()&&atBottom&&snapshot.messages.size()<=messageViews.size()+5;Set<String> previousIds=new HashSet<>(messageViews.keySet());rendered = marker; history.removeAllViews();
            messageBodies.clear();messageViews.clear();
            if (snapshot.messages.isEmpty()) { Ui.space(this,history,28);LinearLayout note=Ui.card(this);note.setGravity(Gravity.CENTER);note.addView(Ui.symbol(this,"lock",24,R.color.accent));Ui.space(this,note,12);TextView intro=Ui.text(this,groupChat?"Only accepted members receive messages.":"Only you and your contact can read this chat.",13,R.color.muted);intro.setGravity(Gravity.CENTER);note.addView(intro);history.addView(note); }
            String lastDay="";
            for (JSONObject m : snapshot.messages) {
                String day=new java.text.SimpleDateFormat("d MMM yyyy",Locale.getDefault()).format(new java.util.Date(m.optJSONObject("envelope").optLong("sentAt")));
                if(!day.equals(lastDay)){LinearLayout dateRow=Ui.row(this);dateRow.setGravity(Gravity.CENTER);TextView date=Ui.text(this,day,11,R.color.muted);date.setPadding(Ui.dp(this,12),Ui.dp(this,5),Ui.dp(this,12),Ui.dp(this,5));date.setBackground(Ui.shape(this,getColor(R.color.surface),12));dateRow.addView(date);history.addView(dateRow);Ui.space(this,history,16);lastDay=day;}
                boolean outgoing = m.optBoolean("outgoing");
                LinearLayout line = Ui.row(this); line.setGravity(outgoing ? Gravity.END : Gravity.START);
                LinearLayout bubble = Ui.column(this); Ui.pad(this, bubble, 12); bubble.setBackground(Ui.shape(this, getColor(outgoing ? R.color.soft : R.color.surface), 16));
                if(groupChat&&!outgoing){JSONObject author=m.optJSONObject("author");if(author!=null&&group!=null){try{JSONObject current=GroupRepository.member(group,author.optString("uid"));if(current!=null)author=current;}catch(org.json.JSONException ignored){}}TextView by=Ui.text(this,"@"+(author==null?"member":author.optString("username","member")),12,R.color.accent);bubble.addView(by);Ui.space(this,bubble,5);}
                String reply=m.optString("replyTo");if(!reply.isEmpty()){JSONObject original=visibleMessages.get(reply);String quote=original==null||original.optBoolean("deleted")?"Message unavailable":original.optString("body");TextView reference=Ui.text(this,"Reply · "+quote,12,R.color.accent);reference.setMaxLines(2);reference.setEllipsize(android.text.TextUtils.TruncateAt.END);reference.setMaxWidth(Math.max(Ui.dp(this,80),Math.min(Ui.dp(this,360),Math.round((history.getWidth()-history.getPaddingLeft()-history.getPaddingRight())*.84f))-Ui.dp(this,24)));messageBodies.add(reference);reference.setContentDescription("View original message");reference.setOnClickListener(v->revealOriginal(reply));bubble.addView(reference);Ui.space(this,bubble,6);}
                TextView body = Ui.text(this,m.optBoolean("deleted")?"Message deleted":m.optString("body"),16,R.color.ink);body.setMaxWidth(Math.max(Ui.dp(this,80),Math.min(Ui.dp(this,360),Math.round((history.getWidth()-history.getPaddingLeft()-history.getPaddingRight())*.84f))-Ui.dp(this,24)));body.setTextIsSelectable(false);body.setOnLongClickListener(v->{showActions(m,bubble);return true;});bubble.setOnLongClickListener(v->{showActions(m,bubble);return true;});if(!m.optBoolean("deleted"))MessageLinks.bind(this,body,m.optString("body"),()->actionReady()&&(actionDialog==null||!actionDialog.isShowing()));body.setSaveEnabled(false);messageBodies.add(body);bubble.addView(body,new LinearLayout.LayoutParams(-2,-2));
                Ui.space(this, bubble, 7); JSONObject envelope = m.optJSONObject("envelope");
                String state = m.optString("state");
                String label = "group-changed".equals(state)?"Not sent · group changed; copy to resend":"pending".equals(state) ? (m.has("retryAfter")?"Waiting to retry":"Waiting to send") : "archived".equals(state) ? "Saved from backup" : "delivered".equals(state) ? "Delivered" : "expired".equals(state) ? "Delivery unconfirmed" : "sent".equals(state) ? "Sent" : "";
                if(m.optBoolean("edited"))label="Edited"+(label.isEmpty()?"":" · "+label);if(outgoing){String change=m.optString("changeState");if("pending".equals(change))label="Change waiting to send";else if("sent".equals(change))label="Change sent";else if("delivered".equals(change))label="Change delivered";else if("expired".equals(change)||"group-changed".equals(change))label="Change not delivered";}
                TextView time = Ui.text(this, Ui.time(envelope.optLong("sentAt")) + (label.isEmpty() ? "" : " · " + label), 11, R.color.muted); bubble.addView(time, new LinearLayout.LayoutParams(-2, -2));
                FrameLayout frame=new FrameLayout(this);frame.setClipChildren(false);line.setClipChildren(false);ImageView replyMark=Ui.symbol(this,"reply",24,R.color.accent);replyMark.setAlpha(0);frame.addView(replyMark,new FrameLayout.LayoutParams(Ui.dp(this,24),Ui.dp(this,24),Gravity.CENTER));frame.addView(bubble,new FrameLayout.LayoutParams(-2,-2));ReplyGesture gesture=new ReplyGesture(bubble,replyMark,()->actionReady()&&!m.optBoolean("deleted")&&peer!=null&&!peer.optBoolean("blocked")&&(actionDialog==null||!actionDialog.isShowing()),()->startReply(envelope.optString("id")));body.setOnTouchListener(gesture);bubble.setOnTouchListener(gesture);bubble.setClickable(true);
                line.addView(frame,new LinearLayout.LayoutParams(-2,-2));history.addView(line,new LinearLayout.LayoutParams(-1,-2));messageViews.put(envelope.optString("id"),line);if(animateArrivals&&!previousIds.contains(envelope.optString("id")))Ui.fadeIn(this,frame);Ui.space(this,history,10);
            }
            scroll.post(() -> {if(atBottom)scroll.fullScroll(View.FOCUS_DOWN);else scroll.scrollTo(0,previousScroll);});
        }, message -> showStatus(message));
    }
    private void send() {
        String body = composer.getText().toString(),reply=draft.replyTo; if (sending || !Validation.message(body)) return;
        draft.saving.setValue(true);
        Repository.work(this, v -> MessageActions.send(v,peerId,body,reply), result -> {
            draft.saving.setValue(false);if(Objects.equals(body,draft.text.getValue())&&Objects.equals(reply,draft.replyTo))draft.text.setValue("");if(Objects.equals(reply,draft.replyTo)){draft.replyTo=null;updateReply();}
            SyncCoordinator.request(getApplicationContext());
            if(isFinishing()||isDestroyed()||environmentBlocked)return;send.committed();load();
        }, message -> {draft.saving.setValue(false);if(!isFinishing()&&!isDestroyed()){showStatus(message);}});
    }
    private void updateReply(){if(replyBar==null)return;boolean show=draft.replyTo!=null&&!collapseReply;boolean changed=replyBar.getVisibility()!=(show?View.VISIBLE:View.GONE);replyBar.setVisibility(show?View.VISIBLE:View.GONE);if(show&&changed)Ui.fadeIn(this,replyBar);JSONObject m=visibleMessages.get(draft.replyTo);String label=m==null||m.optBoolean("deleted")?"Message unavailable":m.optString("body");if(!label.contentEquals(replyLabel.getText()))replyLabel.setText(label);String target=m==null?"Reply":m.optBoolean("outgoing")?"Replying to yourself":"Replying to @"+(groupChat&&m.optJSONObject("author")!=null?m.optJSONObject("author").optString("username","member"):peer==null?"contact":peer.optString("username"));if(!target.contentEquals(replyHeading.getText()))replyHeading.setText(target);String description="Reply · "+label;if(!description.contentEquals(replyLabel.getContentDescription()==null?"":replyLabel.getContentDescription()))replyLabel.setContentDescription(description);}
    private boolean actionReady(){return privateContentAvailable()&&!environmentBlocked&&!isFinishing()&&!isDestroyed()&&draft.owner!=null&&draft.owner.equals(Session.uid())&&getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED);}
    private void startReply(String id){JSONObject message=visibleMessages.get(id);if(!actionReady()||message==null||message.optBoolean("deleted")||peer==null||peer.optBoolean("blocked"))return;draft.replyTo=id;updateReply();composer.requestFocus();composer.post(()->{if(actionReady())getSystemService(android.view.inputmethod.InputMethodManager.class).showSoftInput(composer,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);});}
    private void revealOriginal(String id){if(!actionReady())return;View message=messageViews.get(id);if(message==null)return;scroll.smoothScrollTo(0,Math.max(0,message.getTop()-Ui.dp(this,32)));Ui.lift(message,true);message.postDelayed(()->Ui.lift(message,false),450);}
    private void showActions(JSONObject message,View bubble){if(!actionReady())return;selectedBubble=bubble;Ui.lift(bubble,true);bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);actions(message);}
    private void actions(JSONObject m){
        if(!actionReady())return;String id=m.optJSONObject("envelope").optString("id");ArrayList<String> choices=new ArrayList<>();boolean deleted=m.optBoolean("deleted");
        if(!deleted){choices.add("Reply");choices.add("Copy text");if(m.optBoolean("outgoing")&&("sent".equals(m.optString("state"))||"delivered".equals(m.optString("state")))){long at=m.optJSONObject("envelope").optLong("sentAt"),now=System.currentTimeMillis();if(MessageContent.allowed(2,at,now))choices.add("Edit");if(MessageContent.allowed(3,at,now))choices.add("Delete for everyone");}}
        choices.add("Delete for me");BaseAdapter menu=new BaseAdapter(){public int getCount(){return choices.size();}public Object getItem(int p){return choices.get(p);}public long getItemId(int p){return p;}public View getView(int p,View old,ViewGroup parent){String label=choices.get(p),icon=label.equals("Reply")?"reply":label.equals("Copy text")?"copy":label.equals("Edit")?"edit":label.equals("Delete for everyone")?"unsend":"trash";int color=label.equals("Delete for everyone")?R.color.danger:R.color.ink;LinearLayout row=Ui.row(ChatActivity.this);row.setPadding(Ui.dp(ChatActivity.this,20),Ui.dp(ChatActivity.this,13),Ui.dp(ChatActivity.this,20),Ui.dp(ChatActivity.this,13));row.setMinimumHeight(Ui.dp(ChatActivity.this,54));row.addView(Ui.symbol(ChatActivity.this,icon,23,color));TextView title=Ui.text(ChatActivity.this,label,16,color);title.setPadding(Ui.dp(ChatActivity.this,18),0,0,0);row.addView(title,new LinearLayout.LayoutParams(0,-2,1));row.setContentDescription(label);return row;}};TextView stamp=Ui.text(this,Ui.time(m.optJSONObject("envelope").optLong("sentAt")),12,R.color.muted);stamp.setPadding(Ui.dp(this,20),Ui.dp(this,18),Ui.dp(this,20),Ui.dp(this,8));actionDialog=Ui.dialog(this).setCustomTitle(stamp).setAdapter(menu,(d,index)->{
            if(!actionReady())return;String selected=choices.get(index);
            if("Reply".equals(selected)){startReply(id);}
            else if("Copy text".equals(selected)){android.content.ClipData clip=android.content.ClipData.newPlainText("Priperi",m.optString("body"));PersistableBundle sensitive=new PersistableBundle();sensitive.putBoolean("android.content.extra.IS_SENSITIVE",true);clip.getDescription().setExtras(sensitive);getSystemService(android.content.ClipboardManager.class).setPrimaryClip(clip);showStatus("Text copied.");}
            else if("Edit".equals(selected))editMessage(id,m.optString("body"));
            else {boolean everyone="Delete for everyone".equals(selected);actionDialog=Ui.dialog(this).setTitle(everyone?"Delete for everyone?":"Delete from this phone?").setMessage(everyone?"An encrypted delete request will be sent. It cannot erase saved backups or copies outside Priperi. Both people need the latest app.":"Your contact keeps their copy. A message already waiting to send may still arrive.").setNegativeButton("Cancel",null).setPositiveButton("Delete",(x,w)->performChange(id,null,everyone,true)).show();}
        }).create();
        View lifted=selectedBubble;actionDialog.setOnDismissListener(d->{if(lifted!=null)Ui.lift(lifted,false);selectedBubble=null;});actionDialog.show();android.view.Window window=actionDialog.getWindow();if(window!=null){int width=Math.min(getResources().getDisplayMetrics().widthPixels-Ui.dp(this,24),Ui.dp(this,310));android.graphics.Rect available=new android.graphics.Rect();root.getWindowVisibleDisplayFrame(available);int[] position=new int[2];bubbleLocation(lifted,position);int estimate=Math.min(available.height()-Ui.dp(this,24),Ui.dp(this,60+choices.size()*58));int top=position[1]+(lifted==null?0:lifted.getHeight())+Ui.dp(this,8);if(top+estimate>available.bottom)top=position[1]-estimate-Ui.dp(this,8);top=Math.max(available.top+Ui.dp(this,12),Math.min(top,available.bottom-estimate-Ui.dp(this,12)));window.setGravity(Gravity.TOP|Gravity.LEFT);window.setLayout(width,-2);android.view.WindowManager.LayoutParams attrs=window.getAttributes();attrs.x=Math.max(Ui.dp(this,12),Math.min(position[0],getResources().getDisplayMetrics().widthPixels-width-Ui.dp(this,12)));attrs.y=top-available.top;window.setAttributes(attrs);Ui.fadeIn(this,window.getDecorView());}

    }
    private static void bubbleLocation(View bubble,int[] position){if(bubble!=null)bubble.getLocationOnScreen(position);}
    private void editMessage(String id,String original){
        LinearLayout box=Ui.column(this);Ui.pad(this,box,20);EditText edit=Ui.input(this,box,"Message",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4000)});edit.setText(original);actionInput=edit;
        actionDialog=Ui.dialog(this).setTitle("Edit message").setView(box).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();actionDialog.setOnShowListener(d->actionDialog.getButton(-1).setOnClickListener(v->{if(!Validation.message(edit.getText().toString())){edit.setError("Write a message of up to 4,000 characters.");return;}performChange(id,edit.getText().toString(),true,false);}));actionDialog.show();
    }
    private void performChange(String id,String text,boolean everyone,boolean delete){
        if(!actionReady())return;String actor=draft.owner;androidx.appcompat.app.AlertDialog dialog=actionDialog;if(dialog!=null&&dialog.getButton(-1)!=null)dialog.getButton(-1).setEnabled(false);
        Repository.work(this,v->{if(!actor.equals(Session.uid()))throw new SecurityException("Sign in again.");if(everyone)MessageActions.change(v,peerId,id,text,delete);else v.deleteMessage(peerId,id);return true;},ok->{if(dialog!=null)dialog.dismiss();if(everyone)SyncCoordinator.request(getApplicationContext());if(actionReady())load();},message->{if(dialog!=null&&dialog.isShowing()&&dialog.getButton(-1)!=null)dialog.getButton(-1).setEnabled(true);if(actionReady()){showStatus(message);if(actionDialog==dialog&&dialog!=null&&dialog.isShowing()&&actionInput!=null)actionInput.setError(message);}});
    }
    private void applyContactPolicy(){
        if(allowance==null)return;JSONObject state=peer==null?null:peer.optJSONObject("contactState");boolean approved=state!=null&&state.optBoolean("approved");
        String label=remaining==0?"Waiting for this contact to approve you":remaining==Integer.MAX_VALUE?"":remaining+" message"+(remaining==1?"":"s")+" left. More after this person approves you.";if(!label.contentEquals(allowance.getText()))allowance.setText(label);
        int visible=!approved&&!groupChat&&!collapseReply?View.VISIBLE:View.GONE;if(allowance.getVisibility()!=visible)allowance.setVisibility(visible);
        int requestVisibility=state!=null&&state.optBoolean("incomingPending")&&!compactLayout?View.VISIBLE:View.GONE;if(requestCard.getVisibility()!=requestVisibility)requestCard.setVisibility(requestVisibility);
        approve.setEnabled(!approving);String hint=remaining==0?"Waiting for approval":"Message";if(!hint.contentEquals(composer.getHint()))composer.setHint(hint);
    }
    private void approve(){
        if(peer==null||approving)return;
        String actor=Session.uid();approvalDialog=Ui.dialog(this).setTitle("Approve @"+peer.optString("username")+"?").setMessage("Open this chat for both of you. Neither person will need to approve again. You can still block them at any time.").setNegativeButton("Cancel",null).setPositiveButton("Approve",(d,w)->{
            if(approving||environmentBlocked||isFinishing()||isDestroyed()||actor==null||!actor.equals(Session.uid()))return;approving=true;applyContactPolicy();
            Repository.work(this,v->{if(!actor.equals(Session.uid()))throw new SecurityException("Sign in again.");return Repository.approveContact(v,peerId);},state->{approving=false;if(!isFinishing()&&!isDestroyed()&&!environmentBlocked){load();showStatus("Chat approved for both of you.");SyncCoordinator.request(getApplicationContext());}},message->{approving=false;if(!isFinishing()&&!isDestroyed()){applyContactPolicy();showStatus(message);}});
        }).show();
    }
    private void showStatus(String message){if(status!=null){status.setText(message);status.setVisibility(message.isEmpty()?View.GONE:View.VISIBLE);}}
    private void securityKey(){if(!actionReady()||peer==null||groupChat)return;String user=Session.uid(),fingerprint=peer.optString("fingerprint");Ui.dialog(this).setTitle("Compare security keys").setMessage("Compare this with your contact’s Settings → Security key in person or over a trusted call. Confirm only if every character matches.\n\n"+SettingsActivity.groupKey(fingerprint)).setNegativeButton("Cancel",null).setNeutralButton("Scan QR / NFC",(d,w)->startActivity(new android.content.Intent(this,ContactCodeActivity.class))).setPositiveButton("Keys match",(d,w)->Repository.work(this,v->{JSONObject current=v.contact(peerId);if(!Objects.equals(user,Session.uid())||current==null||!fingerprint.equals(current.optString("fingerprint")))throw new SecurityException("The contact changed. Compare keys again.");v.contactFlag(peerId,"verified",true);return true;},ok->{if(actionReady()){load();showStatus("Security key checked.");}SyncCoordinator.request(getApplicationContext());},this::showStatus)).show();}
    private void details() {
        if (peer == null) return;
        if(groupChat){startActivity(new android.content.Intent(this,GroupDetailsActivity.class).putExtra("group",peerId.substring(6)));return;}
        JSONObject permission=peer.optJSONObject("contactState");boolean incoming=permission!=null&&permission.optBoolean("incomingPending");
        String[] options=incoming?new String[]{"Security key",peer.optBoolean("blocked")?"Unblock":"Block","Clear this chat","Approve contact"}:new String[]{"Security key",peer.optBoolean("blocked")?"Unblock":"Block","Clear this chat"};
        Ui.dialog(this).setTitle("@" + peer.optString("username")).setItems(options, (dialog, item) -> {
            if (item == 0) securityKey();
            else if (item == 1) {
                boolean blocked = !peer.optBoolean("blocked");
                Repository.work(this, v -> { Repository.call(v, "setBlocked", new JSONObject().put("uid", peerId).put("blocked", blocked)); v.contactFlag(peerId, "blocked", blocked); return true; }, ok -> { load(); showStatus(blocked ? "This account is blocked." : "This account is unblocked."); }, message -> showStatus(message));
            } else if(item==3){approve();} else Ui.dialog(this).setTitle("Clear this chat?").setMessage("This removes saved messages from this phone. Your contact keeps their copy. Messages and changes waiting to send stay in the outbox.").setNegativeButton("Cancel", null).setPositiveButton("Clear chat", (d,w) -> Repository.work(this, v -> { v.clearHistory(peerId); return true; }, ok -> load(), message -> showStatus(message))).show();
        }).show();
    }
}
