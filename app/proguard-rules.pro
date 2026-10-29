# LiteRT-LM & LiteRT JNI
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.google.ai.edge.litert.** { *; }

# ObjectBox
-keep class io.objectbox.** { *; }
-keep @io.objectbox.annotation.Entity class * { *; }

# JetBrains Koog & Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class ai.wristmind.app.**$$serializer { *; }

# AppFunctions & AppSearch
-keep class androidx.appfunctions.** { *; }
-keep class androidx.appsearch.** { *; }
