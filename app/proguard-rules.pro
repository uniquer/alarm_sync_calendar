# Keep GSON related classes and generic signatures
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }

# Keep our data models so GSON can deserialize them
-keep class com.example.alarmsynccalendar.ScheduledAlarm { *; }
-keep class com.example.alarmsynccalendar.AutoScheduleRule { *; }
-keep class com.example.alarmsynccalendar.calendar.EventInfo { *; }
-keep class com.example.alarmsynccalendar.calendar.CalendarInfo { *; }

# Prevent R8 from messing with TypeToken signatures
-keep class * extends com.google.gson.reflect.TypeToken
