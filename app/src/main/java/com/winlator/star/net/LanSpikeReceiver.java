package com.winlator.star.net;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * SPIKE / DEBUG ONLY. Lets the LAN-overlay spike be driven from the root bridge with:
 *   am broadcast -a com.winlator.banner.LAN_SPIKE_START -p com.winlator.banner
 *   am broadcast -a com.winlator.banner.LAN_SPIKE_STOP  -p com.winlator.banner
 */
public class LanSpikeReceiver extends BroadcastReceiver {
    private static final String TAG = "LANSPIKE";
    public static final String ACTION_START = "com.winlator.banner.LAN_SPIKE_START";
    public static final String ACTION_STOP = "com.winlator.banner.LAN_SPIKE_STOP";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent != null ? intent.getAction() : null;
        Log.i(TAG, "receiver got action=" + a);
        Intent svc = new Intent(ctx, LanBridgeVpnService.class);
        if (ACTION_STOP.equals(a)) {
            ctx.stopService(svc);
        } else {
            ctx.startService(svc);
        }
    }
}
