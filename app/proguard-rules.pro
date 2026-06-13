# AppAuth + OkHttp are reflection-light; keep serialization metadata.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.gobo.app.**$$serializer { *; }
-keepclassmembers class com.gobo.app.** { *; }

# androidx.security-crypto pulls in Tink, which references compile-only Error Prone
# annotations that aren't on the runtime classpath. They're harmless at runtime, so
# silence R8's missing-class errors for them (these would fail the release build only).
-dontwarn com.google.errorprone.annotations.**
