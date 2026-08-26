package com.inthesky.radar.glasses;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    public static final UUID RADAR_UUID = UUID.fromString("9d9a9c20-a3cc-4a20-b5a2-34f5f6b8c701");
    private static final int REQ_BT = 601;
    private RadarView radar;
    private final ExecutorService serverWorker = Executors.newSingleThreadExecutor();
    private volatile boolean running = true;
    private BluetoothServerSocket serverSocket;
    private BluetoothSocket clientSocket;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        radar = new RadarView();
        setContentView(radar);
        requestBtAndStart();
    }

    private void requestBtAndStart() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE}, REQ_BT);
        } else startServer();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_BT) startServer();
    }

    private void startServer() {
        serverWorker.execute(() -> {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) { radar.setLinkState("NO BLUETOOTH"); return; }
            while (running) {
                try {
                    if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return;
                    radar.setLinkState("WAITING FOR PHONE");
                    serverSocket = adapter.listenUsingRfcommWithServiceRecord("InTheSkyRadar", RADAR_UUID);
                    clientSocket = serverSocket.accept();
                    radar.setLinkState("PHONE CONNECTED");
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while (running && (line = br.readLine()) != null) {
                            try { radar.applyPacket(new JSONObject(line)); } catch(Exception ignored) {}
                        }
                    }
                } catch (Exception ignored) {
                    if (running) radar.setLinkState("LINK LOST • WAITING");
                } finally { closeSockets(); }
            }
        });
    }

    private void closeSockets() {
        try { if(clientSocket!=null) clientSocket.close(); } catch(Exception ignored){} clientSocket=null;
        try { if(serverSocket!=null) serverSocket.close(); } catch(Exception ignored){} serverSocket=null;
    }

    @Override protected void onDestroy() { running=false; closeSockets(); serverWorker.shutdownNow(); super.onDestroy(); }

    private class RadarView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bright = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<Contact> contacts = new ArrayList<>();
        private float sweepDeg = 0f;
        private int rangeMi = 25;
        private String linkState = "STARTING";
        private long lastPacket = 0;

        RadarView() {
            super(MainActivity.this);
            setBackgroundColor(Color.BLACK);
            p.setColor(Color.rgb(79,255,159)); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2f);
            dim.setColor(Color.argb(135,79,255,159)); dim.setStyle(Paint.Style.STROKE); dim.setStrokeWidth(1.2f);
            bright.setColor(Color.rgb(150,255,190)); bright.setStyle(Paint.Style.FILL); bright.setTextAlign(Paint.Align.CENTER);
            fill.setColor(Color.argb(45,79,255,159)); fill.setStyle(Paint.Style.FILL);
            post(animator);
        }

        private final Runnable animator = new Runnable() {
            public void run() { sweepDeg=(sweepDeg+2.2f)%360f; invalidate(); if(running) postDelayed(this,33); }
        };

        void setLinkState(String s) { post(() -> { linkState=s; invalidate(); }); }

        void applyPacket(JSONObject o) {
            if (!"radar_state".equals(o.optString("type"))) return;
            final int r=Math.max(1,Math.min(200,o.optInt("rangeMi",25)));
            final List<Contact> next=new ArrayList<>();
            JSONArray a=o.optJSONArray("aircraft");
            if(a!=null) for(int i=0;i<a.length();i++) {
                JSONObject x=a.optJSONObject(i); if(x==null) continue;
                Contact c=new Contact(); c.callsign=x.optString("callsign",""); c.id=x.optString("id","");
                c.bearing=(float)x.optDouble("bearing",0); c.distance=(float)x.optDouble("distanceMi",0);
                c.altFt=x.optInt("altitudeFt",0); c.speedKt=x.optInt("speedKt",0); c.track=x.optInt("track",0); c.onGround=x.optBoolean("onGround",false);
                next.add(c);
            }
            next.sort(Comparator.comparingDouble(c -> c.distance));
            post(() -> { synchronized(contacts){contacts.clear();contacts.addAll(next);} rangeMi=r; lastPacket=System.currentTimeMillis(); linkState="LIVE"; invalidate(); });
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w=getWidth(), h=getHeight();
            float cx=w/2f;
            float radius=Math.min(w*0.43f, h*0.34f);
            float cy=Math.min(h*0.46f, radius+82f);

            bright.setTextSize(Math.max(15f,w*0.038f)); bright.setTextAlign(Paint.Align.LEFT);
            c.drawText("IN THE SKY",18,34,bright);
            bright.setTextAlign(Paint.Align.RIGHT); c.drawText(linkState,w-18,34,bright);

            for(int i=1;i<=4;i++) c.drawCircle(cx,cy,radius*i/4f,dim);
            c.drawLine(cx-radius,cy,cx+radius,cy,dim); c.drawLine(cx,cy-radius,cx,cy+radius,dim);

            bright.setTextSize(Math.max(16f,w*0.045f)); bright.setTextAlign(Paint.Align.CENTER);
            c.drawText("N",cx,cy-radius-10,bright); c.drawText("S",cx,cy+radius+24,bright);
            c.drawText("W",cx-radius-18,cy+6,bright); c.drawText("E",cx+radius+18,cy+6,bright);

            float ang=(float)Math.toRadians(sweepDeg-90f);
            Path wedge=new Path(); wedge.moveTo(cx,cy);
            float a1=(float)Math.toRadians(sweepDeg-102f), a2=(float)Math.toRadians(sweepDeg-90f);
            wedge.lineTo(cx+(float)Math.cos(a1)*radius,cy+(float)Math.sin(a1)*radius);
            wedge.arcTo(cx-radius,cy-radius,cx+radius,cy+radius,sweepDeg-102f,12f,false);
            wedge.close(); c.drawPath(wedge,fill);
            c.drawLine(cx,cy,cx+(float)Math.cos(a2)*radius,cy+(float)Math.sin(a2)*radius,p);
            c.drawCircle(cx,cy,4f,bright);

            List<Contact> snapshot; synchronized(contacts){snapshot=new ArrayList<>(contacts);}
            for(Contact ac:snapshot) {
                if(ac.distance>rangeMi) continue;
                double rr=radius*(ac.distance/rangeMi), rad=Math.toRadians(ac.bearing-90.0);
                float x=cx+(float)(Math.cos(rad)*rr), y=cy+(float)(Math.sin(rad)*rr);
                drawAircraft(c,x,y,ac.track);
            }

            bright.setTextAlign(Paint.Align.LEFT); bright.setTextSize(Math.max(16f,w*0.043f));
            c.drawText(rangeMi+" MI",18,h-82,bright);
            bright.setTextAlign(Paint.Align.RIGHT); c.drawText(snapshot.size()+" CONTACT"+(snapshot.size()==1?"":"S"),w-18,h-82,bright);

            if(!snapshot.isEmpty()) {
                Contact n=snapshot.get(0); String cs=n.callsign.isEmpty()?n.id.toUpperCase(Locale.ROOT):n.callsign;
                bright.setTextAlign(Paint.Align.CENTER); bright.setTextSize(Math.max(18f,w*0.05f));
                c.drawText(cs+"  "+String.format(Locale.US,"%.1f MI",n.distance)+"  "+n.altFt+" FT",cx,h-42,bright);
            } else {
                bright.setTextAlign(Paint.Align.CENTER); bright.setTextSize(Math.max(16f,w*0.043f));
                String s=lastPacket==0?"AWAITING RADAR DATA":"CLEAR"; c.drawText(s,cx,h-42,bright);
            }
        }

        private void drawAircraft(Canvas c,float x,float y,int track) {
            c.save(); c.rotate(track,x,y); Path a=new Path();
            a.moveTo(x,y-8); a.lineTo(x+2,y-2); a.lineTo(x+8,y+1); a.lineTo(x+2,y+2); a.lineTo(x+2,y+7);
            a.lineTo(x+5,y+9); a.lineTo(x,y+8); a.lineTo(x-5,y+9); a.lineTo(x-2,y+7); a.lineTo(x-2,y+2); a.lineTo(x-8,y+1); a.lineTo(x-2,y-2); a.close();
            c.drawPath(a,bright); c.restore();
        }
    }

    private static class Contact { String callsign,id; float bearing,distance; int altFt,speedKt,track; boolean onGround; }
}
