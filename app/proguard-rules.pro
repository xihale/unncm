# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep jaudiotagger classes but allow AWT/Swing classes to be missing
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.imageio.stream.**
-dontwarn javax.swing.**
-dontwarn javax.swing.filechooser.**
-dontwarn javax.swing.tree.**

# Keep jaudiotagger core functionality (exclude test classes)
-keep class org.jaudiotagger.** { *; }
-keep class org.jaudiotagger.audio.** { *; }
-keep class org.jaudiotagger.tag.** { *; }

# Exclude jaudiotagger test classes (they use Swing)
-dontwarn org.jaudiotagger.test.**
-dontnote org.jaudiotagger.test.**

# Keep Android-specific artwork implementation
-keep class org.jaudiotagger.tag.images.AndroidArtwork { *; }

# Allow reflection in jaudiotagger
-keepclassmembers class * {
    @org.jaudiotagger.tag.id3.framebody.ID3v2FrameBody <fields>;
}

# Keep enum classes
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep custom SafeAndroidArtwork implementation
-keep class top.xihale.unncm.AudioMetadataProcessor$SafeAndroidArtwork { *; }