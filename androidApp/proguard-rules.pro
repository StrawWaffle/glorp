# Ktor and kotlinx.serialization ship their own rules. Add app rules here.
-dontwarn org.slf4j.**

# QuickJS: its native code looks up these classes and members by name.
-keep class com.dokar.quickjs.** { *; }

# Crash reports: keep line numbers, so a stack trace and the release's
# mapping file (uploaded with each release) give the place in the code.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Google's code scanner (ML Kit): it builds its parts by reflection, and
# crashes in GmsBarcodeScanning.getClient when R8 strips them.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_code_scanner.** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }
