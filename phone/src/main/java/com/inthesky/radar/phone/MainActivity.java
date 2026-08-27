package com.inthesky.radar.phone;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.UnknownHostException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.rokid.cxr.Caps;
import com.rokid.cxr.client.extend.CxrApi;
import com.rokid.cxr.client.extend.callbacks.BluetoothStatusCallback;
import com.rokid.cxr.client.utils.ValueUtil;

public class MainActivity extends Activity implements LocationListener {
    private static final String RADAR_CHANNEL = "inthesky_radar_state";
    private static final int REQ_PERMS = 501;
    private static final long REFRESH_MS = 30_000L;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LocationManager locationManager;
    private Location lastLocation;
    private BluetoothAdapter bluetooth;
    private final CxrApi cxr = CxrApi.getInstance();
    private volatile boolean glassesConnected = false;
    private volatile boolean running = true;
    private int rangeMiles = 25;
    private volatile long lastSuccessMs = 0L;
    private volatile JSONObject lastGoodPacket = null;

    private TextView status;
    private TextView rangeLabel;
    private TextView aircraftLabel;
    private TextView lastUpdateLabel;
    private Button connectButton;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        bluetooth = BluetoothAdapter.getDefaultAdapter();
        locationManager = (LocationManager)getSystemService(LOCATION_SERVICE);
        rangeMiles = Math.max(1, Math.min(200, getPreferences(MODE_PRIVATE).getInt("range_miles", 25)));
        setContentView(buildUi());
        requestPermissionsIfNeeded();
        worker.execute(this::updateLoop);
    }

    private View buildUi() {
        int green = Color.rgb(79,255,159);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32,48,32,32);
        root.setBackgroundColor(Color.BLACK);

        TextView title = text("IN THE SKY  •  ROKID RADAR", 24, green);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, new LinearLayout.LayoutParams(-1,-2));

        status = text("PHONE READY • GLASSES DISCONNECTED", 14, green);
        status.setPadding(0,32,0,24);
        root.addView(status);

        connectButton = new Button(this);
        connectButton.setText("CONNECT ROKID GLASSES");
        connectButton.setOnClickListener(v -> choosePairedDevice());
        root.addView(connectButton, new LinearLayout.LayoutParams(-1,-2));

        rangeLabel = text("RADAR RANGE  " + rangeMiles + " MI", 18, green);
        rangeLabel.setPadding(0,40,0,8);
        root.addView(rangeLabel);

        SeekBar range = new SeekBar(this);
        range.setMax(199);
        range.setProgress(rangeMiles - 1);
        range.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                rangeMiles = p + 1;
                rangeLabel.setText("RADAR RANGE  " + rangeMiles + " MI");
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) { getPreferences(MODE_PRIVATE).edit().putInt("range_miles", rangeMiles).apply(); worker.execute(MainActivity.this::fetchAndSendOnce); }
        });
        root.addView(range, new LinearLayout.LayoutParams(-1,-2));

        aircraftLabel = text("0 CONTACTS", 20, green);
        aircraftLabel.setPadding(0,40,0,8);
        root.addView(aircraftLabel);
        lastUpdateLabel = text("LAST LIVE UPDATE  --", 13, Color.rgb(174,244,202));
        root.addView(lastUpdateLabel);

        TextView note = text("The phone handles GPS + OpenSky. The glasses only draw the HUD.\n\nPair the Rokid glasses in Android Bluetooth settings first, then connect here.", 15, Color.rgb(174,244,202));
        note.setPadding(0,24,0,24);
        root.addView(note);

        Button btSettings = new Button(this);
        btSettings.setText("OPEN BLUETOOTH SETTINGS");
        btSettings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        root.addView(btSettings, new LinearLayout.LayoutParams(-1,-2));

        return root;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); return t;
    }

    private void requestPermissionsIfNeeded() {
        ArrayList<String> needed = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (!needed.isEmpty()) requestPermissions(needed.toArray(new String[0]), REQ_PERMS);
        else startLocation();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_PERMS) startLocation();
    }

    private void startLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            setStatus("LOCATION PERMISSION REQUIRED"); return;
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 10f, this);
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000, 25f, this);
            Location g = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location n = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            lastLocation = g != null ? g : n;
        } catch (Exception e) { setStatus("LOCATION ERROR: " + e.getMessage()); }
    }

    @Override public void onLocationChanged(Location l) { lastLocation = l; }

    private void choosePairedDevice() {
        if (bluetooth == null) { setStatus("BLUETOOTH NOT AVAILABLE"); return; }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionsIfNeeded(); return;
        }
        Set<BluetoothDevice> bonded = bluetooth.getBondedDevices();
        if (bonded == null || bonded.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("No paired devices")
                .setMessage("Pair the Rokid glasses in Android Bluetooth settings first.")
                .setPositiveButton("Bluetooth settings", (d,w) -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
                .setNegativeButton("Cancel", null).show();
            return;
        }
        List<BluetoothDevice> devices = new ArrayList<>(bonded);
        String[] names = new String[devices.size()];
        int rokidIndex = -1;
        for (int i=0;i<devices.size();i++) {
            String n = devices.get(i).getName();
            names[i] = (n == null ? "Unknown device" : n) + "\n" + devices.get(i).getAddress();
            if (n != null && n.toLowerCase(Locale.ROOT).contains("rokid")) rokidIndex = i;
        }
        final int suggested = rokidIndex;
        new AlertDialog.Builder(this).setTitle(suggested >= 0 ? "Select Rokid glasses" : "Select paired glasses")
            .setItems(names, (d,which) -> connect(devices.get(which))).show();
    }

    private void connect(BluetoothDevice device) {
        setStatus("CXR • CONNECTING TO " + safeName(device));
        try { cxr.deinitBluetooth(); } catch (Exception ignored) {}
        glassesConnected = false;
        cxr.initBluetooth(this, device, new BluetoothStatusCallback() {
            @Override public void onConnectionInfo(String socketUuid, String macAddress, String deviceName, int protocolVersion) {
                setStatus("CXR LINK FOUND • AUTHENTICATING");
            }
            @Override public void onConnected() {
                glassesConnected = true;
                setStatus("CXR • GLASSES CONNECTED • " + safeName(device));
                runOnUiThread(() -> connectButton.setText("RECONNECT / CHANGE GLASSES"));
                worker.execute(MainActivity.this::fetchAndSendOnce);
            }
            @Override public void onDisconnected() {
                glassesConnected = false;
                setStatus("CXR • GLASSES DISCONNECTED");
            }
            @Override public void onFailed(ValueUtil.CxrBluetoothErrorCode errorCode) {
                glassesConnected = false;
                setStatus("CXR CONNECTION FAILED • " + String.valueOf(errorCode));
            }
        });
    }

    private String safeName(BluetoothDevice d) {
        try { String n=d.getName(); return n==null?d.getAddress():n; } catch(Exception e){ return "ROKID"; }
    }

    private void updateLoop() {
        while (running) {
            fetchAndSendOnce();
            try { Thread.sleep(REFRESH_MS); } catch (InterruptedException e) { return; }
        }
    }

    private void fetchAndSendOnce() {
        Location loc = lastLocation;
        if (loc == null) { setStatus(!glassesConnected ? "WAITING FOR GPS • GLASSES DISCONNECTED" : "WAITING FOR GPS • GLASSES CONNECTED"); return; }
        Exception lastError = null;
        for (int attempt=1; attempt<=3; attempt++) {
            try {
                if (attempt > 1) setStatus("RETRYING OPENSKY • " + attempt + "/3");
                JSONObject packet = fetchOpenSky(loc.getLatitude(), loc.getLongitude(), rangeMiles);
                lastGoodPacket = packet; lastSuccessMs = System.currentTimeMillis();
                int count = packet.getJSONArray("aircraft").length();
                runOnUiThread(() -> { aircraftLabel.setText(count + (count==1 ? " CONTACT" : " CONTACTS")); lastUpdateLabel.setText("LAST LIVE UPDATE  JUST NOW"); });
                sendPacket(packet);
                if (!glassesConnected) setStatus("LIVE • PHONE RADAR • " + rangeMiles + " MI");
                return;
            } catch (Exception e) {
                lastError=e;
                if (attempt<3) try { Thread.sleep(attempt*1500L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
        updateLastSuccessAge();
        setStatus((lastGoodPacket != null ? "OFFLINE • USING LAST CONTACTS • " : "OPENSKY OFFLINE • ") + friendlyNetworkError(lastError));
        if (lastGoodPacket != null) sendPacket(lastGoodPacket);
    }

    private void updateLastSuccessAge() {
        if (lastSuccessMs <= 0) return;
        long sec=Math.max(0,(System.currentTimeMillis()-lastSuccessMs)/1000L);
        String age=sec<60?sec+" SEC AGO":sec<3600?(sec/60)+" MIN AGO":(sec/3600)+" HR AGO";
        runOnUiThread(() -> lastUpdateLabel.setText("LAST LIVE UPDATE  " + age));
    }

    private String friendlyNetworkError(Exception e) {
        if (e == null) return "UNKNOWN ERROR";
        if (e instanceof UnknownHostException) return "DNS TEMPORARILY UNAVAILABLE";
        String m=shortMsg(e);
        if (m.toLowerCase(Locale.ROOT).contains("timed out")) return "NETWORK TIMEOUT";
        return m;
    }

    private JSONObject fetchOpenSky(double lat, double lon, int rangeMi) throws Exception {
        double rangeKm = rangeMi * 1.609344;
        double latDelta = rangeKm / 111.0;
        double lonDelta = rangeKm / Math.max(20.0, 111.0 * Math.cos(Math.toRadians(lat)));
        String u = String.format(Locale.US,
            "https://opensky-network.org/api/states/all?lamin=%.5f&lamax=%.5f&lomin=%.5f&lomax=%.5f",
            lat-latDelta, lat+latDelta, lon-lonDelta, lon+lonDelta);
        HttpURLConnection c = (HttpURLConnection)new URL(u).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(15000); c.setUseCaches(false); c.setRequestProperty("Accept","application/json"); c.setRequestProperty("Connection","close"); c.setRequestProperty("User-Agent","InTheSky-Rokid-Radar/0.4");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line; while ((line=br.readLine()) != null) sb.append(line);
        }
        JSONObject src = new JSONObject(sb.toString());
        JSONArray states = src.optJSONArray("states");
        JSONArray aircraft = new JSONArray();
        if (states != null) {
            for (int i=0;i<states.length();i++) {
                JSONArray s = states.optJSONArray(i); if (s == null || s.length() < 11 || s.isNull(5) || s.isNull(6)) continue;
                double acLon=s.optDouble(5,Double.NaN), acLat=s.optDouble(6,Double.NaN);
                if (Double.isNaN(acLon)||Double.isNaN(acLat)) continue;
                float[] dr = new float[2]; Location.distanceBetween(lat,lon,acLat,acLon,dr);
                double miles = dr[0] / 1609.344; if (miles > rangeMi) continue;
                JSONObject a = new JSONObject();
                a.put("id", s.optString(0,""));
                a.put("callsign", s.optString(1,"").trim());
                a.put("country", s.optString(2,""));
                a.put("bearing", normalizeBearing(dr[1]));
                a.put("distanceMi", round1(miles));
                double altM = s.isNull(7) ? (s.isNull(13)?0:s.optDouble(13,0)) : s.optDouble(7,0);
                a.put("altitudeFt", Math.round(altM * 3.28084));
                a.put("onGround", s.optBoolean(8,false));
                a.put("speedKt", s.isNull(9)?0:Math.round(s.optDouble(9,0)*1.94384));
                a.put("track", s.isNull(10)?0:Math.round(s.optDouble(10,0)));
                aircraft.put(a);
            }
        }
        JSONObject packet = new JSONObject();
        packet.put("type","radar_state"); packet.put("v",1); packet.put("time",System.currentTimeMillis());
        packet.put("rangeMi",rangeMi); packet.put("homeLat",lat); packet.put("homeLon",lon); packet.put("northUp",true); packet.put("aircraft",aircraft);
        return packet;
    }

    private static double normalizeBearing(double b) { b %= 360.0; return b < 0 ? b + 360.0 : b; }
    private static double round1(double v) { return Math.round(v*10.0)/10.0; }

    private synchronized void sendPacket(JSONObject packet) {
        if (!glassesConnected || !cxr.isBluetoothConnected()) return;
        try {
            Caps args = new Caps();
            args.write(packet.toString());
            ValueUtil.CxrStatus result = cxr.sendCustomCmd(RADAR_CHANNEL, args);
            setStatus("LIVE • CXR GLASSES CONNECTED • " + rangeMiles + " MI");
        } catch (Exception e) {
            glassesConnected = false;
            setStatus("CXR SEND FAILED • " + shortMsg(e));
        }
    }

    private synchronized void closeSocket() {
        glassesConnected = false;
        try { cxr.deinitBluetooth(); } catch (Exception ignored) {}
    }

    private void setStatus(String s) { runOnUiThread(() -> status.setText(s)); }
    private String shortMsg(Exception e) { String s=e.getMessage(); return s==null?e.getClass().getSimpleName():s; }

    @Override protected void onDestroy() {
        running=false; worker.shutdownNow(); closeSocket();
        try { if(locationManager!=null) locationManager.removeUpdates(this); } catch(Exception ignored){}
        super.onDestroy();
    }
}
