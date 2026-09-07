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

# NewPipeExtractor brings Rhino for YouTube's JavaScript challenges. These optional
# desktop-Java integration paths are not available or invoked on Android.
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn javax.script.ScriptEngineFactory
