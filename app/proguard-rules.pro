# ============================================================
# 羽光相机 release 混淆规则
# 目标：混淆自有代码，同时保证 TFLite 声控 / ViewBinding /
#       Camera2 回调这些"被框架或 native 反向调用"的路径不被裁掉。
# ============================================================

# ---------- 崩溃堆栈可读性 ----------
# 保留行号（报错时能定位到行），但把源文件名统一替换掉，不泄露真实文件名
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin data class / 泛型的签名信息必须留，否则反射与类型还原会出问题
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# ---------- TensorFlow Lite（离线声控） ----------
# JNI 从 native 侧回调 Java，相关类一律不能改名或删除
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
-keepclasseswithmembers class * {
    native <methods>;
}
-keepclassmembers class * {
    native <methods>;
}

# ---------- ViewBinding ----------
# ActivityMainBinding 由 AGP 生成并反射 inflate
-keep class com.teacher.shotlab.databinding.** { *; }

# ---------- 被 Android 框架回调的类 ----------
# 这些回调由系统进程反向调用，重写方法一旦被改名/删除，运行时直接失效
-keepclassmembers class * extends android.hardware.camera2.CameraCaptureSession$CaptureCallback {
    public *; protected *;
}
-keepclassmembers class * extends android.hardware.camera2.CameraDevice$StateCallback {
    public *; protected *;
}
-keepclassmembers class * implements android.hardware.SensorEventListener {
    public *;
}
-keepclassmembers class * implements android.media.MediaRecorder$OnInfoListener {
    public *;
}
-keepclassmembers class * implements android.media.MediaRecorder$OnErrorListener {
    public *;
}

# ---------- 对外可见的入口 ----------
# Manifest 里声明的组件 AGP 会自动保留，这里只补一层保险
-keep public class com.teacher.shotlab.MainActivity
-keep public class com.teacher.shotlab.GalleryActivity
