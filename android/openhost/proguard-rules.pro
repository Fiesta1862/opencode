# Keep OkHttp
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Keep JSch
-keep class com.jcraft.jsch.** { *; }

# Keep Kotlinx Coroutines
-keepnames kotlinx.coroutines.** { *; }

-keepnames class kotlinx.coroutines.** { *; }

# Keep Ktor engine
-keep class io.ktor.** { *; }

# Keep ZXing QR
-keep class com.google.zxing.** { *; }

# Remove logging in release
-assumenosideeffects class android.util.Log { *; }