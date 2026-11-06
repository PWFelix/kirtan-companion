# Ktor / OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.slf4j.**

# kotlinx.serialization keeps its generated serializers reachable already, but
# the reflective fallback names must survive shrinking.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.kirtan.companion.**$$serializer { *; }
-keepclassmembers class com.kirtan.companion.** {
    *** Companion;
}
-keepclasseswithmembers class com.kirtan.companion.** {
    kotlinx.serialization.KSerializer serializer(...);
}
