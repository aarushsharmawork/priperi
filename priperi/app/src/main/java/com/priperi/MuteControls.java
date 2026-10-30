package com.priperi;
/** Encrypted per-conversation preferences; never disable syncing or delivery. */
final class MuteControls {
 static void show(PrivateActivity host,String peer,Runnable changed){Repository.work(host,v->v.muted(peer),muted->{if(!host.privateContentAvailable())return;String[] choices=muted?new String[]{"Unmute","For 8 hours","For 7 days","Until I turn it back on"}:new String[]{"For 8 hours","For 7 days","Until I turn it back on"};Ui.dialog(host).setTitle("Mute notifications").setItems(choices,(d,index)->{int option=muted?index-1:index;long until=option<0?0:option==0?System.currentTimeMillis()+8*3600000L:option==1?System.currentTimeMillis()+7*86400000L:-1;Repository.work(host,v->{v.mute(peer,until);return true;},ok->{Notices.dismiss(host);changed.run();},error->android.widget.Toast.makeText(host,error,android.widget.Toast.LENGTH_SHORT).show());}).setNegativeButton("Cancel",null).show();},error->{});}
 private MuteControls(){}
}
