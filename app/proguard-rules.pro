# R8/ProGuard выключены (isMinifyEnabled = false), файл оставлен на будущее.
-keepattributes SourceFile,LineNumberTable
-keep class by.charge.overlay.data.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
