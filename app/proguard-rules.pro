# Keep MediaProjection and encoding classes intact if minification is enabled later
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep FrameScope service, application, and data models
-keep class com.example.framescope.FrameScopeApplication { *; }
-keep class com.example.framescope.encoding.EncodingForegroundService { *; }

# Keep kotlinx.serialization generated serializers and @Serializable classes
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-dontwarn kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.example.framescope.**$$serializer { *; }
-keepclassmembers class com.example.framescope.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.framescope.** {
    kotlinx.serialization.KSerializer serializer(...);
}

