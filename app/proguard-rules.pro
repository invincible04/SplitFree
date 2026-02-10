# SplitFree ProGuard Rules

# Keep secp256k1 native library (ACINQ secp256k1-kmp)
-keep class fr.acinq.secp256k1.** { *; }

# Keep BouncyCastle crypto (ChaCha20 engine)
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Keep all native method implementations
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep kotlinx.serialization
-keepattributes *Annotation*
-keep class kotlinx.serialization.** { *; }
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
    *** serializer(...);
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class com.splitfree.domain.model.** { *; }
-keepclassmembers class com.splitfree.domain.usecase.BalanceSnapshot { *; }
-keepclassmembers class com.splitfree.domain.usecase.SnapshotBalance { *; }
-keepclassmembers class com.splitfree.data.ble.BleHandshake { *; }
-keepclassmembers class com.splitfree.data.ble.BleSyncRequest { *; }

# Room entities
-keep class com.splitfree.data.local.entities.** { *; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# LZ4 compression
-keep class net.jpountz.lz4.** { *; }
-dontwarn net.jpountz.**

# ZXing QR
-keep class com.google.zxing.** { *; }
