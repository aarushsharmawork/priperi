package com.priperi;

/** Only an idle chat list backs off; open conversations retain the 4-second fallback. */
final class SyncPace {
    private int idle,failures;
    long complete(boolean changed,boolean failed,boolean conversation){
        failures=failed?Math.min(4,failures+1):0;idle=changed||failed?0:Math.min(4,idle+1);
        return failed?Math.min(60_000,4000L<<failures):conversation||idle<4?4000:8000;
    }
    void reset(){idle=0;failures=0;}
}
