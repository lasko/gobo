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

# Custom Tabs: AppAuth binds the Custom Tabs service (CustomTabsClient.bind…/newSession,
# CustomTabsIntent) to open the OGS login in a *Custom Tab* — which is what returns to the app
# on the gobo://oauth redirect. R8 full mode strips these (our code never references them
# directly), so the release build fell back to opening a plain browser tab. Chrome happens to
# dispatch the custom-scheme redirect from a plain tab too (masking the bug), but hardened
# browsers like Cromite do not, so login hung on the OGS page. Keep the Custom Tabs API.
-keep class androidx.browser.customtabs.** { *; }
-dontwarn androidx.browser.**

# androidx.security-crypto pulls in Tink, which references compile-only Error Prone
# annotations that aren't on the runtime classpath. They're harmless at runtime, so
# silence R8's missing-class errors for them (these would fail the release build only).
-dontwarn com.google.errorprone.annotations.**
