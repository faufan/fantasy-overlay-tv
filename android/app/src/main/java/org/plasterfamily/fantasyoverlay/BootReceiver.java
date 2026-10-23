package org.plasterfamily.fantasyoverlay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the control server after a reboot, and brings the panel back if it was showing. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            OverlayService.send(context, OverlayService.ACTION_BOOT);
        }
    }
}
