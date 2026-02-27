package com.example.helloworld;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity implements LocationListener {

    private static final int LOCATION_PERMISSION_REQUEST = 1;
    private TextView tvLat, tvLon;
    private LocationManager locationManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvLat = (TextView) findViewById(R.id.tv_lat);
        tvLon = (TextView) findViewById(R.id.tv_lon);

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION_REQUEST);
        } else {
            startLocationUpdates();
        }
    }

    private void startLocationUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 0, this);

        Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (last == null) {
            last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        }
        if (last != null) {
            updateDisplay(last);
        }
    }

    private void updateDisplay(Location loc) {
        tvLat.setText(String.format("Lat: %.6f", loc.getLatitude()));
        tvLon.setText(String.format("Lon: %.6f", loc.getLongitude()));
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
            tvLat.setText("Lat: permission denied");
            tvLon.setText("Lon: permission denied");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        locationManager.removeUpdates(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
        }
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}
}
