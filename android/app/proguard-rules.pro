# Project ProGuard rules.

# Keep Gson model classes (fields + default constructors for deserialization)
-keep class dev.medveed.safeshare.net.** { <init>(); <fields>; }
-keep class dev.medveed.safeshare.db.** { <init>(); <fields>; }

# Keep Retrofit service interface methods
-keep,allowobfuscation interface dev.medveed.safeshare.net.ApiService { *; }

# Keep Gson & Retrofit annotations
-keepattributes Signature, RuntimeVisibleAnnotations, AnnotationDefault

# Gson: keep generic type info for TypeToken
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# OkHttp/Retrofit: keep platform classes
-dontwarn okhttp3.internal.platform.**
-dontwarn retrofit2.Platform$Java8
