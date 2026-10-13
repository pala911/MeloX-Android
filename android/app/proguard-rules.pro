# JavaScript bridges are invoked by name from bundled WebView assets.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# JSON/domain objects are decoded explicitly; retain enum names used in persisted settings.
-keepclassmembers enum com.lladlam.melox.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Kotlin file-level static functions are resolved by R8 as *Kt classes.
# Retain the ones that are referenced across module boundaries or via companion objects.
-keep class com.lladlam.melox.core.account.NeteaseSessionStoreKt { *; }
-keep class com.lladlam.melox.core.provider.qqmusic.QQMusicSessionStoreKt { *; }
-keep class com.lladlam.melox.core.provider.kugou.KugouSessionStoreKt { *; }
-keep class com.lladlam.melox.core.provider.qqmusic.QQMusicApiClientKt { *; }
-keep class com.lladlam.melox.core.network.NeteaseSearchClientKt { *; }

# Shizuku and optional vendor bridges are discovered through Android framework metadata.
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-keep class com.lladlam.melox.platform.xiaomi.ShizukuKeepAliveService { *; }
-keep class org.lsposed.hiddenapibypass.** { *; }
-dontwarn org.lsposed.hiddenapibypass.**
-dontwarn com.apple.android.music.**

## ONNX Runtime — 已随 AutoSync 神经对齐一起移除（2026-10-13）
# 曾需要下列规则：上游 0.6.5 的 onnxruntime-android 没有自带 keep 规则，
# 其 .so 运行时按 JNI「类名 + 方法签名」反查 Java 方法，R8 一改名就
#   JNI DETECTED ERROR IN APPLICATION: java_class == null
#     in call to GetMethodID
#     from boolean[] ai.onnxruntime.OrtSession.run(...)
# 导致加载歌词时 SIGABRT（表现为「放几秒就停并退回后台」）。
# 现已不再打包 ONNX（省 55.7 MB），规则一并移除，避免留下误导性的死配置。

## Rules for NewPipeExtractor
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**

# Rhino JS engine references desktop APIs that are unavailable on Android.
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn javax.script.AbstractScriptEngine
-dontwarn javax.script.Bindings
-dontwarn javax.script.Compilable
-dontwarn javax.script.CompiledScript
-dontwarn javax.script.Invocable
-dontwarn javax.script.ScriptContext
-dontwarn javax.script.ScriptEngine
-dontwarn javax.script.ScriptEngineFactory
-dontwarn javax.script.ScriptException
-dontwarn javax.script.SimpleBindings
-dontwarn jdk.dynalink.**
