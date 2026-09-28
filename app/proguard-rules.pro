# Keep Room entities
-keep class com.swims.app.data.model.** { *; }

# Keep SQLCipher
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }

# Keep MPAndroidChart
-keep class com.github.mikephil.charting.** { *; }

# Keep WorkManager workers
-keep class com.swims.app.util.ReminderWorker { *; }
