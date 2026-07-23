# Reader activities, parser entry points, Room models and backup models are
# runtime-critical. Keep app classes stable across release shrinking.
-keep class com.simplereader.app.** { *; }

# EPUB4J reader (Apache-2.0).
-keep class io.documentnode.epub4j.** { *; }
-dontwarn io.documentnode.epub4j.**
-dontwarn org.kxml2.**

# Public pure-Java CHM reader (Apache-2.0).
-keep class org.jchmlib.** { *; }
-dontwarn org.jchmlib.**

# Mozilla universal charset detector used for TXT files.
-keep class org.mozilla.universalchardet.** { *; }
-dontwarn org.mozilla.universalchardet.**

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
