# GymCoach ProGuard/R8 rules. Inert while isMinifyEnabled = false (app/build.gradle.kts); reviewed
# together with the switch to minification (docs/build/FIRST_BUILD_CHECKLIST.md, Appendix A.4).

# kotlinx.serialization: keep generated serializers and the Companion.serializer() entry points.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.gymcoach.**$$serializer { *; }
-keepclassmembers class com.gymcoach.** {
    *** Companion;
}
-keepclasseswithmembers class com.gymcoach.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Knowledge JSON is loaded from the class path (ClasspathKnowledgeSource, §7.5): keep resources.
-keep class com.gymcoach.core.knowledge.ClasspathKnowledgeSource { *; }

# OkHttp / Okio ship their own consumer rules; nothing to add.
