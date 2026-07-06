# ========== osmdroid ==========
-dontwarn org.osmdroid.**
-keep class org.osmdroid.** { *; }
-dontwarn org.apache.james.mime4j.**
-keep class org.apache.james.mime4j.** { *; }

# ========== OkHttp（R8 可能移除未直接引用的方法）==========
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# ========== 保留所有 App 核心类，防止 R8 全网混淆导致 Service 被破坏 ==========
-keep class com.health.pedometer.assistant.MainActivity { *; }
-keep class com.health.pedometer.assistant.service.** { *; }
-keep class com.health.pedometer.assistant.xposed.** { *; }
-keep class com.health.pedometer.assistant.joystick.** { *; }
-keep class com.health.pedometer.assistant.utils.** { *; }

# ========== 保留匿名内部类（OnlineTileSourceBase 子类）==========
-keep class com.health.pedometer.assistant.MainActivity$* { *; }
-keep class com.health.pedometer.assistant.joystick.JoyStick$* { *; }

# ========== 保留 LocationManager 反射调用 ==========
-keepclassmembers class android.location.LocationManager {
    public void addTestProvider(...);
    public void removeTestProvider(...);
    public void setTestProviderEnabled(...);
    public void setTestProviderLocation(...);
    public void setTestProviderStatus(...);
}
