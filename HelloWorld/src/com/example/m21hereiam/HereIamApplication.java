package com.example.m21hereiam;

import android.app.Application;
import android.os.Environment;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class HereIamApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread thread, Throwable ex) {
                logCrash(thread, ex);
                if (previous != null) previous.uncaughtException(thread, ex);
            }
        });
    }

    // Written directly to the day's txt log so a crash that kills the process
    // still leaves a record of what happened, instead of just vanishing.
    private void logCrash(Thread thread, Throwable ex) {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            if (!dir.exists()) dir.mkdirs();
            SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            SimpleDateFormat tsFmt   = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            Date now = new Date();
            File logFile = new File(dir, dateFmt.format(now) + "-hia.txt");
            StringWriter sw = new StringWriter();
            ex.printStackTrace(new PrintWriter(sw));
            FileWriter fw = new FileWriter(logFile, true);
            fw.write(tsFmt.format(now) + " CRASH on thread \"" + thread.getName() + "\":\n" + sw + "\n");
            fw.close();
        } catch (IOException | RuntimeException ignored) {}
    }
}
