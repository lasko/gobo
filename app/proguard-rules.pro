# AppAuth + OkHttp are reflection-light; keep serialization metadata.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.gobo.app.**$$serializer { *; }
-keepclassmembers class com.gobo.app.** { *; }
