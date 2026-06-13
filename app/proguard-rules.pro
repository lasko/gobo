# Keep our own serializer metadata.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.gobo.app.**$$serializer { *; }
-keepclassmembers class com.gobo.app.** { *; }

# AppAuth: keep the whole library. R8 full mode otherwise strips the redirect-return
# path (AuthorizationManagementActivity, AuthorizationManagementUtil.responseFrom, …)
# because much of AppAuth is reached via the manifest/PendingIntents that R8 can't
# trace — so in a *release* build the browser opens but the gobo://oauth callback
# silently fails to finish login. The debug build doesn't minify, which is why this
# only bit the signed release APK. (AppAuth is small; keeping it whole is the standard fix.)
-keep class net.openid.appauth.** { *; }
-dontwarn net.openid.appauth.**

# androidx.security-crypto pulls in Tink, which references compile-only Error Prone
# annotations that aren't on the runtime classpath. They're harmless at runtime, so
# silence R8's missing-class errors for them (these would fail the release build only).
-dontwarn com.google.errorprone.annotations.**
