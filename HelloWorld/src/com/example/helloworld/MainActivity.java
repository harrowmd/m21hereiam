package com.example.helloworld;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class MainActivity extends Activity implements LocationListener {

    private static final int LOCATION_PERMISSION_REQUEST = 1;

    // GPX closing tags — always the tail of a valid GPX file
    private static final String GPX_CLOSE = "    </trkseg>\n  </trk>\n</gpx>\n";

    private MapView  mapView;
    private TextView tvLat, tvLon, tvAlt, tvAccuracy, tvSatellites, tvBattery, tvDate, tvTime;
    private LocationManager locationManager;
    private GnssStatus.Callback gnssCallback;

    // Current values for logging
    private double csvLat        = 0;
    private double csvLon        = 0;
    private double csvAlt        = 0;
    private float  csvAccuracy   = 0;
    private int    csvSatellites = 0;
    private int    csvBattery    = 0;

    private final Handler clockHandler = new Handler();
    private final Handler logHandler   = new Handler();

    private final SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd",          Locale.getDefault());
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss",            Locale.getDefault());
    private final SimpleDateFormat tsFmt   = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    private final SimpleDateFormat isoFmt;   // UTC ISO-8601 for GPX <time>

    {
        isoFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        isoFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            tvDate.setText("Date: " + dateFmt.format(now));
            tvTime.setText("Time: " + timeFmt.format(now));
            clockHandler.postDelayed(this, 1000);
        }
    };

    private final Runnable logTick = new Runnable() {
        @Override public void run() {
            saveToCsv();
            saveToGpx();
            logHandler.postDelayed(this, 60000);
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            csvBattery = (scale > 0) ? (level * 100 / scale) : -1;
            tvBattery.setText("Battery: " + csvBattery + "%");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mapView      = (MapView)  findViewById(R.id.map_view);
        tvLat        = (TextView) findViewById(R.id.tv_lat);
        tvLon        = (TextView) findViewById(R.id.tv_lon);
        tvAlt        = (TextView) findViewById(R.id.tv_alt);
        tvAccuracy   = (TextView) findViewById(R.id.tv_accuracy);
        tvSatellites = (TextView) findViewById(R.id.tv_satellites);
        tvBattery    = (TextView) findViewById(R.id.tv_battery);
        tvDate       = (TextView) findViewById(R.id.tv_date);
        tvTime       = (TextView) findViewById(R.id.tv_time);

        Button btnRecentre = (Button) findViewById(R.id.btn_recentre);
        btnRecentre.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { mapView.recentre(); }
        });

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            gnssCallback = new GnssStatus.Callback() {
                @Override public void onSatelliteStatusChanged(GnssStatus status) {
                    int used = 0;
                    for (int i = 0; i < status.getSatelliteCount(); i++) {
                        if (status.usedInFix(i)) used++;
                    }
                    csvSatellites = used;
                    tvSatellites.setText("Satellites: " + used);
                }
            };
        }

        requestNeededPermissions();
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private void requestNeededPermissions() {
        java.util.List<String> needed = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (!needed.isEmpty())
            requestPermissions(needed.toArray(new String[0]), LOCATION_PERMISSION_REQUEST);
        else
            startLocationUpdates();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        startLocationUpdates();
    }

    // ── Location ──────────────────────────────────────────────────────────────

    private void startLocationUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            tvLat.setText("Location permission denied");
            return;
        }
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60000, 0, this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssCallback != null)
            locationManager.registerGnssStatusCallback(gnssCallback);
        Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (last == null) last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        if (last != null) updateDisplay(last);
    }

    private void updateDisplay(Location loc) {
        csvLat      = loc.getLatitude();
        csvLon      = loc.getLongitude();
        csvAlt      = loc.getAltitude();
        csvAccuracy = loc.getAccuracy();
        tvLat.setText(String.format("Lat: %.6f",       csvLat));
        tvLon.setText(String.format("Lon: %.6f",       csvLon));
        tvAlt.setText(String.format("Alt: %.1f m",     csvAlt));
        tvAccuracy.setText(String.format("Accuracy: %.1f m", csvAccuracy));
        mapView.setLocation(csvLat, csvLon);
    }

    @Override public void onLocationChanged(Location location) { updateDisplay(location); }
    @Override public void onStatusChanged(String p, int s, Bundle e) {}
    @Override public void onProviderEnabled(String p) {}
    @Override public void onProviderDisabled(String p) {}

    // ── CSV saving ────────────────────────────────────────────────────────────

    private void saveToCsv() {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            return;
        File dir = docsDir();
        Date now  = new Date();
        File file = new File(dir, dateFmt.format(now) + "-hereiamnow.csv");
        boolean isNew = !file.exists();
        try {
            FileWriter fw = new FileWriter(file, true);
            if (isNew)
                fw.write("timestamp,date,time,latitude,longitude,altitude_m,accuracy_m,satellites,battery_pct\n");
            fw.write(String.format(Locale.US, "%s,%s,%s,%.6f,%.6f,%.1f,%.1f,%d,%d\n",
                tsFmt.format(now), dateFmt.format(now), timeFmt.format(now),
                csvLat, csvLon, csvAlt, csvAccuracy, csvSatellites, csvBattery));
            fw.close();
        } catch (IOException ignored) {}

        deleteOldFiles(dir, "-hereiamnow.csv");
    }

    // ── GPX saving ────────────────────────────────────────────────────────────

    private void saveToGpx() {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            return;
        File dir  = docsDir();
        Date now  = new Date();
        File file = new File(dir, dateFmt.format(now) + "-hereiamnow.gpx");

        try {
            if (!file.exists()) {
                // Create new GPX file with header
                FileWriter fw = new FileWriter(file);
                fw.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
                fw.write("<gpx version=\"1.1\" creator=\"Here I Am Now\"\n");
                fw.write("    xmlns=\"http://www.topografix.com/GPX/1/1\"\n");
                fw.write("    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
                fw.write("    xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n");
                fw.write("  <trk>\n");
                fw.write("    <name>" + dateFmt.format(now) + "</name>\n");
                fw.write("    <trkseg>\n");
                fw.close();
            } else {
                // Strip closing tags so we can append before them
                long closeBytes = GPX_CLOSE.getBytes("UTF-8").length;
                RandomAccessFile raf = new RandomAccessFile(file, "rw");
                long newLen = raf.length() - closeBytes;
                if (newLen > 0) raf.setLength(newLen);
                raf.close();
            }

            // Append the new trackpoint + closing tags
            FileWriter fw = new FileWriter(file, true);
            fw.write(String.format(Locale.US,
                "      <trkpt lat=\"%.6f\" lon=\"%.6f\">\n" +
                "        <ele>%.1f</ele>\n" +
                "        <time>%s</time>\n" +
                "        <sat>%d</sat>\n" +
                "        <hdop>%.1f</hdop>\n" +
                "      </trkpt>\n",
                csvLat, csvLon, csvAlt, isoFmt.format(now), csvSatellites, csvAccuracy));
            fw.write(GPX_CLOSE);
            fw.close();
        } catch (IOException ignored) {}

        deleteOldFiles(dir, "-hereiamnow.gpx");
    }

    // ── File helpers ──────────────────────────────────────────────────────────

    private File docsDir() {
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void deleteOldFiles(File dir, String suffix) {
        long cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            if (!name.endsWith(suffix) || name.length() < 10) continue;
            try {
                Date fileDate = dateFmt.parse(name.substring(0, 10));
                if (fileDate != null && fileDate.getTime() < cutoff) f.delete();
            } catch (java.text.ParseException ignored) {}
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onResume() {
        super.onResume();
        clockHandler.post(clockTick);
        logHandler.postDelayed(logTick, 60000);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            startLocationUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();
        clockHandler.removeCallbacks(clockTick);
        logHandler.removeCallbacks(logTick);
        locationManager.removeUpdates(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssCallback != null)
            locationManager.unregisterGnssStatusCallback(gnssCallback);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(batteryReceiver);
    }
}
