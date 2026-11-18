# Add rules here as libraries require them.

# JNA, which the generated Rust bindings (mn.lls.pocketkeepass.core) call the engine through.
# It finds classes, fields, and methods by name at runtime, so none of them may be renamed
# or removed.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class mn.lls.pocketkeepass.core.** { *; }
# JNA's desktop-only code refers to AWT, which Android doesn't have.
-dontwarn java.awt.**
