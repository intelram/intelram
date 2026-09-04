# Add project specific ProGuard rules here.

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.threadprotection.app.**$$serializer { *; }
-keepclassmembers class com.threadprotection.app.** { *** Companion; }
-keepclasseswithmembers class com.threadprotection.app.** { kotlinx.serialization.KSerializer serializer(...); }

# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes Signature, Exceptions
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keep class retrofit2.Response

# ML Kit / CameraX ship their own consumer rules; nothing extra required here.

# Bouncy Castle — post-quantum ML-KEM key exchange + AES-GCM chat crypto (PqcChatCrypto.kt).
# Root-cause fix for "NoSuchMethodError ... something went wrong" crashing chat on the responder
# side only: with no keep rule at all, R8 was free to strip/rename members of the PQC classes it
# judged unreachable from its own static analysis — which differs between the initiator's call
# graph (decapsulate()/MLKEMExtractor) and the responder's (encapsulate()/MLKEMGenerator), so one
# side could lose a method the other kept. This only ever showed up in release builds (isMinifyEnabled
# true) since debug skips R8 entirely — every release APK built before this had the bug latent.
# Crypto code is also exactly the wrong place to let an optimizer guess: keep the whole library
# untouched rather than trying to enumerate exactly which members are actually used.
-keep class org.bouncycastle.** { *; }
-keepclassmembers class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
