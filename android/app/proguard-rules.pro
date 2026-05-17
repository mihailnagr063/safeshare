# Project ProGuard rules.

# Keep all model classes
-keep class dev.medveed.safeshare.net.** { <fields>; }
-keep class dev.medveed.safeshare.db.** { <fields>; }

# Keep Retrofit/Gson annotations
-keepattributes Signature, RuntimeVisibleAnnotations
