# Keep stack traces actionable in Firebase Crashlytics while still allowing R8 to
# optimise app code. Firebase and AndroidX ship their own consumer rules.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Components instantiated by Android/Firebase from the manifest must retain
# their constructors in a minified release build.
-keep class * extends android.app.Activity { <init>(); }
-keep class * extends com.google.firebase.messaging.FirebaseMessagingService { <init>(); }
-keep class androidx.core.content.FileProvider { <init>(); }

# Socket.IO discovers transport implementations dynamically.
-keep class io.socket.** { *; }
-dontwarn okhttp3.**
