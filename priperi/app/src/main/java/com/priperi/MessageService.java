package com.priperi;

import com.google.firebase.messaging.*;
import org.json.JSONObject;

public final class MessageService extends FirebaseMessagingService {
    @Override public void onMessageReceived(RemoteMessage message) {
        if(!"inbox".equals(message.getData().get("kind")))return;
        // Hand off synchronously before FCM releases its short process lifetime.
        PushSyncService.schedule(this);
    }
    @Override public void onNewToken(String token) {
        Repository.local(this,v->{
            if(Session.uid()!=null&&v.account()!=null&&v.setting("notifications",false))Repository.call(v,"setNotificationToken",new JSONObject().put("token",token));
            return true;
        },null,ignored->{});
    }
}
