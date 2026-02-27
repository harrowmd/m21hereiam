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
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity implements LocationListener {

    private static final int LOCATION_PERMISSION_REQUEST = 1;

    private MapView mapView;
    private TextView tvLat, tvLon, tvAlt, tvAccuracy, tvSatellites, tvBattery, tvDate, tvTime;
    private LocationManager locationManager;
    private GnssStatus.Callback gnssCallback;

    private final Handler clockHandler = new Handler();
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            tvDate.setText("Date: " + dateFmt.format(now));
            tvTime.setText("Time: " + timeFmt.format(now));
            clockHandler.postDelayed(this, 1000);
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int pct = (scale > 0) ? (level * 100 / scale) : -1;
            tvBattery.setText("Battery: " + pct + "%");
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
                    tvSatellites.setText("Satellites: " + used);
                }
            };
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION_REQUEST);
        } else {
            startLocationUpdates();
        }
    }

    private void startLocationUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;

        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60000, 0, this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssCallback != null) {
            locationManager.registerGnssStatusCallback(gnssCallback);
        }

        Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (last == null) last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        if (last != null) updateDisplay(last);
    }

    private void updateDisplay(Location loc) {
        tvLat.setText(String.format("Lat: %.6f", loc.getLatitude()));
        tvLon.setText(String.format("Lon: %.6f", loc.getLongitude()));
        tvAlt.setText(String.format("Alt: %.1f m", loc.getAltitude()));
        tvAccuracy.setText(String.format("Accuracy: %.1f m", loc.getAccuracy()));
        mapView.setLocation(loc.getLatitude(), loc.getLongitude());
    }

    @Override
    public void onLocationChanged(Location location) {
        updateDisplay(location);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == LOCATION_PERMISSION_REQUEST &&
                grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
        } else {
            tvLat.setText("Location permission denied");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        clockHandler.post(clockTick);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        clockHandler.removeCallbacks(clockTick);
        locationManager.removeUpdates(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssCallback != null) {
            locationManager.unregisterGnssStatusCallback(gnssCallback);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(batteryReceiver);
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}
}
