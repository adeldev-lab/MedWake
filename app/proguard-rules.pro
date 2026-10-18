# ==============================================================================
# MedWake (org.wakegated.medication) — Production R8 / ProGuard Configuration
# ==============================================================================

# Preserve source file and line numbers for readable crash stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Preserve Kotlin metadata and annotations required by Serialization & Room
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature

# ------------------------------------------------------------------------------
# 1. Room 2.8.4 (KSP Generated Implementations, Entities, DAOs, TypeConverters)
# ------------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase {
    <init>();
}
-keep @androidx.room.Database class * { *; }

# Keep all @Entity data classes and their fields/constructors so Cursor mapping works
-keep @androidx.room.Entity class * {
    <init>(...);
    <fields>;
    *;
}

# Keep @Dao interfaces and KSP-generated *_Impl classes
-keep @androidx.room.Dao class * { *; }
-keep class **.*_Impl { *; }

# Keep custom Room TypeConverters (referenced reflectively or via generated code)
-keepclassmembers class * {
    @androidx.room.TypeConverter *;
}

# ------------------------------------------------------------------------------
# 2. Kotlinx Serialization 1.11.0 (JSON / Models)
# ------------------------------------------------------------------------------
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Keep Companion objects and serializer getter methods on @Serializable classes
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class org.wakegated.medication.**$$serializer { *; }
-keepclassmembers class org.wakegated.medication.** {
    *** Companion;
}
-keepclasseswithmembers class org.wakegated.medication.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep Kotlin Enums used in database entities or preferences
-keepclassmembers enum org.wakegated.medication.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ------------------------------------------------------------------------------
# 3. Kotlin Coroutines 1.10.1
# ------------------------------------------------------------------------------
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Strip coroutine debug probes in release builds
-assumenosideeffects class kotlinx.coroutines.DebugKt {
     java.lang.String DEBUG_PROPERTY_NAME;
     boolean getASSERTIONS_ENABLED();
     boolean getDEBUG();
     boolean getRECOVER_STACK_TRACES();
}

# ------------------------------------------------------------------------------
# 4. ViewBinding & Android Wake/Alarm Components
# ------------------------------------------------------------------------------
# Prevent ViewBinding classes from having their inflate/bind methods stripped
-keepclassmembers class * implements androidx.viewbinding.ViewBinding {
    public static *** inflate(...);
    public static *** bind(...);
}

# Keep BroadcastReceivers and Services (Wake-gated alarm receiver, boot receiver, foreground service)
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.app.Service
