# Ktor and kotlinx.serialization ship their own rules. Add app rules here.
-dontwarn org.slf4j.**

# QuickJS: its native code looks up these classes and members by name.
-keep class com.dokar.quickjs.** { *; }
