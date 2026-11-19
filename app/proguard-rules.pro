# JARVIS AI / OpenWA client keep rules.
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, KotlinMetadata

# kotlinx.serialization
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class ai.jarvis.**$$serializer { *; }
-keepclassmembers class ai.jarvis.** { *** Companion; }
-keepclasseswithmembers class ai.jarvis.** { kotlinx.serialization.KSerializer serializer(...); }

# Retrofit / OkHttp
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keep class ai.jarvis.data.api.** { *; }
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**

# Socket.IO / engine.io
-keep class io.socket.** { *; }
-keep class org.json.** { *; }
-dontwarn io.socket.**

# Hilt / Dagger
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Coil / Media3
-dontwarn coil.**
-dontwarn androidx.media3.**
