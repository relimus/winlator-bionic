# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in C:\tools\adt-bundle-windows-x86_64-20131030\sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

#-dontobfuscate

# Native methods are resolved by static JNI names in libwinlator.so.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Native code looks up these Java classes and callbacks by string name.
-keep class com.winlator.cmod.core.AppUtils {
    public static java.lang.String getNativeLibDir(android.content.Context);
}
-keep class com.winlator.cmod.contents.AdrenotoolsManager {
    public <init>(android.content.Context);
    public java.lang.String getLibraryName(java.lang.String);
}
-keepclassmembers class com.winlator.cmod.xconnector.XConnectorEpoll {
    private void handleNewConnection(int);
    private void handleExistingConnection(int);
}
-keep class com.winlator.cmod.XServerDisplayActivity {
    boolean performanceMode;
    float getRefreshRate();
    void updateFrameRating(com.winlator.cmod.xserver.Window);
}
-keepclassmembers class com.winlator.cmod.xconnector.ClientSocket {
    public void addAncillaryFd(int);
}
-keepclassmembers class com.winlator.cmod.renderer.GPUImage {
    private void setStride(short);
}

# The renderer JNI cache resolves these classes, fields, and methods by their
# original names and signatures in renderer_jni.hpp.
-keep class com.winlator.cmod.xserver.XServer { *; }
-keep class com.winlator.cmod.xserver.Window { *; }
-keep class com.winlator.cmod.xserver.WindowAttributes { *; }
-keep class com.winlator.cmod.xserver.WindowManager { *; }
-keep class com.winlator.cmod.xserver.InputDeviceManager { *; }
-keep class com.winlator.cmod.xserver.Drawable { *; }
-keep class com.winlator.cmod.xserver.Cursor { *; }
-keep class com.winlator.cmod.renderer.GPUImage { *; }

# Retrofit needs runtime annotations and generic signatures.
-keepattributes Signature, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-keep interface com.winlator.cmod.bigpicture.steamgrid.SteamGridDBApi { *; }
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl

# zstd-jni native code reads these fields by their original names.
-keepclassmembers class com.github.luben.zstd.ZstdInputStreamNoFinalizer {
    private long dstPos;
    private long srcPos;
    private long srcSize;
}
-keepclassmembers class com.github.luben.zstd.ZstdOutputStreamNoFinalizer {
    private long srcPos;
    private long dstPos;
}

# Gson models use @SerializedName and reflection-based adapters.
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.winlator.cmod.bigpicture.steamgrid.** { *; }
