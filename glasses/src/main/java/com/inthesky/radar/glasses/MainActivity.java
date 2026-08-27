package com.inthesky.radar.glasses;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import com.rokid.cxr.CXRServiceBridge;
import com.rokid.cxr.Caps;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String RADAR_CHANNEL = "inthesky_radar_state";
    private RadarView radar;
    private volatile boolean running = true;
    private CXRServiceBridge cxrBridge;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        radar = new RadarView();
        setContentView(radar);
        startCxrReceiver();
    }

    private void startCxrReceiver() {
        try {
            cxrBridge = new CXRServiceBridge();
            cxrBridge.setStatusListener(new CXRServiceBridge.StatusListener() {
                @Override public void onConnecting(String deviceInfo, String macAddress, int deviceType) {
                    radar.setLinkState("CXR • CONNECTING");
                }
                @Override public void onConnected(String deviceInfo, String macAddress, int deviceType) {
                    radar.setLinkState("CXR • PHONE CONNECTED");
                }
                @Override public void onDisconnected() { radar.setLinkState("CXR • WAITING FOR PHONE"); }
                @Override public void onARTCStatus(float quality, boolean isHealthy) {}
                @Override public void onRokidAccountChanged(String account) {}
            });
            int result = cxrBridge.subscribe(RADAR_CHANNEL, new CXRServiceBridge.MsgCallback() {
                @Override public void onReceive(String channel, Caps args, byte[] data) {
                    try {
                        String json = null;
                        for (int i=0; i<args.size(); i++) {
                            Caps.Value v = args.at(i);
                            if (v != null && v.type() == Caps.Value.TYPE_STRING) { json = v.getString(); break; }
                        }
                        if (json != null) radar.applyPacket(new JSONObject(json));
                    } catch (Exception ignored) {}
                }
            });
            radar.setLinkState(result == 0 ? "CXR • WAITING FOR PHONE" : "CXR SUBSCRIBE • " + result);
        } catch (Throwable t) {
            radar.setLinkState("CXR SERVICE UNAVAILABLE");
        }
    }

    @Override protected void onDestroy() { running=false; super.onDestroy(); }

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
