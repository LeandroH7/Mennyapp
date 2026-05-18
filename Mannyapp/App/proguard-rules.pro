# Menny ProGuard Rules
-keep class com.menny.assistant.** { *; }
-keepclassmembers class * extends android.service.notification.NotificationListenerService { *; }
