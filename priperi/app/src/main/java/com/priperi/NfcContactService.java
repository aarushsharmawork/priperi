package com.priperi;

import android.app.KeyguardManager;
import android.nfc.cardemulation.HostApduService;
import android.os.*;

/** Android NFC system alone may bind; an unlocked, explicitly armed foreground screen is also required. */
public final class NfcContactService extends HostApduService {
    static final NfcShareLease SHARING=new NfcShareLease();
    private Object selected;
    private boolean allowed(){
        try{DeviceSecurity.requireAllowed();KeyguardManager keyguard=getSystemService(KeyguardManager.class);PowerManager power=getSystemService(PowerManager.class);return keyguard!=null&&!keyguard.isDeviceLocked()&&power!=null&&power.isInteractive();}catch(RuntimeException error){return false;}
    }
    @Override public byte[] processCommandApdu(byte[] command,Bundle extras){
        boolean unlocked=allowed();long now=SystemClock.elapsedRealtime();String uid=Session.uid();
        if(NfcProtocol.isSelect(command)){
            selected=SHARING.current(uid,now,unlocked);return NfcProtocol.status(selected==null?0x6985:0x9000);
        }
        if(NfcProtocol.isRead(command)){
            String value=SHARING.read(selected,uid,now,unlocked);return value==null?NfcProtocol.status(0x6985):NfcProtocol.response(value);
        }
        if(NfcProtocol.isExchange(command)){
            String value=SHARING.read(selected,uid,now,unlocked);if(value==null)return NfcProtocol.status(0x6985);
            try{if(!SHARING.accept(selected,uid,now,unlocked,NfcProtocol.peer(command)))return NfcProtocol.status(0x6985);return NfcProtocol.response(value);}catch(IllegalArgumentException error){selected=null;return NfcProtocol.status(0x6700);}
        }
        selected=null;return NfcProtocol.status(command==null||command.length>261?0x6700:0x6D00);
    }
    @Override public void onDeactivated(int reason){selected=null;}
    @Override public void onDestroy(){selected=null;super.onDestroy();}
}
