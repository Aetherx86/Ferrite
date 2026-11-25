# Serialized data classes keep their generated serializers.
-keepclassmembers class dev.omakasekim.ferrite.** {
    *** Companion;
}
-keepclasseswithmembers class dev.omakasekim.ferrite.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.omakasekim.ferrite.**$$serializer { *; }
