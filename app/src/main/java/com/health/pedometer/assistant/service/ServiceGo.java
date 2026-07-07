package com.health.pedometer.assistant.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.health.pedometer.assistant.MainActivity;
import com.health.pedometer.assistant.R;
import com.health.pedometer.assistant.joystick.JoyStick;

import java.util.Random;

@SuppressWarnings("deprecation")
public class ServiceGo extends Service implements SensorEventListener {
    private static final String TAG = "ServiceGo";

    public static final double DEFAULT_LAT = 36.667662;
    public static final double DEFAULT_LNG = 117.027707;
    public static final double DEFAULT_ALT = 5.0D;
    public static final float DEFAULT_BEA = 0.0F;
    private double mCurLat = DEFAULT_LAT;
    private double mCurLng = DEFAULT_LNG;
    private double mCurAlt = DEFAULT_ALT;
    private float mCurBea = DEFAULT_BEA;
    private double mSpeed = 1.2;
    private static final int HANDLER_MSG_ID = 0;
    private static final String SERVICE_GO_HANDLER_NAME = "ServiceGoLocation";
    private LocationManager mLocManager;
    private HandlerThread mLocHandlerThread;
    private Handler mLocHandler;
    private boolean isStop = false;
    private static final int SERVICE_GO_NOTE_ID = 1;
    private static final String SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW = "ShowJoyStick";
    private static final String SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE = "HideJoyStick";
    private static final String SERVICE_GO_NOTE_CHANNEL_ID = "SERVICE_GO_NOTE";
    private static final String SERVICE_GO_NOTE_CHANNEL_NAME = "SERVICE_GO_NOTE";
    private NoteActionReceiver mActReceiver;
    private JoyStick mJoyStick;
    private final ServiceGoBinder mBinder = new ServiceGoBinder();
    private SensorManager mSensorManager;
    private Sensor mSensorAcc;
    private Sensor mSensorMag;
    private float[] mAccValues = new float[3];
    private float[] mMagValues = new float[3];
    private final float[] mR = new float[9];
    private final float[] mDirectionValues = new float[3];
    private float mRealBearing = 0.0f;
    private final Random mRandom = new Random();

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mLocManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        initSensors();
        removeTestProviderNetwork();
        addTestProviderNetwork();
        removeTestProviderGPS();
        addTestProviderGPS();
        removeTestProviderFused();
        addTestProviderFused();
        initGoLocation();
        initNotification();
        initJoyStick();
    }

    private void initSensors() {
        if (mSensorManager != null) {
            mSensorAcc = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            mSensorMag = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            if (mSensorAcc != null) {
                mSensorManager.registerListener(this, mSensorAcc, SensorManager.SENSOR_DELAY_UI);
            }
            if (mSensorMag != null) {
                mSensorManager.registerListener(this, mSensorMag, SensorManager.SENSOR_DELAY_UI);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            mCurLng = intent.getDoubleExtra(MainActivity.LNG_MSG_ID, DEFAULT_LNG);
            mCurLat = intent.getDoubleExtra(MainActivity.LAT_MSG_ID, DEFAULT_LAT);
            mCurAlt = intent.getDoubleExtra(MainActivity.ALT_MSG_ID, DEFAULT_ALT);
        }
        if (mJoyStick != null) {
            mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        isStop = true;
        mLocHandler.removeMessages(HANDLER_MSG_ID);
        mLocHandlerThread.quit();
        if (mSensorManager != null) {
            mSensorManager.unregisterListener(this);
        }
        mJoyStick.destroy();
        removeTestProviderNetwork();
        removeTestProviderGPS();
        removeTestProviderFused();
        unregisterReceiver(mActReceiver);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            mAccValues = event.values;
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            mMagValues = event.values;
        }
        SensorManager.getRotationMatrix(mR, null, mAccValues, mMagValues);
        SensorManager.getOrientation(mR, mDirectionValues);
        float azimuth = (float) Math.toDegrees(mDirectionValues[0]);
        if (azimuth < 0) {
            azimuth += 360;
        }
        mRealBearing = azimuth;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void initNotification() {
        mActReceiver = new NoteActionReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW);
        filter.addAction(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE);
        ContextCompat.registerReceiver(this, mActReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        NotificationChannel mChannel = new NotificationChannel(SERVICE_GO_NOTE_CHANNEL_ID, SERVICE_GO_NOTE_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.createNotificationChannel(mChannel);
        }
        Intent clickIntent = new Intent(this, MainActivity.class);
        PendingIntent clickPI = PendingIntent.getActivity(this, 1, clickIntent, PendingIntent.FLAG_IMMUTABLE);
        Intent showIntent = new Intent(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW);
        showIntent.setPackage(getPackageName());
        PendingIntent showPendingPI = PendingIntent.getBroadcast(this, 0, showIntent, PendingIntent.FLAG_IMMUTABLE);
        Intent hideIntent = new Intent(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE);
        hideIntent.setPackage(getPackageName());
        PendingIntent hidePendingPI = PendingIntent.getBroadcast(this, 0, hideIntent, PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new NotificationCompat.Builder(this, SERVICE_GO_NOTE_CHANNEL_ID)
                .setChannelId(SERVICE_GO_NOTE_CHANNEL_ID)
                .setContentTitle(getResources().getString(R.string.app_name))
                .setContentText(getResources().getString(R.string.app_service_tips))
                .setContentIntent(clickPI)
                .addAction(new NotificationCompat.Action(null, getResources().getString(R.string.note_show), showPendingPI))
                .addAction(new NotificationCompat.Action(null, getResources().getString(R.string.note_hide), hidePendingPI))
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();
        startForeground(SERVICE_GO_NOTE_ID, notification);
    }

    private void initJoyStick() {
        mJoyStick = new JoyStick(this);
        mJoyStick.setListener(new JoyStick.JoyStickClickListener() {
            @Override
            public void onMoveInfo(double speed, double disLng, double disLat, double angle) {
                mSpeed = speed;
                mCurLng += disLng / (111.320 * Math.cos(Math.abs(mCurLat) * Math.PI / 180));
                mCurLat += disLat / 110.574;
                mCurBea = (float) angle;
            }
            @Override
            public void onPositionInfo(double lng, double lat, double alt) {
                mCurLng = lng;
                mCurLat = lat;
                mCurAlt = alt;
            }
        });
        android.content.SharedPreferences sp = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
        boolean isJoyStickEnabled = sp.getBoolean("setting_joystick_state", false);
        if (isJoyStickEnabled) {
            mJoyStick.show();
        }
    }

    private void initGoLocation() {
        mLocHandlerThread = new HandlerThread(SERVICE_GO_HANDLER_NAME, Process.THREAD_PRIORITY_FOREGROUND);
        mLocHandlerThread.start();
        mLocHandler = new Handler(mLocHandlerThread.getLooper()) {
            @Override
            public void handleMessage(@NonNull Message msg) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (!isStop) {
                    doSetLocationGPS();
                    doSetLocationNetwork();
                    doSetLocationFused();
                }
                if (!isStop) {
                    sendEmptyMessage(HANDLER_MSG_ID);
                }
            }
        };
        mLocHandler.sendEmptyMessage(HANDLER_MSG_ID);
    }

    private void doSetLocationGPS() {
        try { setLocationGPS(); } catch (Throwable t) { Log.e(TAG, "GPS err", t); }
    }
    private void doSetLocationNetwork() {
        try { setLocationNetwork(); } catch (Throwable t) { Log.e(TAG, "NET err", t); }
    }
    private void doSetLocationFused() {
        try { setLocationFused(); } catch (Throwable t) { Log.e(TAG, "FUS err", t); }
    }

    private void removeTestProviderGPS() {
        try {
            if (mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false);
                mLocManager.removeTestProvider(LocationManager.GPS_PROVIDER);
            }
        } catch (Exception e) { Log.e(TAG, "rmGPS", e); }
    }

    @SuppressLint("wrongconstant")
    private void addTestProviderGPS() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false,
                        false, true, true, true, ProviderProperties.POWER_USAGE_HIGH, ProviderProperties.ACCURACY_FINE);
            } else {
                mLocManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false,
                        false, true, true, true, Criteria.POWER_HIGH, Criteria.ACCURACY_FINE);
            }
            if (!mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
            }
        } catch (Exception e) { Log.e(TAG, "addGPS", e); }
    }

    private void setLocationGPS() {
        double noiseLat = (mRandom.nextDouble() - 0.5) * 0.00004;
        double noiseLng = (mRandom.nextDouble() - 0.5) * 0.00004;
        double noiseAlt = (mRandom.nextDouble() - 0.5) * 1.0;
        double[] wgs = gcj02ToWgs84(mCurLng + noiseLng, mCurLat + noiseLat);
        Location loc = new Location(LocationManager.GPS_PROVIDER);
        loc.setAccuracy(Criteria.ACCURACY_FINE);
        loc.setAltitude(mCurAlt + noiseAlt);
        loc.setBearing(mRealBearing);
        loc.setLatitude(wgs[1]);
        loc.setLongitude(wgs[0]);
        loc.setTime(System.currentTimeMillis());
        loc.setSpeed((float) mSpeed);
        loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        Bundle bundle = new Bundle();
        bundle.putInt("satellites", 7);
        loc.setExtras(bundle);
        mLocManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc);
    }

    private void removeTestProviderNetwork() {
        try {
            if (mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, false);
                mLocManager.removeTestProvider(LocationManager.NETWORK_PROVIDER);
            }
        } catch (Exception e) { Log.e(TAG, "rmNET", e); }
    }

    @SuppressLint("wrongconstant")
    private void addTestProviderNetwork() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(LocationManager.NETWORK_PROVIDER, true, false,
                        true, true, true, true,
                        true, ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_COARSE);
            } else {
                mLocManager.addTestProvider(LocationManager.NETWORK_PROVIDER, true, false,
                        true, true, true, true,
                        true, Criteria.POWER_LOW, Criteria.ACCURACY_COARSE);
            }
            if (!mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
            }
        } catch (SecurityException e) { Log.e(TAG, "addNET", e); }
    }

    private void setLocationNetwork() {
        double noiseLat = (mRandom.nextDouble() - 0.5) * 0.00004;
        double noiseLng = (mRandom.nextDouble() - 0.5) * 0.00004;
        double noiseAlt = (mRandom.nextDouble() - 0.5) * 1.0;
        double[] wgs = gcj02ToWgs84(mCurLng + noiseLng, mCurLat + noiseLat);
        Location loc = new Location(LocationManager.NETWORK_PROVIDER);
        loc.setAccuracy(Criteria.ACCURACY_COARSE);
        loc.setAltitude(mCurAlt + noiseAlt);
        loc.setBearing(mRealBearing);
        loc.setLatitude(wgs[1]);
        loc.setLongitude(wgs[0]);
        loc.setTime(System.currentTimeMillis());
        loc.setSpeed((float) mSpeed);
        loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        mLocManager.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, loc);
    }

    private void removeTestProviderFused() {
        try {
            String providerName = "fused";
            if (mLocManager.isProviderEnabled(providerName)) {
                mLocManager.setTestProviderEnabled(providerName, false);
                mLocManager.removeTestProvider(providerName);
            }
        } catch (Exception e) { Log.e(TAG, "rmFUS", e); }
    }

    @SuppressLint("wrongconstant")
    private void addTestProviderFused() {
        try {
            String providerName = "fused";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(providerName, false, false, false,
                        false, true, true, true, ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_FINE);
            } else {
                mLocManager.addTestProvider(providerName, false, false, false,
                        false, true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
            }
            if (!mLocManager.isProviderEnabled(providerName)) {
                mLocManager.setTestProviderEnabled(providerName, true);
            }
        } catch (Exception e) { Log.e(TAG, "addFUS", e); }
    }

    private void setLocationFused() {
        double noiseLat = (mRandom.nextDouble() - 0.5) * 0.00004;
        double noiseLng = (mRandom.nextDouble() - 0.5) * 0.00004;
        double noiseAlt = (mRandom.nextDouble() - 0.5) * 1.0;
        double[] wgs = gcj02ToWgs84(mCurLng + noiseLng, mCurLat + noiseLat);
        Location loc = new Location("fused");
        loc.setAccuracy(Criteria.ACCURACY_FINE);
        loc.setAltitude(mCurAlt + noiseAlt);
        loc.setBearing(mRealBearing);
        loc.setLatitude(wgs[1]);
        loc.setLongitude(wgs[0]);
        loc.setTime(System.currentTimeMillis());
        loc.setSpeed((float) mSpeed);
        loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        Bundle bundle = new Bundle();
        bundle.putInt("satellites", 7);
        loc.setExtras(bundle);
        mLocManager.setTestProviderLocation("fused", loc);
    }

    // ========== GCJ-02 → WGS-84 坐标转换 ==========
    private static final double GCJ_A = 6378245.0;
    private static final double GCJ_EE = 0.00669342162296594323;

    private static double[] gcj02ToWgs84(double gcjLon, double gcjLat) {
        double wgsLon = gcjLon, wgsLat = gcjLat;
        for (int i = 0; i < 5; i++) {
            double[] gcj = wgs84ToGcj02(wgsLon, wgsLat);
            wgsLon = gcjLon - (gcj[0] - wgsLon);
            wgsLat = gcjLat - (gcj[1] - wgsLat);
        }
        return new double[]{wgsLon, wgsLat};
    }

    private static double[] wgs84ToGcj02(double wgsLon, double wgsLat) {
        double dLat = transformLat(wgsLon - 105.0, wgsLat - 35.0);
        double dLon = transformLon(wgsLon - 105.0, wgsLat - 35.0);
        double radLat = wgsLat / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1 - GCJ_EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((GCJ_A * (1 - GCJ_EE)) / (magic * sqrtMagic) * Math.PI);
        dLon = (dLon * 180.0) / (GCJ_A / sqrtMagic * Math.cos(radLat) * Math.PI);
        return new double[]{wgsLon + dLon, wgsLat + dLat};
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLon(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }

    public class NoteActionReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null) {
                if (action.equals(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW)) {
                    mJoyStick.show();
                }
                if (action.equals(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE)) {
                    mJoyStick.hide();
                }
            }
        }
    }

    public class ServiceGoBinder extends Binder {
        public void setPosition(double lng, double lat, double alt) {
            mLocHandler.removeMessages(HANDLER_MSG_ID);
            mCurLng = lng;
            mCurLat = lat;
            mCurAlt = alt;
            mLocHandler.sendEmptyMessage(HANDLER_MSG_ID);
            mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);
        }
    }
}