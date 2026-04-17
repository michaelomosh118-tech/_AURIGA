# Auriga Obfuscation & Shrinking Rules

# Maintain entry points for Android components
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# Maintain Auriga core classes
-keep public class com.drakosanctis.auriga.MainActivity
-keep public class com.drakosanctis.auriga.AurigaConfig
-keep class com.drakosanctis.auriga.TriangulationEngine$SpatialOutput { *; }
-keep class com.drakosanctis.auriga.BuildConfig { *; }

# Protect JSON logic for mesh network (using internal Android JSON)
-keep class org.json.** { *; }

# Maintain accessibility/voice features
-keep class android.speech.tts.** { *; }

# Support for Google Play Store requirements
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses

# Optimize for performance
-optimizationpasses 5
-allowaccessmodification
-dontpreverify
-repackageclasses ''
-overloadaggressively

# Keep names of native methods (if any are added later)
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep serializable classes
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
