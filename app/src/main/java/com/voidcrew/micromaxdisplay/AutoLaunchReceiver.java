package com.voidcrew.micromaxdisplay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class AutoLaunchReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        // The switch in Settings controls this.
        if (!AppSettings.isAutoLaunchEnabled(context)) {
            return;
        }

        Intent launch = new Intent(context, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(launch);
    }
}