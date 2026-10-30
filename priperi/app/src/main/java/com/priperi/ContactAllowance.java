package com.priperi;

/** Local compose guidance; the server independently enforces the durable cap. */
final class ContactAllowance {
    static int remaining(boolean approved,int serverSent,int locallySent,int queued){
        if(approved)return Integer.MAX_VALUE;
        return Math.max(0,3-Math.max(Math.max(0,serverSent),Math.max(0,locallySent))-Math.max(0,queued));
    }
    private ContactAllowance(){}
}
