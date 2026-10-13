# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles settings in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep Shizuku classes
-keep class rikka.shizuku.** { *; }

# Keep AIDL interfaces
-keep interface com.android.internal.telephony.** { *; }
-keep class com.android.internal.telephony.**$* { *; }
