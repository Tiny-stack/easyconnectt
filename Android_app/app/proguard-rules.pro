# ZXing embedded scanner uses some reflection; keep its public API intact.
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# Kotlin metadata is not needed at runtime.
-dontwarn kotlin.**
