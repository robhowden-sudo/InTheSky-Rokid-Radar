package com.inthesky.radar.phone;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Pair;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.text.InputType;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.UnknownHostException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.rokid.cxr.Caps;
import com.rokid.cxr.link.CXRLink;
import com.rokid.cxr.link.callbacks.ICXRLinkCbk;
import com.rokid.cxr.link.callbacks.ICXRSessionCbk;
import com.rokid.cxr.link.callbacks.ICustomCmdCbk;
import com.rokid.cxr.link.callbacks.IGlassAppCbk;
import com.rokid.cxr.link.utils.CxrDefs;
import com.rokid.cxr.link.utils.GlassInfo;
import com.rokid.sprite.aiapp.externalapp.auth.AuthResult;
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper;

public class MainActivity extends Activity implements LocationListener, SensorEventListener {
    private static final String RADAR_CHANNEL = "inthesky_radar_state";
    private static final int REQ_PERMS = 501;
    private static final int REQ_HI_ROKID_AUTH = 502;
    private static final long REFRESH_MS = 30_000L;

    // One worker maintains the timed refresh loop; the second handles immediate
    // connect/range-change refreshes instead of leaving them behind its sleep.
    private final ExecutorService worker = Executors.newFixedThreadPool(5);
    private LocationManager locationManager;
    private SensorManager sensorManager;
    private Location lastLocation;
    private CXRLink cxrLink;
    private volatile boolean glassesConnected = false;
    private volatile boolean sessionReady = false;
    private volatile boolean appStartRequested = false;
    private volatile boolean deliveryBurstRunning = false;
    private volatile boolean restartInProgress = false;
    private volatile long lastGlassesAckMs = 0L;
    private volatile long sessionReadyMs = 0L;
    private volatile boolean glassesUserClosed = false;
    private volatile String connectedDeviceName = "ROKID GLASSES";
    private volatile boolean running = true;
    private int rangeMiles = 25;
    private volatile long lastSuccessMs = 0L;
    private volatile JSONObject lastGoodPacket = null;
    private volatile String openskyToken = null;
    private volatile long openskyTokenExpiryMs = 0L;
    private volatile long openskyRetryAfterMs = 0L;
    private String openskyClientId = "";
    private String openskyClientSecret = "";
    private boolean autoCompass = true;
    private int compassOffsetDeg = -90;
    private volatile float headingDeg = 0f;
    private volatile float lastHeadingSentDeg = -999f;
    private volatile long lastHeadingSentMs = 0L;
    private boolean alertsEnabled = true;
    private int alertMiles = 5;
    private final Set<String> alertContacts = new HashSet<>();
    private boolean alertInitialized = false;
    private ToneGenerator alertTone;

