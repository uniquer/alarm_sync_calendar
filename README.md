# CalAlarm Sync

Never miss your important calendar webinar sessions. **CalAlarm Sync** automatically converts your calendar events into loud, persistent system alarms, one click auto-sync, all calendar data is locally stored and processed.

## 🚀 Features

- **Bidirectional Sync:** Automatically updates or cancels alarms when calendar events change or are deleted.
- **Auto-Schedule Rules:** Create automation rules based on meeting organizers to schedule alarms for future events automatically.
- **90-Day Lookahead:** Scans your calendar 3 months into the future for consistent planning.
- **Flexible Lead Times:** Set alarms exactly on time, or 5, 10, or 15 minutes before your meetings.
- **Privacy First:** All data is processed locally on the device. No calendar data is ever uploaded to external servers.
- **Material 3 Design:** A modern, responsive UI with deep-blue branding.
- **Background Engine:** Reliable sync powered by Android WorkManager, checking for updates every 15 minutes.

## 🛠 Tech Stack

- **Language:** Kotlin
- **Framework:** Jetpack Compose (UI)
- **Background Processing:** WorkManager & AlarmManager
- **Persistence:** SharedPreferences & GSON
- **Build System:** Gradle (Kotlin DSL)

## 📦 Installation & Setup

1. **Clone the Repo:**
   ```bash
   git clone https://github.com/[your-username]/alarm-sync-calendar.git
   ```
2. **Open in Android Studio:**
   Import the project and wait for the Gradle sync to complete.
3. **Set Up SDK:**
   Ensure you have Android SDK 34 installed.
4. **Build:**
   ```bash
   ./gradlew assembleDebug
   ```

## 🔐 Permissions

The app requires the following permissions to function:
- `READ_CALENDAR`: To scan for upcoming meetings.
- `SCHEDULE_EXACT_ALARM`: To ensure alarms trigger precisely on time.
- `POST_NOTIFICATIONS`: To show meeting reminders.
- `RECEIVE_BOOT_COMPLETED`: To re-schedule alarms after a device restart.

## 🤝 Contribution & Design Considerations

When contributing or updating the app, please keep the following in mind:
- **Data Schema:** Any changes to `ScheduledAlarm` or `AutoScheduleRule` require careful migration logic to avoid breaking existing user data.
- **Package Stability:** Maintain the `com.nen.alarmsynccalendar` namespace for Play Store compatibility.
- **Adaptive Icons:** Ensure logo changes respect the 66dp center safe zone for Android Adaptive Icons.

## 📄 License

Distributed under the MIT License. See `LICENSE` for more information.

## 👤 Author

Developed and maintained by **Karthik**.
