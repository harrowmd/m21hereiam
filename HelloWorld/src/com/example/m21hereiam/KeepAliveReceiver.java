package com.example.m21hereiam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

// Fired by an exact, allow-while-idle alarm (see LocationService.scheduleKeepAlive). Receiving a
// broadcast briefly raises the app's process importance in a way the OS recognises as a
// legitimate trigger — unlike a plain in-process Handler tick, a startForegroundService() call
// made from here is not silently ignored by Android's background-FGS-start limitation once the
// service has already dropped out of foreground state.
public class KeepAliveReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Intent svc = new Intent(context, LocationService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.startForegroundService(svc);
        else
            context.startService(svc);
    }
}