    private TextView status;
    private TextView rangeLabel;
    private TextView aircraftLabel;
    private TextView lastUpdateLabel;
    private TextView headingLabel;
    private TextView alertLabel;
    private Button connectButton;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        locationManager = (LocationManager)getSystemService(LOCATION_SERVICE);
        sensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
        cxrLink = new CXRLink(this);
        configureHiRokidLink();
        rangeMiles = Math.max(1, Math.min(200, getPreferences(MODE_PRIVATE).getInt("range_miles", 25)));
        openskyClientId = getPreferences(MODE_PRIVATE).getString("opensky_client_id", "");
        openskyClientSecret = getPreferences(MODE_PRIVATE).getString("opensky_client_secret", "");
        autoCompass = getPreferences(MODE_PRIVATE).getBoolean("auto_compass", true);
        compassOffsetDeg = getPreferences(MODE_PRIVATE).getInt("compass_offset", -90);
        alertsEnabled = getPreferences(MODE_PRIVATE).getBoolean("alerts_enabled", true);
        alertMiles = Math.max(1, Math.min(200, getPreferences(MODE_PRIVATE).getInt("alert_miles", 5)));
        alertTone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80);
        setContentView(buildUi());
        Sensor rotation=sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if(rotation!=null)sensorManager.registerListener(this,rotation,SensorManager.SENSOR_DELAY_UI);
        Intent keepAlive = new Intent(this, RadarKeepAliveService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(keepAlive); else startService(keepAlive);
        requestPermissionsIfNeeded();
        worker.execute(this::updateLoop);
        worker.execute(this::ackWatchdog);
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
        connectButton.setText("CONNECT THROUGH HI ROKID");
        connectButton.setOnClickListener(v -> {
            if (glassesConnected && glassesUserClosed) reopenGlassesRadarApp();
            else authorizeHiRokid();
        });
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
            public void onStopTrackingTouch(SeekBar s) { getPreferences(MODE_PRIVATE).edit().putInt("range_miles", rangeMiles).apply(); sendSettingsPacket(); worker.execute(MainActivity.this::fetchAndSendOnce); }
        });
        root.addView(range, new LinearLayout.LayoutParams(-1,-2));

        CheckBox compass = new CheckBox(this); compass.setText("AUTO COMPASS ORIENTATION"); compass.setTextColor(green); compass.setChecked(autoCompass);
        compass.setOnCheckedChangeListener((b,checked)->{autoCompass=checked;getPreferences(MODE_PRIVATE).edit().putBoolean("auto_compass",checked).apply();sendSettingsPacket();}); root.addView(compass);
        headingLabel=text("COMPASS  0°   CALIBRATION  "+signed(compassOffsetDeg)+"°",14,green); headingLabel.setPadding(0,10,0,4); root.addView(headingLabel);
        SeekBar calibration=new SeekBar(this); calibration.setMax(360); calibration.setProgress(compassOffsetDeg+180); calibration.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int p,boolean user){compassOffsetDeg=p-180;headingLabel.setText("COMPASS  "+Math.round(headingDeg)+"°   CALIBRATION  "+signed(compassOffsetDeg)+"°");}
            public void onStartTrackingTouch(SeekBar s){} public void onStopTrackingTouch(SeekBar s){getPreferences(MODE_PRIVATE).edit().putInt("compass_offset",compassOffsetDeg).apply();sendSettingsPacket();}
        }); root.addView(calibration,new LinearLayout.LayoutParams(-1,-2));

        CheckBox alerts=new CheckBox(this); alerts.setText("AIRCRAFT ENTRY ALERT"); alerts.setTextColor(green); alerts.setChecked(alertsEnabled);
        alerts.setOnCheckedChangeListener((b,checked)->{alertsEnabled=checked;alertInitialized=false;getPreferences(MODE_PRIVATE).edit().putBoolean("alerts_enabled",checked).apply();sendSettingsPacket();}); root.addView(alerts);
        alertLabel=text("ALERT ZONE  "+alertMiles+" MI",16,green); alertLabel.setPadding(0,10,0,4); root.addView(alertLabel);
        SeekBar alertRange=new SeekBar(this); alertRange.setMax(199); alertRange.setProgress(alertMiles-1); alertRange.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int p,boolean user){alertMiles=p+1;alertLabel.setText("ALERT ZONE  "+alertMiles+" MI");}
            public void onStartTrackingTouch(SeekBar s){} public void onStopTrackingTouch(SeekBar s){alertInitialized=false;getPreferences(MODE_PRIVATE).edit().putInt("alert_miles",alertMiles).apply();sendSettingsPacket();}
        }); root.addView(alertRange,new LinearLayout.LayoutParams(-1,-2));

        aircraftLabel = text("0 CONTACTS", 20, green);
        aircraftLabel.setPadding(0,40,0,8);
        root.addView(aircraftLabel);
        lastUpdateLabel = text("LAST LIVE UPDATE  --", 13, Color.rgb(174,244,202));
        root.addView(lastUpdateLabel);

        TextView authTitle = text("OPENSKY API  •  OPTIONAL", 16, green);
        authTitle.setPadding(0,28,0,8); root.addView(authTitle);
        EditText clientId = new EditText(this); clientId.setHint("OpenSky Client ID"); clientId.setText(openskyClientId); clientId.setTextColor(green); clientId.setHintTextColor(Color.rgb(100,170,130)); root.addView(clientId);
        EditText clientSecret = new EditText(this); clientSecret.setHint("OpenSky Client Secret"); clientSecret.setText(openskyClientSecret); clientSecret.setTextColor(green); clientSecret.setHintTextColor(Color.rgb(100,170,130)); clientSecret.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD); root.addView(clientSecret);
        Button saveOpenSky = new Button(this); saveOpenSky.setText("SAVE OPENSKY CREDENTIALS"); saveOpenSky.setOnClickListener(v -> {
            openskyClientId=clientId.getText().toString().trim(); openskyClientSecret=clientSecret.getText().toString().trim();
            getPreferences(MODE_PRIVATE).edit().putString("opensky_client_id",openskyClientId).putString("opensky_client_secret",openskyClientSecret).apply();
            openskyToken=null; openskyTokenExpiryMs=0; openskyRetryAfterMs=0; setStatus(openskyClientId.isEmpty()?"OPENSKY ANONYMOUS MODE":"OPENSKY CREDENTIALS SAVED • AUTHENTICATING"); worker.execute(MainActivity.this::fetchAndSendOnce);
        }); root.addView(saveOpenSky,new LinearLayout.LayoutParams(-1,-2));

        TextView note = text("The phone handles GPS + OpenSky. Hi Rokid keeps ownership of the glasses connection and carries radar data to the HUD.\n\nKeep the glasses connected in Hi Rokid, then authorize this app.", 15, Color.rgb(174,244,202));
        note.setPadding(0,24,0,24);
        root.addView(note);

        Button hiRokid = new Button(this);
        hiRokid.setText("OPEN HI ROKID");
        hiRokid.setOnClickListener(v -> {
            Intent launch = getPackageManager().getLaunchIntentForPackage("com.rokid.sprite.global.aiapp");
            if (launch != null) startActivity(launch);
            else setStatus("HI ROKID APP NOT FOUND");
        });
        root.addView(hiRokid, new LinearLayout.LayoutParams(-1,-2));

        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.addView(root); return scroll;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); return t;
    }

    private void requestPermissionsIfNeeded() {
        ArrayList<String> needed = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
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

    @Override public void onSensorChanged(SensorEvent event) {
        float[] rotation=new float[9], orientation=new float[3];
        SensorManager.getRotationMatrixFromVector(rotation,event.values); SensorManager.getOrientation(rotation,orientation);
        headingDeg=(float)normalizeBearing(Math.toDegrees(orientation[0])+compassOffsetDeg);
        if(headingLabel!=null)runOnUiThread(()->headingLabel.setText("COMPASS  "+Math.round(headingDeg)+"°   CALIBRATION  "+signed(compassOffsetDeg)+"°"));
        long now=System.currentTimeMillis(); float change=Math.abs(headingDeg-lastHeadingSentDeg);change=Math.min(change,360f-change);
        if(autoCompass&&sessionReady&&now-lastHeadingSentMs>=750L&&(lastHeadingSentDeg<-360f||change>=2f)){
            lastHeadingSentMs=now;lastHeadingSentDeg=headingDeg;worker.execute(this::sendSettingsPacket);
        }
    }
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy) {}

    private void configureHiRokidLink() {
        cxrLink.setCXRCustomCmdCbk(new ICustomCmdCbk() {
            @Override public void onCustomCmdResult(String command, byte[] data) {
                if ("inthesky_radar_closed".equals(command)) {
                    if (!restartInProgress) {
                        glassesUserClosed = true;
                        sessionReady = false;
                        appStartRequested = false;
                        setStatus("RADAR HUD CLOSED ON GLASSES");
                        runOnUiThread(() -> connectButton.setText("OPEN RADAR HUD"));
                    }
                    return;
                }
                if (!"inthesky_radar_ack".equals(command)) return;
                glassesUserClosed = false;
                lastGlassesAckMs = System.currentTimeMillis();
                setStatus("LIVE • HI ROKID → " + connectedDeviceName + " • " + rangeMiles + " MI");
            }
        });
        cxrLink.setCXRLinkCbk(new ICXRLinkCbk() {
            @Override public void onCXRLConnected(boolean connected) {
                setStatus(connected ? "HI ROKID LINK CONNECTED • WAITING FOR GLASSES" : "HI ROKID LINK DISCONNECTED");
            }
            @Override public void onGlassBtConnected(boolean connected) {
                glassesConnected = connected;
                if (connected) {
                    setStatus("HI ROKID • CONNECTED TO " + connectedDeviceName + " • STARTING RADAR SESSION");
                    cxrLink.getGlassDeviceInfo();
                } else {
                    sessionReady = false;
                    appStartRequested = false;
                    setStatus("HI ROKID • GLASSES DISCONNECTED");
                }
            }
            @Override public void onGlassDeviceInfo(GlassInfo info) {
                if (info != null && info.deviceName != null && !info.deviceName.trim().isEmpty())
                    connectedDeviceName = info.deviceName.trim();
                setStatus("HI ROKID • CONNECTED TO " + connectedDeviceName + (sessionReady ? " • RADAR SESSION READY" : " • STARTING RADAR SESSION"));
            }
            @Override public void onGlassWearingStatus(boolean wearing) {}
            @Override public void onGlassAiAssistStart() {}
            @Override public void onGlassAiAssistStop() {}
            @Override public void onGlassAiInterrupt(boolean interrupted) {}
            @Override public void onGlassLauncherResume() {}
        });

        CxrDefs.CXRSession session = new CxrDefs.CXRSession(
            CxrDefs.CXRSessionType.CUSTOMAPP, "com.inthesky.radar.glasses");
        cxrLink.configCXRSession(session, new ICXRSessionCbk() {
            @Override public void onSessionAvailable(CxrDefs.CXRSessionReason reason) {
                if (glassesUserClosed) {
                    setStatus("RADAR HUD CLOSED • TAP OPEN RADAR HUD ON PHONE");
                    return;
                }
                setStatus("HI ROKID • CONNECTED TO " + connectedDeviceName + " • LAUNCHING RADAR HUD");
                startGlassesRadarApp();
            }
            @Override public void onSessionStart(CxrDefs.CXRSessionReason reason) {
                sessionReady = true;
                glassesConnected = true;
                glassesUserClosed = false;
                sessionReadyMs = System.currentTimeMillis();
                setStatus("HI ROKID • CONNECTED TO " + connectedDeviceName + " • RADAR SESSION READY");
                runOnUiThread(() -> connectButton.setText("REAUTHORIZE HI ROKID"));
                sendSettingsPacket();
                worker.execute(MainActivity.this::fetchAndSendOnce);
                startDeliveryBurst();
            }
            @Override public void onSessionPause(CxrDefs.CXRSessionReason reason) {
                sessionReady = false;
                setStatus("HI ROKID • RADAR SESSION PAUSED • " + reason);
            }
            @Override public void onSessionUnavailable(CxrDefs.CXRSessionReason reason) {
                sessionReady = false;
                appStartRequested = false;
                setStatus("HI ROKID • RADAR SESSION UNAVAILABLE • " + reason);
            }
        });
    }

    private void startGlassesRadarApp() {
        if (appStartRequested) return;
        appStartRequested = true;
        cxrLink.appStart("com.inthesky.radar.glasses.MainActivity", new IGlassAppCbk() {
            @Override public void onInstallAppResult(boolean success) {}
            @Override public void onUnInstallAppResult(boolean success) {}
            @Override public void onStopAppResult(boolean success) {}
            @Override public void onQueryAppResult(boolean installed) {}
            @Override public void onOpenAppResult(boolean success) {
                if (success) markRadarSessionReady();
                else {
                    appStartRequested = false;
                    setStatus("HI ROKID • COULD NOT LAUNCH RADAR ON " + connectedDeviceName);
                }
            }
            @Override public void onGlassAppResume(boolean resumed) {
                if (resumed) markRadarSessionReady();
            }
        });
    }

    private void reopenGlassesRadarApp() {
        glassesUserClosed = false;
        sessionReady = false;
        appStartRequested = false;
        lastGlassesAckMs = 0L;
        restartInProgress = true;
        setStatus("HI ROKID • CREATING FRESH RADAR SESSION");
        cxrLink.appStop(new IGlassAppCbk() {
            public void onInstallAppResult(boolean success){} public void onUnInstallAppResult(boolean success){}
            public void onStopAppResult(boolean success){finishHudRestart();} public void onQueryAppResult(boolean installed){}
            public void onOpenAppResult(boolean success){} public void onGlassAppResume(boolean resumed){}
        });
        runOnUiThread(()->connectButton.postDelayed(this::finishHudRestart,2500L));
    }

    private void markRadarSessionReady() {
        sessionReady = true;
        glassesConnected = true;
        glassesUserClosed = false;
        sessionReadyMs = System.currentTimeMillis();
        setStatus("HI ROKID • CONNECTED TO " + connectedDeviceName + " • RADAR SESSION READY");
        runOnUiThread(() -> connectButton.setText("REAUTHORIZE HI ROKID"));
        sendSettingsPacket();
        worker.execute(MainActivity.this::fetchAndSendOnce);
        startDeliveryBurst();
    }

    private synchronized void startDeliveryBurst() {
        if (deliveryBurstRunning) return;
        deliveryBurstRunning = true;
        worker.execute(() -> {
            try {
                for (int i=0; i<12 && running && sessionReady; i++) {
                    JSONObject packet = lastGoodPacket;
                    if (packet != null) sendPacket(packet);
                    Thread.sleep(2000L);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                deliveryBurstRunning = false;
            }
        });
    }

    private void authorizeHiRokid() {
        if (!AuthorizationHelper.INSTANCE.isRequiredHiRokidInstalled(this)) {
            setStatus("COMPATIBLE HI ROKID APP REQUIRED");
            return;
        }
        setStatus("REQUESTING HI ROKID AUTHORIZATION");
        Pair<Integer, Intent> immediate = AuthorizationHelper.INSTANCE.requestAuthorization(this, REQ_HI_ROKID_AUTH);
        if (immediate != null) handleHiRokidAuthorization(immediate.first, immediate.second);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_HI_ROKID_AUTH) return;
        handleHiRokidAuthorization(resultCode, data);
    }

    private void handleHiRokidAuthorization(int resultCode, Intent data) {
        AuthResult result = AuthorizationHelper.INSTANCE.parseAuthorizationResult(resultCode, data);
        if (result instanceof AuthResult.AuthSuccess) {
            String token = ((AuthResult.AuthSuccess)result).getToken();
            setStatus("HI ROKID AUTHORIZED • CONNECTING");
            cxrLink.connect(token);
        } else if (result instanceof AuthResult.AuthCancel) {
            setStatus("HI ROKID AUTHORIZATION CANCELLED");
        } else {
            setStatus("HI ROKID AUTHORIZATION FAILED");
        }
    }

    private void updateLoop() {
        while (running) {
            fetchAndSendOnce();
            try { Thread.sleep(REFRESH_MS); } catch (InterruptedException e) { return; }
        }
    }

    private void ackWatchdog() {
        while (running) {
            try { Thread.sleep(5000L); } catch (InterruptedException e) { return; }
            long baseline = Math.max(lastGlassesAckMs, sessionReadyMs);
            if (!glassesUserClosed && lastGlassesAckMs > 0L && sessionReady && baseline > 0 && System.currentTimeMillis()-baseline > 45_000L) restartGlassesHud();
        }
    }

    private synchronized void restartGlassesHud() {
        if (restartInProgress) return;
        restartInProgress = true;
        sessionReadyMs = System.currentTimeMillis();
        setStatus("GLASSES ACK STALE • RESTARTING RADAR HUD");
        cxrLink.appStop(new IGlassAppCbk() {
            @Override public void onInstallAppResult(boolean success) {}
            @Override public void onUnInstallAppResult(boolean success) {}
            @Override public void onStopAppResult(boolean success) { finishHudRestart(); }
            @Override public void onQueryAppResult(boolean installed) {}
            @Override public void onOpenAppResult(boolean success) {}
            @Override public void onGlassAppResume(boolean resumed) {}
        });
        runOnUiThread(() -> connectButton.postDelayed(this::finishHudRestart, 2500L));
    }

    private synchronized void finishHudRestart() {
        if (!restartInProgress) return;
        restartInProgress = false;
        appStartRequested = false;
        startGlassesRadarApp();
    }

    private void fetchAndSendOnce() {
        Location loc = lastLocation;
        if (loc == null) { setStatus(!glassesConnected ? "WAITING FOR GPS • GLASSES DISCONNECTED" : "WAITING FOR GPS • GLASSES CONNECTED"); return; }
        if (System.currentTimeMillis() < openskyRetryAfterMs) {
            long seconds=Math.max(1,(openskyRetryAfterMs-System.currentTimeMillis()+999)/1000);
            sendSettingsPacket(); setStatus("OPENSKY RATE LIMITED • RETRY IN "+seconds+" SEC"); return;
        }
        Exception lastError = null;
        for (int attempt=1; attempt<=3; attempt++) {
            try {
                if (attempt > 1) setStatus("RETRYING OPENSKY • " + attempt + "/3");
                JSONObject packet = fetchOpenSky(loc.getLatitude(), loc.getLongitude(), rangeMiles);
                lastGoodPacket = packet; lastSuccessMs = System.currentTimeMillis();
                updateEntryAlerts(packet.optJSONArray("aircraft"));
                int count = packet.getJSONArray("aircraft").length();
                runOnUiThread(() -> { aircraftLabel.setText(count + (count==1 ? " CONTACT" : " CONTACTS")); lastUpdateLabel.setText("LAST LIVE UPDATE  JUST NOW"); });
                sendPacket(packet);
                if (!glassesConnected) setStatus("LIVE • PHONE RADAR • " + rangeMiles + " MI");
                return;
            } catch (Exception e) {
                lastError=e;
                if (shortMsg(e).startsWith("RATE LIMITED")) break;
                if (attempt<3) try { Thread.sleep(attempt*1500L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
        updateLastSuccessAge();
        setStatus((lastGoodPacket != null ? "OFFLINE • USING LAST CONTACTS • " : "OPENSKY OFFLINE • ") + friendlyNetworkError(lastError));
        if (lastGoodPacket != null) sendPacket(lastGoodPacket);
        else sendSettingsPacket();
    }

    private void sendSettingsPacket() {
        if (!glassesConnected || !sessionReady) return;
        try {
            JSONObject packet=lastGoodPacket==null?new JSONObject():new JSONObject(lastGoodPacket.toString());
            packet.put("type","radar_state"); packet.put("v",2); packet.put("time",System.currentTimeMillis()); packet.put("rangeMi",rangeMiles);
            addDisplaySettings(packet);
            if(!packet.has("aircraft"))packet.put("aircraft",new JSONArray());
            sendPacket(packet);
        } catch(Exception ignored) {}
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
        String bearer=getOpenSkyToken(); if(bearer!=null)c.setRequestProperty("Authorization","Bearer "+bearer);
        int code = c.getResponseCode();
        if(code==429){long retry=60;try{retry=Long.parseLong(c.getHeaderField("X-Rate-Limit-Retry-After-Seconds"));}catch(Exception ignored){} openskyRetryAfterMs=System.currentTimeMillis()+Math.max(30,retry)*1000L;throw new Exception("RATE LIMITED • RETRY IN "+retry+" SEC");}
        if(code==401){openskyToken=null;openskyTokenExpiryMs=0;throw new Exception("OPENSKY AUTH FAILED • CHECK CLIENT ID/SECRET");}
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
                boolean altitudeKnown = !s.isNull(7) || !s.isNull(13);
                double altM = !s.isNull(7) ? s.optDouble(7,0) : (!s.isNull(13) ? s.optDouble(13,0) : 0);
                a.put("altitudeKnown", altitudeKnown);
                if (altitudeKnown) a.put("altitudeFt", Math.round(altM * 3.28084));
                a.put("onGround", s.optBoolean(8,false));
                a.put("speedKt", s.isNull(9)?0:Math.round(s.optDouble(9,0)*1.94384));
                a.put("track", s.isNull(10)?0:Math.round(s.optDouble(10,0)));
                a.put("category", s.length()>17 && !s.isNull(17) ? s.optInt(17,0) : 0);
                aircraft.put(a);
            }
        }
        JSONObject packet = new JSONObject();
        packet.put("type","radar_state"); packet.put("v",1); packet.put("time",System.currentTimeMillis());
        packet.put("rangeMi",rangeMi); packet.put("homeLat",lat); packet.put("homeLon",lon); packet.put("northUp",!autoCompass); packet.put("aircraft",aircraft); addDisplaySettings(packet);
        return packet;
    }

    private void addDisplaySettings(JSONObject packet) throws Exception {
        packet.put("autoCompass",autoCompass); packet.put("headingDeg",headingDeg);
        packet.put("alertsEnabled",alertsEnabled); packet.put("alertMi",Math.min(alertMiles,rangeMiles));
    }

    private synchronized void updateEntryAlerts(JSONArray aircraft) {
        Set<String> now=new HashSet<>();
        if(aircraft!=null)for(int i=0;i<aircraft.length();i++){JSONObject a=aircraft.optJSONObject(i);if(a!=null&&a.optDouble("distanceMi",999)<=alertMiles)now.add(a.optString("id",""));}
        if(alertsEnabled&&alertInitialized){for(String id:now)if(!id.isEmpty()&&!alertContacts.contains(id)){if(alertTone!=null)alertTone.startTone(ToneGenerator.TONE_PROP_BEEP2,450);break;}}
        alertContacts.clear();alertContacts.addAll(now);alertInitialized=true;
    }

    private synchronized String getOpenSkyToken() throws Exception {
        if(openskyClientId.isEmpty()||openskyClientSecret.isEmpty())return null;
        if(openskyToken!=null&&System.currentTimeMillis()<openskyTokenExpiryMs-30_000L)return openskyToken;
        HttpURLConnection c=(HttpURLConnection)new URL("https://auth.opensky-network.org/auth/realms/opensky-network/protocol/openid-connect/token").openConnection();
        c.setRequestMethod("POST");c.setDoOutput(true);c.setConnectTimeout(12000);c.setReadTimeout(12000);c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
        String body="grant_type=client_credentials&client_id="+URLEncoder.encode(openskyClientId,"UTF-8")+"&client_secret="+URLEncoder.encode(openskyClientSecret,"UTF-8");
        try(OutputStream out=c.getOutputStream()){out.write(body.getBytes(StandardCharsets.UTF_8));}
        int code=c.getResponseCode();if(code<200||code>=300)throw new Exception("OPENSKY AUTH HTTP "+code);
        StringBuilder sb=new StringBuilder();try(BufferedReader br=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))){String line;while((line=br.readLine())!=null)sb.append(line);}
        JSONObject data=new JSONObject(sb.toString());openskyToken=data.optString("access_token","");if(openskyToken.isEmpty())throw new Exception("OPENSKY AUTH TOKEN MISSING");
        openskyTokenExpiryMs=System.currentTimeMillis()+Math.max(60,data.optLong("expires_in",300))*1000L;return openskyToken;
    }

    private static double normalizeBearing(double b) { b %= 360.0; return b < 0 ? b + 360.0 : b; }
    private static double round1(double v) { return Math.round(v*10.0)/10.0; }
    private static String signed(int v){return v>0?"+"+v:String.valueOf(v);}

    private synchronized void sendPacket(JSONObject packet) {
        if (!glassesConnected || !sessionReady) return;
        try {
            addDisplaySettings(packet); packet.put("rangeMi",rangeMiles);
            Caps args = new Caps();
            args.write(packet.toString());
            Integer result = cxrLink.sendCustomCmd(RADAR_CHANNEL, args);
            if (result != null && result == 0 && System.currentTimeMillis()-lastGlassesAckMs < 45_000L)
                setStatus("LIVE • HI ROKID → " + connectedDeviceName + " • " + rangeMiles + " MI");
            else if (result != null && result == 0)
                setStatus("SENDING • AWAITING " + connectedDeviceName + " ACK");
            else
                setStatus("RADAR SEND RESULT " + String.valueOf(result) + " • " + connectedDeviceName);
        } catch (Exception e) {
            setStatus("HI ROKID RADAR SEND FAILED • " + shortMsg(e));
        }
    }

    private synchronized void closeSocket() {
        glassesConnected = false;
        sessionReady = false;
        appStartRequested = false;
        deliveryBurstRunning = false;
        restartInProgress = false;
        lastGlassesAckMs = 0L;
        try { cxrLink.disconnect(); } catch (Exception ignored) {}
    }

    private void setStatus(String s) { runOnUiThread(() -> status.setText(s)); }
    private String shortMsg(Exception e) { String s=e.getMessage(); return s==null?e.getClass().getSimpleName():s; }

    @Override protected void onDestroy() {
        running=false; worker.shutdownNow(); closeSocket();
        if(sensorManager!=null)sensorManager.unregisterListener(this);
        if(alertTone!=null)alertTone.release();
        try { if(locationManager!=null) locationManager.removeUpdates(this); } catch(Exception ignored){}
        super.onDestroy();
    }
}
