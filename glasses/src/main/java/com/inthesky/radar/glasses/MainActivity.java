package com.inthesky.radar.glasses;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import com.rokid.cxr.CXRServiceBridge;
import com.rokid.cxr.Caps;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity implements SensorEventListener {
    private static final String RADAR_CHANNEL = "inthesky_radar_state";
    private RadarView radar;
    private volatile boolean running = true;
    private CXRServiceBridge cxrBridge;
    private volatile boolean cxrConnected = false;
    private int cxrAttempt = 0;
    private SensorManager sensors;
    private Sensor rotationSensor;
    private ToneGenerator tone;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 65);
        radar = new RadarView();
        setContentView(radar);
        sensors = (SensorManager)getSystemService(SENSOR_SERVICE);
        rotationSensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        // Hi Rokid reports the session before its glasses endpoint is always
        // ready. Attaching a moment later avoids the first-launch race.
        radar.postDelayed(this::startCxrReceiver, 1500L);
    }

    @Override protected void onResume() {
        super.onResume();
        if (rotationSensor != null) sensors.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI);
    }

    @Override protected void onPause() {
        if (sensors != null) sensors.unregisterListener(this);
        super.onPause();
    }

    @Override public void onSensorChanged(SensorEvent event) {
        float[] matrix=new float[9], orientation=new float[3];
        SensorManager.getRotationMatrixFromVector(matrix,event.values);
        SensorManager.getOrientation(matrix,orientation);
        float heading=(float)Math.toDegrees(orientation[0]);
        if(heading<0) heading+=360f;
        radar.setHeading(heading);
    }
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy) {}

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if(keyCode==KeyEvent.KEYCODE_DPAD_RIGHT || keyCode==KeyEvent.KEYCODE_VOLUME_UP || keyCode==KeyEvent.KEYCODE_PAGE_DOWN) { radar.selectNext(1); return true; }
        if(keyCode==KeyEvent.KEYCODE_DPAD_LEFT || keyCode==KeyEvent.KEYCODE_VOLUME_DOWN || keyCode==KeyEvent.KEYCODE_PAGE_UP) { radar.selectNext(-1); return true; }
        return super.onKeyDown(keyCode,event);
    }

    private void startCxrReceiver() {
        final int attempt = ++cxrAttempt;
        try {
            cxrBridge = new CXRServiceBridge();
            cxrBridge.setStatusListener(new CXRServiceBridge.StatusListener() {
                @Override public void onConnecting(String d,String m,int t){radar.setLinkState("CXR • CONNECTING");}
                @Override public void onConnected(String d,String m,int t){cxrConnected=true;cxrAttempt=0;radar.setLinkState("CXR • PHONE CONNECTED");}
                @Override public void onDisconnected(){cxrConnected=false;radar.setLinkState("CXR • WAITING FOR PHONE");scheduleCxrRetry();}
                @Override public void onARTCStatus(float q,boolean h){}
                @Override public void onRokidAccountChanged(String a){}
                @Override public void onAudioNoise(float n){}
            });
            int result=cxrBridge.subscribe(RADAR_CHANNEL,new CXRServiceBridge.MsgCallback(){
                @Override public void onReceive(String channel,Caps args,byte[] data){
                    try{
                        String json=null;
                        for(int i=0;i<args.size();i++){Caps.Value v=args.at(i);if(v!=null&&v.type()==Caps.Value.TYPE_STRING){json=v.getString();break;}}
                        if(json!=null){cxrConnected=true;radar.applyPacket(new JSONObject(json));}
                    }catch(Exception ignored){}
                }
            });
            radar.setLinkState(result==0?"CXR • WAITING FOR PHONE":"CXR SUBSCRIBE • "+result);
            radar.postDelayed(() -> {
                if(running && !cxrConnected && cxrAttempt==attempt) scheduleCxrRetry();
            }, 4000L);
        }catch(Throwable t){radar.setLinkState("CXR SERVICE UNAVAILABLE");}
    }

    private void scheduleCxrRetry(){
        if(!running || cxrConnected || cxrAttempt>=5)return;
        final int expected=cxrAttempt;
        radar.setLinkState("CXR • RETRYING PHONE  "+(expected+1)+"/5");
        radar.postDelayed(()->{if(running&&!cxrConnected&&cxrAttempt==expected)startCxrReceiver();},1200L);
    }

    private void ping(){try{tone.startTone(ToneGenerator.TONE_PROP_BEEP,180);}catch(Exception ignored){}}
    @Override protected void onDestroy(){running=false;if(tone!=null)tone.release();super.onDestroy();}

    private class RadarView extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG),dim=new Paint(Paint.ANTI_ALIAS_FLAG),bright=new Paint(Paint.ANTI_ALIAS_FLAG),fill=new Paint(Paint.ANTI_ALIAS_FLAG),selected=new Paint(Paint.ANTI_ALIAS_FLAG),map=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<Contact> contacts=new ArrayList<>();
        private final Set<String> knownIds=new HashSet<>();
        private float sweepDeg=0f,headingDeg=0f,touchX;
        private int rangeMi=25,selectedIndex=0;
        private String selectedId="",linkState="STARTING";
        private long lastPacket=0;

        RadarView(){
            super(MainActivity.this);setBackgroundColor(Color.BLACK);setFocusable(true);
            p.setColor(Color.rgb(79,255,159));p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2f);
            dim.setColor(Color.argb(135,79,255,159));dim.setStyle(Paint.Style.STROKE);dim.setStrokeWidth(1.2f);
            bright.setColor(Color.rgb(150,255,190));bright.setStyle(Paint.Style.FILL);bright.setTextAlign(Paint.Align.CENTER);
            fill.setColor(Color.argb(45,79,255,159));fill.setStyle(Paint.Style.FILL);
            selected.setColor(Color.YELLOW);selected.setStyle(Paint.Style.STROKE);selected.setStrokeWidth(2.5f);
            map.setColor(Color.argb(18,90,180,130));map.setStyle(Paint.Style.STROKE);map.setStrokeWidth(1f);
            post(animator);
        }
        private final Runnable animator=new Runnable(){public void run(){sweepDeg=(sweepDeg+2.2f)%360f;invalidate();if(running)postDelayed(this,33);}};
        void setLinkState(String s){post(()->{linkState=s;invalidate();});}
        void setHeading(float h){post(()->{float delta=((h-headingDeg+540f)%360f)-180f;headingDeg=(headingDeg+delta*.18f+360f)%360f;invalidate();});}

        void applyPacket(JSONObject o){
            if(!"radar_state".equals(o.optString("type")))return;
            final int r=Math.max(1,Math.min(200,o.optInt("rangeMi",25)));
            final List<Contact> next=new ArrayList<>();final Set<String> ids=new HashSet<>();
            JSONArray a=o.optJSONArray("aircraft");
            if(a!=null)for(int i=0;i<a.length();i++){
                JSONObject x=a.optJSONObject(i);if(x==null)continue;Contact ct=new Contact();
                ct.callsign=x.optString("callsign","");ct.id=x.optString("id","");ct.bearing=(float)x.optDouble("bearing",0);ct.distance=(float)x.optDouble("distanceMi",0);
                ct.altitudeKnown=x.optBoolean("altitudeKnown",x.has("altitudeFt"));ct.altFt=x.optInt("altitudeFt",0);ct.speedKt=x.optInt("speedKt",0);ct.track=x.optInt("track",0);ct.onGround=x.optBoolean("onGround",false);ct.category=x.optInt("category",0);
                next.add(ct);if(!ct.id.isEmpty())ids.add(ct.id);
            }
            next.sort(Comparator.comparingDouble(ct->ct.distance));
            post(()->{
                boolean newArrival=!knownIds.isEmpty()&&ids.stream().anyMatch(id->!knownIds.contains(id));
                synchronized(contacts){contacts.clear();contacts.addAll(next);}
                knownIds.clear();knownIds.addAll(ids);rangeMi=r;lastPacket=System.currentTimeMillis();linkState="LIVE";
                restoreSelection();if(newArrival)ping();invalidate();
            });
        }

        private void restoreSelection(){
            if(contacts.isEmpty()){selectedIndex=0;selectedId="";return;}
            int found=-1;for(int i=0;i<contacts.size();i++)if(contacts.get(i).id.equals(selectedId)){found=i;break;}
            selectedIndex=found>=0?found:Math.min(selectedIndex,contacts.size()-1);selectedId=contacts.get(selectedIndex).id;
        }
        void selectNext(int direction){post(()->{synchronized(contacts){if(contacts.isEmpty())return;selectedIndex=(selectedIndex+direction+contacts.size())%contacts.size();selectedId=contacts.get(selectedIndex).id;}invalidate();});}
        @Override public boolean onTouchEvent(MotionEvent e){if(e.getAction()==MotionEvent.ACTION_DOWN){touchX=e.getX();return true;}if(e.getAction()==MotionEvent.ACTION_UP){float dx=e.getX()-touchX;if(Math.abs(dx)>35)selectNext(dx>0?1:-1);else selectNext(e.getX()>getWidth()/2f?1:-1);return true;}return true;}

        @Override protected void onDraw(Canvas c){
            super.onDraw(c);float w=getWidth(),h=getHeight(),cx=w/2f;float radius=Math.min(w*.43f,h*.34f),cy=Math.min(h*.46f,radius+82f);
            bright.setTextSize(Math.max(15f,w*.038f));bright.setTextAlign(Paint.Align.LEFT);c.drawText("IN THE SKY",18,34,bright);
            bright.setTextAlign(Paint.Align.RIGHT);c.drawText(linkState+"  "+Math.round(headingDeg)+"°",w-18,34,bright);
            if(lastPacket>0){long age=Math.max(0,(System.currentTimeMillis()-lastPacket)/1000L);String updated=age<3?"UPDATED JUST NOW":age<60?"UPDATED "+age+" SEC AGO":"UPDATED "+(age/60)+" MIN AGO";if(age>45)updated="STALE • "+updated;bright.setTextAlign(Paint.Align.CENTER);bright.setTextSize(Math.max(12f,w*.029f));c.drawText(updated,cx,57,bright);}
            drawMapLayer(c,cx,cy,radius);
            for(int i=1;i<=4;i++)c.drawCircle(cx,cy,radius*i/4f,dim);c.drawLine(cx-radius,cy,cx+radius,cy,dim);c.drawLine(cx,cy-radius,cx,cy+radius,dim);
            drawCardinals(c,cx,cy,radius);
            float displaySweep=(sweepDeg-headingDeg+360f)%360f,a1=(float)Math.toRadians(displaySweep-102f),a2=(float)Math.toRadians(displaySweep-90f);
            Path wedge=new Path();wedge.moveTo(cx,cy);wedge.lineTo(cx+(float)Math.cos(a1)*radius,cy+(float)Math.sin(a1)*radius);wedge.arcTo(cx-radius,cy-radius,cx+radius,cy+radius,displaySweep-102f,12f,false);wedge.close();c.drawPath(wedge,fill);c.drawLine(cx,cy,cx+(float)Math.cos(a2)*radius,cy+(float)Math.sin(a2)*radius,p);c.drawCircle(cx,cy,4f,bright);
            List<Contact> snapshot;synchronized(contacts){snapshot=new ArrayList<>(contacts);}
            for(int i=0;i<snapshot.size();i++){Contact ac=snapshot.get(i);if(ac.distance>rangeMi)continue;double rr=radius*(ac.distance/rangeMi),rad=Math.toRadians(ac.bearing-headingDeg-90);float x=cx+(float)(Math.cos(rad)*rr),y=cy+(float)(Math.sin(rad)*rr);drawAircraft(c,x,y,ac.track-headingDeg,ac.category,ac.onGround);if(i==selectedIndex)c.drawCircle(x,y,13f,selected);}
            bright.setTextAlign(Paint.Align.LEFT);bright.setTextSize(Math.max(16f,w*.043f));c.drawText(rangeMi+" MI",18,h-82,bright);bright.setTextAlign(Paint.Align.RIGHT);c.drawText(snapshot.size()+" CONTACT"+(snapshot.size()==1?"":"S"),w-18,h-82,bright);
            if(!snapshot.isEmpty()){Contact n=snapshot.get(Math.min(selectedIndex,snapshot.size()-1));String cs=n.callsign.isEmpty()?n.id.toUpperCase(Locale.ROOT):n.callsign;String alt=n.altitudeKnown?n.altFt+" FT":"ALT UNKNOWN";bright.setTextAlign(Paint.Align.CENTER);bright.setTextSize(Math.max(18f,w*.047f));c.drawText("‹  "+cs+"  "+String.format(Locale.US,"%.1f MI",n.distance)+"  "+alt+"  ›",cx,h-42,bright);}else{bright.setTextAlign(Paint.Align.CENTER);bright.setTextSize(Math.max(16f,w*.043f));c.drawText(lastPacket==0?"AWAITING RADAR DATA":"CLEAR",cx,h-42,bright);}
        }

        private void drawMapLayer(Canvas c,float cx,float cy,float r){
            c.save();c.clipPath(circlePath(cx,cy,r));
            for(int i=-3;i<=3;i++){float y=cy+i*r/3f;c.drawLine(cx-r,y,cx+r,y+(i%2==0?12:-12),map);float x=cx+i*r/3f;c.drawLine(x,cy-r,x+(i%2==0?-10:10),cy+r,map);}
            Path road=new Path();road.moveTo(cx-r,cy+r*.35f);road.cubicTo(cx-r*.35f,cy-r*.5f,cx+r*.15f,cy+r*.55f,cx+r,cy-r*.25f);c.drawPath(road,map);c.restore();
        }
        private Path circlePath(float x,float y,float r){Path q=new Path();q.addCircle(x,y,r,Path.Direction.CW);return q;}
        private void drawCardinals(Canvas c,float cx,float cy,float r){String[] names={"N","E","S","W"};float[] bearings={0,90,180,270};bright.setTextSize(Math.max(15f,getWidth()*.04f));bright.setTextAlign(Paint.Align.CENTER);for(int i=0;i<4;i++){double a=Math.toRadians(bearings[i]-headingDeg-90);c.drawText(names[i],cx+(float)Math.cos(a)*(r+18),cy+(float)Math.sin(a)*(r+18)+6,bright);}}
        private void drawAircraft(Canvas c,float x,float y,float track,int category,boolean ground){
            c.save();c.rotate(track,x,y);Path a=new Path();
            if(category==7){a.addCircle(x,y,6,Path.Direction.CW);a.moveTo(x-10,y);a.lineTo(x+10,y);a.moveTo(x,y-10);a.lineTo(x,y+10);c.drawPath(a,ground?dim:bright);}
            else if(category==5||category==6){a.moveTo(x,y-8);a.lineTo(x+8,y+7);a.lineTo(x,y+3);a.lineTo(x-8,y+7);a.close();c.drawPath(a,ground?dim:bright);}
            else{float wing=category==3?11:8;a.moveTo(x,y-9);a.lineTo(x+2,y-2);a.lineTo(x+wing,y+1);a.lineTo(x+2,y+3);a.lineTo(x+2,y+7);a.lineTo(x+5,y+9);a.lineTo(x,y+8);a.lineTo(x-5,y+9);a.lineTo(x-2,y+7);a.lineTo(x-2,y+3);a.lineTo(x-wing,y+1);a.lineTo(x-2,y-2);a.close();c.drawPath(a,ground?dim:bright);}c.restore();
        }
    }
    private static class Contact{String callsign,id;float bearing,distance;int altFt,speedKt,track,category;boolean onGround,altitudeKnown;}
}
