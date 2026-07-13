package com.winlator.star.net;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * P1 trigger. Start the overlay from the root bridge:
 *   am broadcast -a com.winlator.banner.LAN_START -p com.winlator.banner \
 *     --es relay 127.0.0.1 --ei port 48800 --es room GAME01 --ei role 1
 *   am broadcast -a com.winlator.banner.LAN_STOP  -p com.winlator.banner
 * (role 1 = host / 10.99.0.1, 2 = client / 10.99.0.2)
 */
public class LanOverlayReceiver extends BroadcastReceiver {
    public static final String ACTION_START = "com.winlator.banner.LAN_START";
    public static final String ACTION_STOP  = "com.winlator.banner.LAN_STOP";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent != null ? intent.getAction() : null;
        Intent svc = new Intent(ctx, LanOverlayVpnService.class);
        if (ACTION_STOP.equals(a)) {
            svc.setAction("STOP");
        } else {
            svc.setAction("START");
            svc.putExtra(LanOverlayVpnService.EXTRA_RELAY, intent.getStringExtra("relay"));
            svc.putExtra(LanOverlayVpnService.EXTRA_PORT, intent.getIntExtra("port", 48800));
            svc.putExtra(LanOverlayVpnService.EXTRA_ROOM, intent.getStringExtra("room"));
            svc.putExtra(LanOverlayVpnService.EXTRA_ROLE, intent.getIntExtra("role", LanOverlay.ROLE_HOST));
        }
        ctx.startService(svc);
    }
}
