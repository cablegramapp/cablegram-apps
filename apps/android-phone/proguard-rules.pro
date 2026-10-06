# R8 rules for release builds. The libraries below call into native code or are read by reflection,
# so what the native side looks up by name must keep its name.

# LibVLC: libvlcjni looks up these classes and methods by name.
-keep class org.videolan.libvlc.** { *; }
-dontwarn org.videolan.**

# TDLib (tdl-coroutines): JNI. The wrapper ships its own consumer rules; this keeps the JNI classes whole.
-keep class org.drinkless.tdlib.** { *; }
-keep class dev.g000sha256.tdl.** { *; }

# kotlinx.serialization: the generated serializers of our @Serializable models.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class app.cablegram.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class app.cablegram.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontnote kotlinx.serialization.**

# NanoHTTPD: the localhost Range server for Telegram files.
-keep class fi.iki.elonen.** { *; }

# OkHttp ships consumer rules; its optional TLS providers are not on Android.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-keep class app.cablegram.phone.cast.SpikeSenderOptions { *; }
