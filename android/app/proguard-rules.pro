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

## ONNX Runtime (AutoSync 逐字歌词对齐，0.6.5 新增)
# ⚠ 上游 0.6.5 引入了 onnxruntime-android 却没带任何 keep 规则。
#   它的 .so 在运行时用 JNI 按「类名 + 方法签名」反查 Java 侧方法，R8 一旦
#   改名/裁掉就直接 abort：
#     Abort message: JNI DETECTED ERROR IN APPLICATION: java_class == null
#       in call to GetMethodID
#       from boolean[] ai.onnxruntime.OrtSession.run(...)
#   症状：歌词加载到一半进程 SIGABRT，表现为「放几秒就停并退回后台」。
#   上游 CI 只跑 assembleDebug（不过 R8），所以它自己从没跑到这条路径。
-keep class ai.onnxruntime.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class ai.onnxruntime.** {
    native <methods>;
}
-dontwarn ai.onnxruntime.**

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
