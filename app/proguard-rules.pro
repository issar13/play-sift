# kotlinx.serialization looks serializers up through Companion and $serializer members, so keep them
# on every @Serializable class (the Spotify request and response types).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers @kotlinx.serialization.Serializable class app.playsift.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

-keepclassmembers class app.playsift.**$$serializer {
    *** INSTANCE;
}
