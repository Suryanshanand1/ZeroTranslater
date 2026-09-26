# ML Kit ships consumer ProGuard rules that keep its model entry points.
# These extra rules are defensive: they pin the JNI entry points that the
# translate model loader resolves reflectively.

-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**

# Model packs are loaded by filename from the Play services module.
-keepclasseswithmembernames class * {
    native <methods>;
}
