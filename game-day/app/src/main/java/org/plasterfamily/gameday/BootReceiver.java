package org.plasterfamily.gameday;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** On the TV: start listening for the phone after a reboot (the window stays hidden). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) && StreamService.isTv(context)) {
            StreamService.start(context);
        }
    }
}
