# kotlinx.serialization keeps its generated serializers via @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class tr.com.uslanozan.evritext.** {
    *** Companion;
}
-keepclasseswithmembers class tr.com.uslanozan.evritext.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp pulls in optional platform classes it guards with reflection.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
