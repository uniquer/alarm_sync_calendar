# CalAlarm Sync

Never miss your important calendar webinar sessions. **CalAlarm Sync** automatically converts your calendar events into loud, persistent system alarms, one click auto-sync, all calendar data is locally stored and processed.

## 🚀 Features

- **Auto Sync:** Automatically schedules, updates, or cancels alarms as your calendar events change.
- **Recurring Events:** Schedules alarms for recurring reminders or tasks easily.
- **60-Day Lookahead:** Scans your primary calendar 60 days into the future to keep your alarms up to date.
- **Privacy First:** All sync processing is done locally on your device; no calendar data is ever uploaded.

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
