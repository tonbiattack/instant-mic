# Keep OkHttp's platform integrations; they are loaded reflectively.
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.internal.platform.** { *; }
