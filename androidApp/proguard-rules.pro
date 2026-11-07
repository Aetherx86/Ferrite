# Serialized data classes keep their generated serializers.
-keepclassmembers class dev.aetherx86.ferrite.** {
    *** Companion;
}
-keepclasseswithmembers class dev.aetherx86.ferrite.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.aetherx86.ferrite.**$$serializer { *; }
