# R8 rules for the release build. Retrofit, OkHttp, kotlinx.serialization, Room, Hilt and WorkManager ship their own
# consumer rules; only what they can't know about is here.

# SQLCipher: native code calls back into these classes over JNI, so their names and members must survive.
-keep class net.zetetic.database.** { *; }
-keep class net.zetetic.database.sqlcipher.** { *; }

# Our @Serializable wire types are reached through generated serializers (covered by the library's rules); keep the
# DTO field names readable in crash reports only, they are never used reflectively by name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Release builds must not log call data. Strip verbose/debug/info logging entirely (warnings and errors stay; they
# never contain numbers or notes, see CLAUDE.md "Never log raw phone numbers").
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
