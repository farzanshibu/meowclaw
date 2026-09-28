# JNI entry points are looked up by name.
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.farzanshibu.meowclaw.llm.needle.NeedleNative { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Shizuku reflection (newProcess)
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn org.slf4j.**

# Cactus runtimes: JNI entry points and the token callback looked up by name.
-keep class com.farzanshibu.meowclaw.llm.cactus.CactusV1Native { *; }
-keep class com.farzanshibu.meowclaw.llm.cactus.CactusV2Native { *; }
-keep interface com.farzanshibu.meowclaw.llm.cactus.CactusTokenCallback { *; }
-keep class * implements com.farzanshibu.meowclaw.llm.cactus.CactusTokenCallback { *; }
