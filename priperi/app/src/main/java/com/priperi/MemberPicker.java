package com.priperi;

import android.os.*;import android.text.*;import android.view.*;import android.widget.*;
import org.json.*;import java.util.*;import java.util.function.Consumer;

/** Local contacts first; directory searches retain the existing protected API. */
final class MemberPicker {
 private final PrivateActivity host;private final JSONObject group;private final Consumer<JSONObject> chosen;
 private final Handler handler=new Handler(Looper.getMainLooper());private int generation;private String owner;
 private final Set<String> blocked=new HashSet<>();
 private final List<JSONObject> contacts=new ArrayList<>(),remote=new ArrayList<>(),rows=new ArrayList<>();
 private androidx.appcompat.app.AlertDialog dialog;private EditText search;private TextView status;private ListView list;private BaseAdapter adapter;
 MemberPicker(PrivateActivity host,JSONObject group,Consumer<JSONObject> chosen){this.host=host;this.group=group;this.chosen=chosen;}
 void show(){
  owner=Session.uid();LinearLayout box=Ui.column(host);Ui.pad(host,box,18);search=Ui.search(host,box,"Search contacts or usernames");status=Ui.status(host,box);
  list=new ListView(host);list.setDivider(null);box.addView(list,new LinearLayout.LayoutParams(-1,Ui.dp(host,Math.max(140,Math.min(320,host.getResources().getConfiguration().screenHeightDp/2)))));
  adapter=new BaseAdapter(){public int getCount(){return rows.size();}public Object getItem(int p){return rows.get(p);}public long getItemId(int p){return p;}public View getView(int p,View old,ViewGroup parent){JSONObject peer=rows.get(p);LinearLayout row=Ui.row(host);Ui.pad(host,row,10);row.addView(Ui.avatar(host,peer.optString("username"),42));TextView name=Ui.text(host,"@"+peer.optString("username"),16,R.color.ink);name.setPadding(Ui.dp(host,12),0,0,0);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);row.addView(name,new LinearLayout.LayoutParams(0,-2,1));row.addView(Ui.symbol(host,"chevron",18,R.color.muted));return row;}};
  list.setAdapter(adapter);list.setOnItemClickListener((p,v,position,id)->{if(!available())return;JSONObject peer=rows.get(position);dialog.dismiss();chosen.accept(peer);});
  dialog=Ui.dialog(host).setTitle("Add members").setView(box).setNegativeButton("Done",null).create();dialog.setOnDismissListener(d->{generation++;handler.removeCallbacksAndMessages(null);contacts.clear();remote.clear();rows.clear();blocked.clear();});dialog.show();
  search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int a,int b,int c){query();}});
  Repository.work(host,Vault::contacts,result->{if(!available())return;contacts.addAll(result);for(JSONObject peer:result)if(peer.optBoolean("blocked"))blocked.add(peer.optString("uid"));query();},error->{if(available())status.setText(error);});
 }
 private boolean available(){return dialog!=null&&dialog.isShowing()&&host.privateContentAvailable()&&Objects.equals(owner,Session.uid());}
 private boolean eligible(JSONObject peer){if(peer==null||peer.optBoolean("blocked")||blocked.contains(peer.optString("uid"))||owner.equals(peer.optString("uid")))return false;try{return GroupRepository.member(group,peer.optString("uid"))==null;}catch(JSONException e){return false;}}
 private String queryText(){String q=search.getText().toString().trim().toLowerCase(Locale.ROOT);return q.startsWith("@")?q.substring(1):q;}
 private void render(){String q=queryText();rows.clear();Set<String> ids=new HashSet<>();for(List<JSONObject> source:Arrays.asList(contacts,remote))for(JSONObject peer:source)if(eligible(peer)&&peer.optString("username").contains(q)&&ids.add(peer.optString("uid")))rows.add(peer);rows.sort(Comparator.comparing(p->p.optString("username")));int height=Ui.dp(host,Math.min(Math.min(320,host.getResources().getConfiguration().screenHeightDp/2),Math.max(56,rows.size()*64)));android.view.ViewGroup.LayoutParams params=list.getLayoutParams();if(params.height!=height){params.height=height;list.setLayoutParams(params);}list.setVisibility(rows.isEmpty()?View.GONE:View.VISIBLE);adapter.notifyDataSetChanged();}
 private void query(){
  if(!available())return;int request=++generation;handler.removeCallbacksAndMessages(null);remote.clear();render();String q=queryText();status.setText(rows.isEmpty()?(q.isEmpty()?"Search a username to invite someone.":"No matching contacts."):"Your contacts");
  if(!Validation.username(q))return;handler.postDelayed(()->{if(!available()||request!=generation)return;status.setText("Searching…");Repository.work(host,v->Repository.search(v,q),found->{if(!available()||request!=generation||!q.equals(queryText()))return;for(int i=0;i<found.length();i++)remote.add(found.optJSONObject(i));render();status.setText(rows.isEmpty()?"No users found.":"Tap a person to invite");},error->{if(available()&&request==generation)status.setText(error);});},500);
 }
}
