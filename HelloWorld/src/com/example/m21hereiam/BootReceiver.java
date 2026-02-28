package com.example.m21hereiam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        SharedPreferences prefs = context.getSharedPreferences(
            LocationService.PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(LocationService.PREF_START_ON_BOOT, true)) return;
        Intent svc = new Intent(context, LocationService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.startForegroundService(svc);
        else
            context.startService(svc);
    }
}
