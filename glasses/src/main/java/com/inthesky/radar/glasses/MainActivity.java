package com.inthesky.radar.glasses;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.view.View;
import android.view.KeyEvent;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import com.rokid.cxr.CXRServiceBridge;
import com.rokid.cxr.Caps;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final String RADAR_CHANNEL = "inthesky_radar_state";
    private RadarView radar;
    private volatile boolean running = true;
    private CXRServiceBridge cxrBridge;
    private volatile boolean cxrConnected = false;

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
                    cxrConnected = true;
                    radar.setLinkState("CXR • PHONE CONNECTED");
                }
                @Override public void onDisconnected() { cxrConnected=false; radar.setLinkState("CXR • WAITING FOR PHONE"); }
                @Override public void onARTCStatus(float quality, boolean isHealthy) {}
                @Override public void onRokidAccountChanged(String account) {}
                @Override public void onAudioNoise(float noiseLevel) {}
            });
            int result = cxrBridge.subscribe(RADAR_CHANNEL, new CXRServiceBridge.MsgCallback() {
                @Override public void onReceive(String channel, Caps args, byte[] data) {
                    try {
                        String json = null;
                        for (int i=0; i<args.size(); i++) {
                            Caps.Value v = args.at(i);
                            if (v != null && v.type() == Caps.Value.TYPE_STRING) { json = v.getString(); break; }
                        }
                        if (json != null) {
                            radar.applyPacket(new JSONObject(json));
                            Caps ack = new Caps(); ack.write("received");
                            cxrBridge.sendMessage("inthesky_radar_ack", ack, "ack".getBytes(StandardCharsets.UTF_8));
                        }
                    } catch (Exception ignored) {}
                }
            });
            radar.setLinkState(result == 0 ? "CXR • WAITING FOR PHONE" : "CXR SUBSCRIBE • " + result);
        } catch (Throwable t) {
            radar.setLinkState("CXR SERVICE UNAVAILABLE");
        }
    }

    @Override protected void onDestroy() {
        if (cxrConnected) try {
            Caps closed = new Caps(); closed.write("closed");
            cxrBridge.sendMessage("inthesky_radar_closed", closed, "closed".getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
        running=false; super.onDestroy();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if(keyCode==KeyEvent.KEYCODE_DPAD_RIGHT||keyCode==KeyEvent.KEYCODE_VOLUME_DOWN){radar.selectContact(1);return true;}
        if(keyCode==KeyEvent.KEYCODE_DPAD_LEFT||keyCode==KeyEvent.KEYCODE_VOLUME_UP){radar.selectContact(-1);return true;}
        if(keyCode==KeyEvent.KEYCODE_DPAD_CENTER||keyCode==KeyEvent.KEYCODE_ENTER){radar.selectContact(1);return true;}
        return super.onKeyDown(keyCode,event);
    }

    private class RadarView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bright = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint alert = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<Contact> contacts = new ArrayList<>();
        private float sweepDeg = 0f;
        private int rangeMi = 25;
        private String linkState = "STARTING";
        private long lastPacket = 0;
        private boolean autoCompass = false;
        private float headingDeg = 0f;
        private boolean alertsEnabled = false;
        private int alertMi = 5;
        private int selectedIndex = 0;

        RadarView() {
            super(MainActivity.this);
            setBackgroundColor(Color.BLACK);
            p.setColor(Color.rgb(79,255,159)); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2f);
            dim.setColor(Color.argb(135,79,255,159)); dim.setStyle(Paint.Style.STROKE); dim.setStrokeWidth(1.2f);
            bright.setColor(Color.rgb(150,255,190)); bright.setStyle(Paint.Style.FILL); bright.setTextAlign(Paint.Align.CENTER);
            fill.setColor(Color.argb(45,79,255,159)); fill.setStyle(Paint.Style.FILL);
            alert.setColor(Color.rgb(255,190,70)); alert.setStyle(Paint.Style.STROKE); alert.setStrokeWidth(2.4f);
            post(animator);
        }

        private final Runnable animator = new Runnable() {
            public void run() { sweepDeg=(sweepDeg+2.2f)%360f; invalidate(); if(running) postDelayed(this,33); }
        };

        void setLinkState(String s) { post(() -> { linkState=s; invalidate(); }); }

        void selectContact(int delta) { post(()->{synchronized(contacts){if(!contacts.isEmpty())selectedIndex=(selectedIndex+delta+contacts.size())%contacts.size();}invalidate();}); }

        void applyPacket(JSONObject o) {
            if (!"radar_state".equals(o.optString("type"))) return;
            final int r=Math.max(1,Math.min(200,o.optInt("rangeMi",25)));
            final List<Contact> next=new ArrayList<>();
            JSONArray a=o.optJSONArray("aircraft");
            if(a!=null) for(int i=0;i<a.length();i++) {
                JSONObject x=a.optJSONObject(i); if(x==null) continue;
                Contact c=new Contact(); c.callsign=x.optString("callsign",""); c.id=x.optString("id","");
                c.bearing=(float)x.optDouble("bearing",0); c.distance=(float)x.optDouble("distanceMi",0);
                c.altitudeKnown=x.optBoolean("altitudeKnown",x.has("altitudeFt")); c.altFt=x.optInt("altitudeFt",0); c.speedKt=x.optInt("speedKt",0); c.track=x.optInt("track",0); c.onGround=x.optBoolean("onGround",false); c.category=x.optInt("category",0);
                next.add(c);
            }
            next.sort(Comparator.comparingDouble(c -> c.distance));
            final boolean orient=o.optBoolean("autoCompass",false); final float heading=(float)o.optDouble("headingDeg",0);
            final boolean alerts=o.optBoolean("alertsEnabled",false); final int zone=Math.max(1,o.optInt("alertMi",5));
            post(() -> { synchronized(contacts){contacts.clear();contacts.addAll(next);if(selectedIndex>=contacts.size())selectedIndex=0;} rangeMi=r;autoCompass=orient;headingDeg=heading;alertsEnabled=alerts;alertMi=zone;lastPacket=System.currentTimeMillis();linkState="LIVE";invalidate(); });
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w=getWidth(), h=getHeight();
            float cx=w/2f;
            float radius=Math.min(w*0.43f, h*0.34f);
            float cy=Math.min(h*0.46f, radius+82f);

            bright.setTextSize(Math.max(15f,w*0.038f)); bright.setTextAlign(Paint.Align.LEFT);
            c.drawText("IN THE SKY",18,34,bright);
            String status=linkState;
            if(lastPacket>0){long age=(System.currentTimeMillis()-lastPacket)/1000L;status=(age>75?"STALE":"LIVE")+" • "+age+"S";}
            bright.setTextAlign(Paint.Align.RIGHT); c.drawText(status,w-18,34,bright);

            for(int i=1;i<=4;i++) c.drawCircle(cx,cy,radius*i/4f,dim);
            c.drawLine(cx-radius,cy,cx+radius,cy,dim); c.drawLine(cx,cy-radius,cx,cy+radius,dim);

            bright.setTextSize(Math.max(11f,w*0.027f)); bright.setTextAlign(Paint.Align.LEFT);
            for(int i=1;i<=4;i++){float rr=radius*i/4f;c.drawText(Math.round(rangeMi*i/4f)+"",cx+5,cy-rr+14,bright);}
            if(alertsEnabled&&alertMi<rangeMi)c.drawCircle(cx,cy,radius*alertMi/(float)rangeMi,alert);

            bright.setTextSize(Math.max(16f,w*0.045f)); bright.setTextAlign(Paint.Align.CENTER);
            c.drawText(autoCompass?headingName(headingDeg):"N",cx,cy-radius-10,bright); c.drawText("S",cx,cy+radius+24,bright);
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
            for(int contactIndex=0;contactIndex<snapshot.size();contactIndex++) { Contact ac=snapshot.get(contactIndex);
                if(ac.distance>rangeMi) continue;
                double displayBearing=ac.bearing-(autoCompass?headingDeg:0f);
                double rr=radius*(ac.distance/rangeMi), rad=Math.toRadians(displayBearing-90.0);
                float x=cx+(float)(Math.cos(rad)*rr), y=cy+(float)(Math.sin(rad)*rr);
                drawAircraft(c,x,y,ac.track-(autoCompass?headingDeg:0f),ac.category);
                if(contactIndex==selectedIndex)c.drawCircle(x,y,13f,alert);
            }

            bright.setTextAlign(Paint.Align.LEFT); bright.setTextSize(Math.max(16f,w*0.043f));
            c.drawText(rangeMi+" MI",18,h-82,bright);
            bright.setTextAlign(Paint.Align.RIGHT); c.drawText(snapshot.size()+" CONTACT"+(snapshot.size()==1?"":"S"),w-18,h-82,bright);

            if(!snapshot.isEmpty()) {
                Contact n=snapshot.get(Math.min(selectedIndex,snapshot.size()-1)); String cs=n.callsign.isEmpty()?n.id.toUpperCase(Locale.ROOT):n.callsign;
                bright.setTextAlign(Paint.Align.CENTER); bright.setTextSize(Math.max(18f,w*0.05f));
                String altitude=n.altitudeKnown?n.altFt+" FT":"ALT --";
                c.drawText(cs+"  "+String.format(Locale.US,"%.1f MI",n.distance)+"  "+altitude,cx,h-42,bright);
            } else {
                bright.setTextAlign(Paint.Align.CENTER); bright.setTextSize(Math.max(16f,w*0.043f));
                String s=lastPacket==0?"OPEN RADAR FROM PHONE":"CLEAR"; c.drawText(s,cx,h-42,bright);
            }
        }

        private String headingName(float deg){int d=Math.round(deg)%360;if(d<0)d+=360;return d+"°";}

        private void drawAircraft(Canvas c,float x,float y,float track,int category) {
            if(category==8){c.save();c.rotate(track,x,y);c.drawCircle(x,y,3f,bright);c.drawLine(x-9,y,x+9,y,bright);c.drawLine(x,y-7,x,y+7,bright);c.restore();return;}
            if(category==9){c.save();c.rotate(track,x,y);c.drawLine(x-11,y,x+11,y,bright);c.drawLine(x,y-7,x,y+7,bright);c.restore();return;}
            if(category==13){Path d=new Path();d.moveTo(x,y-8);d.lineTo(x+8,y);d.lineTo(x,y+8);d.lineTo(x-8,y);d.close();c.drawPath(d,bright);return;}
            if(category>=15){c.drawRect(x-6,y-6,x+6,y+6,bright);return;}
            c.save(); c.rotate(track,x,y); Path a=new Path();
            float scale=(category==6||category==5||category==4)?1.35f:1f;c.scale(scale,scale,x,y);
            a.moveTo(x,y-8); a.lineTo(x+2,y-2); a.lineTo(x+8,y+1); a.lineTo(x+2,y+2); a.lineTo(x+2,y+7);
            a.lineTo(x+5,y+9); a.lineTo(x,y+8); a.lineTo(x-5,y+9); a.lineTo(x-2,y+7); a.lineTo(x-2,y+2); a.lineTo(x-8,y+1); a.lineTo(x-2,y-2); a.close();
            c.drawPath(a,bright); c.restore();
        }
    }

    private static class Contact { String callsign,id; float bearing,distance; int altFt,speedKt,track,category; boolean onGround,altitudeKnown; }
}
