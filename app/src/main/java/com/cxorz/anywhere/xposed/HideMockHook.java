package com.cxorz.anywhere.xposed;

import android.app.ActivityThread;
import android.content.ContentResolver;
import android.location.Location;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.telephony.CellInfo;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HideMockHook implements IXposedHookLoadPackage {

    private static final String TAG = "AnyWhereHook";

    // 白名单：排除自己和系统核心进程，避免误伤
    private static final List<String> WHITELIST_PACKAGES = Arrays.asList(
            "com.cxorz.anywhere",
            "android",
            "com.android.systemui",
            "com.android.phone"
    );

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName == null) return;
        
        if (WHITELIST_PACKAGES.contains(lpparam.packageName)) {
            return;
        }

        try {
            // 1. 基础防检测
            XposedHelpers.findAndHookMethod(Location.class, "isFromMockProvider", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    return false;
                }
            });

            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    XposedHelpers.findAndHookMethod(Location.class, "isMock", new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            return false;
                        }
                    });
                } catch (Throwable t) {}
            }

            XposedHelpers.findAndHookMethod(Location.class, "getExtras", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Bundle extras = (Bundle) param.getResult();
                    if (extras != null && extras.containsKey("mockLocation")) {
                        extras.remove("mockLocation");
                    }
                }
            });

            // 1.1 从源头阻止 mock 标记被设置（覆盖所有路径，包括 LocationListener 同进程回调）
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    XposedHelpers.findAndHookMethod(Location.class, "setMock", boolean.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            param.args[0] = false;
                        }
                    });
                } catch (Throwable t) {}
            }
            try {
                XposedHelpers.findAndHookMethod(Location.class, "setIsFromMockProvider", boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        param.args[0] = false;
                    }
                });
            } catch (Throwable t) {}

            // 1.2 清理 IPC 反序列化路径中的 Mock 标记字段
            // 微信小程序通过 LocationListener 回调接收 Location 时，对象由 Parcel 反序列化而来，
            // mIsFromMockProvider / mMock 字段在 readFromParcel 中被直接赋值。
            // 通过直接修改字段值，可同时覆盖 Java 方法调用和反射/JNI 直接访问两种检测路径。
            try {
                XposedHelpers.findAndHookMethod(Location.class, "readFromParcel", android.os.Parcel.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        clearMockFlagField((Location) param.thisObject);
                    }
                });
            } catch (Throwable t) { XposedBridge.log(t); }

            // 1.3 清理主动查询场景（getLastKnownLocation）中的 Mock 标记字段
            try {
                XposedHelpers.findAndHookMethod(LocationManager.class, "getLastKnownLocation", String.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Location location = (Location) param.getResult();
                        if (location != null) {
                            clearMockFlagField(location);
                        }
                    }
                });
            } catch (Throwable t) { XposedBridge.log(t); }

            // 1.4 Hook getCurrentLocation (API 30+)，拦截回调中的 Consumer 并清理 mock 标记
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    XposedBridge.hookAllMethods(LocationManager.class, "getCurrentLocation", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            for (int i = 0; i < param.args.length; i++) {
                                if (param.args[i] instanceof java.util.function.Consumer) {
                                    @SuppressWarnings("unchecked")
                                    final java.util.function.Consumer<Location> original =
                                            (java.util.function.Consumer<Location>) param.args[i];
                                    param.args[i] = (java.util.function.Consumer<Location>) location -> {
                                        if (location != null) {
                                            clearMockFlagField(location);
                                        }
                                        original.accept(location);
                                    };
                                    break;
                                }
                            }
                        }
                    });
                } catch (Throwable t) { XposedBridge.log(t); }
            }

            // 2. 屏蔽 Wi-Fi 和 基站
            try {
                XposedHelpers.findAndHookMethod(WifiManager.class, "getScanResults", new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return new ArrayList<>(); 
                    }
                });
            } catch (Throwable t) {}

            try {
                XposedHelpers.findAndHookMethod(TelephonyManager.class, "getCellLocation", new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return null;
                    }
                });
                XposedHelpers.findAndHookMethod(TelephonyManager.class, "getAllCellInfo", new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return new ArrayList<CellInfo>();
                    }
                });
            } catch (Throwable t) {}

            // 3. 卫星状态主动伪造 (GnssStatus Only)
            final Handler mainHandler = new Handler(Looper.getMainLooper());
            final List<Object> gnssCallbacks = new ArrayList<>();

            // =========================================================================
            // GnssStatus Hook (Android N+)
            // =========================================================================
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                
                final Class<?> gnssStatusClass = Class.forName("android.location.GnssStatus");
                
                // 1. Hook GnssStatus 的所有 Getter 方法
                XposedHelpers.findAndHookMethod(gnssStatusClass, "getSatelliteCount", new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return 7; 
                    }
                });

                XposedHelpers.findAndHookMethod(gnssStatusClass, "getSvid", int.class, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return ((int) param.args[0]) + 1;
                    }
                });

                XposedHelpers.findAndHookMethod(gnssStatusClass, "getConstellationType", int.class, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return 1; // GPS
                    }
                });

                XposedHelpers.findAndHookMethod(gnssStatusClass, "getCn0DbHz", int.class, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return 35.0f;
                    }
                });

                XposedHelpers.findAndHookMethod(gnssStatusClass, "getElevationDegrees", int.class, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return 45.0f;
                    }
                });

                XposedHelpers.findAndHookMethod(gnssStatusClass, "getAzimuthDegrees", int.class, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return (float)((int)param.args[0] * 45);
                    }
                });

                XposedHelpers.findAndHookMethod(gnssStatusClass, "usedInFix", int.class, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return true;
                    }
                });
                
                try { XposedHelpers.findAndHookMethod(gnssStatusClass, "hasEphemeris", int.class, new XC_MethodReplacement() { @Override protected Object replaceHookedMethod(MethodHookParam param) { return true; } }); } catch (Throwable t) {}
                try { XposedHelpers.findAndHookMethod(gnssStatusClass, "hasAlmanac", int.class, new XC_MethodReplacement() { @Override protected Object replaceHookedMethod(MethodHookParam param) { return true; } }); } catch (Throwable t) {}
                try { XposedHelpers.findAndHookMethod(gnssStatusClass, "hasCarrierFrequencyHz", int.class, new XC_MethodReplacement() { @Override protected Object replaceHookedMethod(MethodHookParam param) { return true; } }); } catch (Throwable t) {}
                try { XposedHelpers.findAndHookMethod(gnssStatusClass, "getCarrierFrequencyHz", int.class, new XC_MethodReplacement() { @Override protected Object replaceHookedMethod(MethodHookParam param) { return 1.57542e9f; } }); } catch (Throwable t) {}


                // 2. 拦截注册
                Class<?> gnssCallbackClass = Class.forName("android.location.GnssStatus$Callback");
                XC_MethodReplacement registerGnssHook = new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        Object callback = param.args[0];
                        if (callback != null) {
                            synchronized (gnssCallbacks) {
                                if (!gnssCallbacks.contains(callback)) gnssCallbacks.add(callback);
                            }
                        }
                        return true;
                    }
                };
                XposedHelpers.findAndHookMethod(LocationManager.class, "registerGnssStatusCallback", gnssCallbackClass, registerGnssHook);
                XposedHelpers.findAndHookMethod(LocationManager.class, "registerGnssStatusCallback", gnssCallbackClass, android.os.Handler.class, registerGnssHook);
                
                // 2.1 拦截注销 (防止内存泄漏)
                XposedHelpers.findAndHookMethod(LocationManager.class, "unregisterGnssStatusCallback", gnssCallbackClass, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        Object callback = param.args[0];
                        if (callback != null) {
                            synchronized (gnssCallbacks) {
                                gnssCallbacks.remove(callback);
                            }
                        }
                        return null;
                    }
                });

                // 3. 模拟循环
                Runnable simulator = new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (!gnssCallbacks.isEmpty()) {
                                Object statusToSend = getBestDummyGnssStatus(); 
                                if (statusToSend != null) {
                                    synchronized (gnssCallbacks) {
                                        for (Object callback : gnssCallbacks) {
                                            XposedHelpers.callMethod(callback, "onSatelliteStatusChanged", statusToSend);
                                        }
                                    }
                                } else {
                                    Log.e(TAG, "CRITICAL: getBestDummyGnssStatus returned NULL.");
                                }
                            }
                        } catch (Throwable t) { Log.e(TAG, "Sim loop error: " + t); }
                        mainHandler.postDelayed(this, 1000);
                    }
                };
                mainHandler.post(simulator);
            }
            
            // 4. 清理 Settings 和 Provider 列表
            XposedHelpers.findAndHookMethod(Settings.Secure.class, "getString", ContentResolver.class, String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if ("mock_location".equals(param.args[1])) param.setResult("0");
                }
            });

            final List<String> standardProviders = Arrays.asList("gps", "network", "passive", "fused");
            XC_MethodHook providerCleaner = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    List<String> providers = (List<String>) param.getResult();
                    if (providers != null) {
                        for (int i = providers.size() - 1; i >= 0; i--) {
                            if (!standardProviders.contains(providers.get(i))) providers.remove(i);
                        }
                    }
                }
            };
            XposedHelpers.findAndHookMethod(LocationManager.class, "getProviders", boolean.class, providerCleaner);
            XposedHelpers.findAndHookMethod(LocationManager.class, "getAllProviders", providerCleaner);

            // 4.1 对非标准 provider 返回 false
            XposedHelpers.findAndHookMethod(LocationManager.class, "isProviderEnabled", String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.hasThrowable()) return;
                    String provider = (String) param.args[0];
                    if (provider != null && !standardProviders.contains(provider)) {
                        param.setResult(false);
                    }
                }
            });

            // ================================================================
            // 5. 腾讯地图 SDK Hook（微信/QQ 等腾讯系 APP 使用）
            // 这些 APP 绕过系统 LocationManager，直接使用腾讯定位 SDK
            // ================================================================
            hookTencentLocationSDK(lpparam);

            // ================================================================
            // 6. 百度地图 SDK Hook（部分 APP 使用百度定位）
            // ================================================================
            hookBaiduLocationSDK(lpparam);

            // ================================================================
            // 7. 高德地图 SDK Hook（部分 APP 使用高德定位）
            // ================================================================
            hookAmapLocationSDK(lpparam);

        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    /**
     * 腾讯地图 SDK Hook — 微信/QQ 等腾讯系 APP 内部使用 TencentLocationManager
     * 绕过系统 LocationManager，直接读取 GPS 或使用腾讯自有网络定位。
     */
    private static void hookTencentLocationSDK(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // 5.1 拦截 TencentLocationListener 回调，将坐标替换为系统 mock 值
            Class<?> tencentLocListener = XposedHelpers.findClassIfExists(
                    "com.tencent.map.geolocation.TencentLocationListener", lpparam.classLoader);
            Class<?> tencentLoc = XposedHelpers.findClassIfExists(
                    "com.tencent.map.geolocation.TencentLocation", lpparam.classLoader);
            if (tencentLocListener == null || tencentLoc == null) return;

            // 5.2 Hook requestLocationUpdates 的所有重载
            Class<?> tencentLocManager = XposedHelpers.findClassIfExists(
                    "com.tencent.map.geolocation.TencentLocationManager", lpparam.classLoader);
            if (tencentLocManager != null) {
                // Hook 所有 requestLocationUpdates 方法
                XposedBridge.hookAllMethods(tencentLocManager, "requestLocationUpdates", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        // 查找 TencentLocationListener 参数并替换为代理
                        for (int i = 0; i < param.args.length; i++) {
                            if (param.args[i] != null && tencentLocListener.isInstance(param.args[i])) {
                                final Object originalListener = param.args[i];
                                param.args[i] = java.lang.reflect.Proxy.newProxyInstance(
                                        lpparam.classLoader,
                                        new Class[]{tencentLocListener},
                                        (proxy, method, methodArgs) -> {
                                            if ("onLocationChanged".equals(method.getName()) && methodArgs.length > 0) {
                                                // 用系统 mock Location 替换腾讯定位结果
                                                methodArgs[0] = patchTencentLocation(methodArgs[0], tencentLoc);
                                            }
                                            return method.invoke(originalListener, methodArgs);
                                        });
                                break;
                            }
                        }
                    }
                });

                // 5.3 Hook requestSingleUpdate
                XposedBridge.hookAllMethods(tencentLocManager, "requestSingleUpdate", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        for (int i = 0; i < param.args.length; i++) {
                            if (param.args[i] != null && tencentLocListener.isInstance(param.args[i])) {
                                final Object originalListener = param.args[i];
                                param.args[i] = java.lang.reflect.Proxy.newProxyInstance(
                                        lpparam.classLoader,
                                        new Class[]{tencentLocListener},
                                        (proxy, method, methodArgs) -> {
                                            if ("onLocationChanged".equals(method.getName()) && methodArgs.length > 0) {
                                                methodArgs[0] = patchTencentLocation(methodArgs[0], tencentLoc);
                                            }
                                            return method.invoke(originalListener, methodArgs);
                                        });
                                break;
                            }
                        }
                    }
                });

                XposedBridge.log("AnyWhereHook: TencentLocationManager hooked for " + lpparam.packageName);
            }
        } catch (Throwable t) {
            XposedBridge.log("AnyWhereHook: Tencent SDK hook failed - " + t.getMessage());
        }
    }

    /**
     * 百度地图 SDK Hook
     */
    private static void hookBaiduLocationSDK(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> bdLocation = XposedHelpers.findClassIfExists(
                    "com.baidu.location.BDLocation", lpparam.classLoader);
            Class<?> bdLocationListener = XposedHelpers.findClassIfExists(
                    "com.baidu.location.BDLocationListener", lpparam.classLoader);
            Class<?> locationClient = XposedHelpers.findClassIfExists(
                    "com.baidu.location.LocationClient", lpparam.classLoader);
            
            if (bdLocation == null || bdLocationListener == null || locationClient == null) return;

            // Hook BDLocation.getLatitude/getLongitude
            XposedHelpers.findAndHookMethod(bdLocation, "getLatitude", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    // 返回系统 mock 位置的纬度
                    double[] mock = getMockLocation();
                    if (mock != null) param.setResult(mock[0]);
                }
            });
            XposedHelpers.findAndHookMethod(bdLocation, "getLongitude", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    double[] mock = getMockLocation();
                    if (mock != null) param.setResult(mock[1]);
                }
            });

            XposedBridge.log("AnyWhereHook: BaiduLocation SDK hooked for " + lpparam.packageName);
        } catch (Throwable t) {
            XposedBridge.log("AnyWhereHook: Baidu SDK hook failed - " + t.getMessage());
        }
    }

    /**
     * 高德地图 SDK Hook
     */
    private static void hookAmapLocationSDK(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> amapLocation = XposedHelpers.findClassIfExists(
                    "com.amap.api.location.AMapLocation", lpparam.classLoader);
            if (amapLocation == null) return;

            XposedHelpers.findAndHookMethod(amapLocation, "getLatitude", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    double[] mock = getMockLocation();
                    if (mock != null) param.setResult(mock[0]);
                }
            });
            XposedHelpers.findAndHookMethod(amapLocation, "getLongitude", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    double[] mock = getMockLocation();
                    if (mock != null) param.setResult(mock[1]);
                }
            });

            XposedBridge.log("AnyWhereHook: AMapLocation SDK hooked for " + lpparam.packageName);
        } catch (Throwable t) {
            XposedBridge.log("AnyWhereHook: AMap SDK hook failed - " + t.getMessage());
        }
    }

    /**
     * 修改腾讯定位结果对象，将坐标替换为系统 mock 值
     */
    private static Object patchTencentLocation(Object tencentLocation, Class<?> tencentLocClass) {
        if (tencentLocation == null) return null;
        try {
            double[] mock = getMockLocation();
            if (mock == null) return tencentLocation;

            // 创建代理对象，拦截 getLatitude/getLongitude
            return java.lang.reflect.Proxy.newProxyInstance(
                    tencentLocClass.getClassLoader(),
                    new Class[]{tencentLocClass},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("getLatitude".equals(name)) return mock[0];
                        if ("getLongitude".equals(name)) return mock[1];
                        return method.invoke(tencentLocation, args);
                    });
        } catch (Throwable t) {
            return tencentLocation;
        }
    }

    /**
     * 从系统 LocationManager 获取当前 mock 位置坐标
     */
    private static volatile double[] sCachedMockLocation;
    private static volatile long sMockLocationCacheTime;

    private static double[] getMockLocation() {
        // 缓存 2 秒，避免频繁反射调用
        long now = System.currentTimeMillis();
        if (sCachedMockLocation != null && (now - sMockLocationCacheTime) < 2000) {
            return sCachedMockLocation;
        }
        try {
            android.location.LocationManager lm = (android.location.LocationManager)
                    ActivityThread.currentApplication().getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
            android.location.Location gps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (gps != null) {
                sCachedMockLocation = new double[]{gps.getLatitude(), gps.getLongitude()};
                sMockLocationCacheTime = now;
                return sCachedMockLocation;
            }
        } catch (Throwable t) {}
        return null;
    }

    /**
     * 直接通过反射清除 Location 对象中的 Mock 标记字段。
     * Android API 31+ 使用字段名 "mMock"，低版本使用 "mIsFromMockProvider"。
     * 此方法可确保即使调用方通过反射或 JNI 读取字段值，也无法检测到模拟位置。
     */
    private static volatile Field sMockField;
    private static volatile boolean sMockFieldResolved;

    private static void clearMockFlagField(Location location) {
        if (!sMockFieldResolved) {
            synchronized (HideMockHook.class) {
                if (!sMockFieldResolved) {
                    // API 31+ 使用 "mMock"
                    try {
                        sMockField = Location.class.getDeclaredField("mMock");
                        sMockField.setAccessible(true);
                    } catch (NoSuchFieldException ignored) {
                        // API < 31 使用 "mIsFromMockProvider"
                        try {
                            sMockField = Location.class.getDeclaredField("mIsFromMockProvider");
                            sMockField.setAccessible(true);
                        } catch (NoSuchFieldException ignored2) {
                        }
                    }
                    sMockFieldResolved = true;
                }
            }
        }
        if (sMockField != null) {
            try {
                sMockField.set(location, false);
            } catch (Throwable t) {}
        }
    }

    private Object getBestDummyGnssStatus() {
        try {
            Class<?> builderClass = Class.forName("android.location.GnssStatus$Builder");
            
            // 1. Try Builder No-Args
            try {
                Object builder = XposedHelpers.newInstance(builderClass); 
                return XposedHelpers.callMethod(builder, "build");
            } catch (Throwable t) {}
            
            // 2. Try Builder with int
            try {
                Object builder = XposedHelpers.newInstance(builderClass, 0); 
                return XposedHelpers.callMethod(builder, "build");
            } catch (Throwable t) {}

            // 3. Try Legacy GnssStatus Constructors
            Class<?> gnssStatusClass = Class.forName("android.location.GnssStatus");
            Constructor<?>[] constructors = gnssStatusClass.getDeclaredConstructors();
            for (Constructor<?> c : constructors) {
                try {
                    c.setAccessible(true);
                    Class<?>[] params = c.getParameterTypes();
                    Object[] args = new Object[params.length];
                    for (int i = 0; i < params.length; i++) {
                        Class<?> type = params[i];
                        if (type == int.class) args[i] = 0;
                        else if (type == float[].class) args[i] = new float[0];
                        else if (type == int[].class) args[i] = new int[0];
                        else args[i] = null;
                    }
                    return c.newInstance(args);
                } catch (Throwable t) {}
            }
        } catch (Throwable t) {
            Log.e(TAG, "getBestDummyGnssStatus fatal: " + t);
        }
        return null;
    }
}