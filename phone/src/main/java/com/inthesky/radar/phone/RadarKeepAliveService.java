package com.inthesky.radar.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

public class RadarKeepAliveService extends Service {
    private static final String CHANNEL="inthesky_radar_live";
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate(){
        super.onCreate();
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26) manager.createNotificationChannel(new NotificationChannel(CHANNEL,"Live glasses radar",NotificationManager.IMPORTANCE_LOW));
        Intent open=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        Notification notification=builder.setContentTitle("In The Sky radar is live").setContentText("Sending aircraft updates to Rokid glasses").setSmallIcon(android.R.drawable.ic_menu_compass).setContentIntent(pending).setOngoing(true).build();
        startForeground(42,notification);
        PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
        wakeLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"InTheSky:RadarLink");
        wakeLock.acquire();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){return START_STICKY;}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();super.onDestroy();}
}
