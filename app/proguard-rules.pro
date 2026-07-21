# WireGuard tunnel library talks to native code via JNI and reflection.
-keep class com.wireguard.android.backend.** { *; }
-keep class com.wireguard.crypto.** { *; }
-keep class com.wireguard.config.** { *; }

# kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.batman110391.warpfiretv.** {
    *** Companion;
}
-keepclasseswithmembers class com.batman110391.warpfiretv.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp / Okio optional platform bits.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Tink (androidx.security) optional protobuf-lite bits.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
