# Project Instructions: Alarm Sync Calendar

## Agent Workflow
- **Auto-Approvals:** The agent has full permission to perform all edits, create files, and execute shell commands as needed to fulfill directives without seeking explicit permission for each step.
- **Standards:** Maintain Material 3 design standards and ensure all resource changes respect Android adaptive icon guidelines (center 66dp safe zone).

## Project History & Changes

### 2026-05-02: Advanced Automation & Production Readiness
- **Features:** 
    - Implemented **Auto-Schedule Rules** (Organizer-based filtering).
    - Added **Background Sync Engine** (WorkManager, 15m interval).
    - Unified **90-day calendar look-ahead** across all features.
- **Production:**
    - Package name updated to `com.nen.alarmsynccalendar`.
    - Enabled R8/ProGuard obfuscation for security.
    - Integrated In-App "About & Privacy" and created external marketing landing page.
- **Logo:** Finalized with custom SVG vector (Indigo background, Yellow sync elements).

## Future Update Design Considerations

To ensure that future app updates do not impact existing alarms or data, follow these guidelines:

1. **Data Schema Stability:**
   - **JSON Migration:** The app uses GSON to store alarms and rules. If the `ScheduledAlarm` or `AutoScheduleRule` models change (e.g., field renaming), use a versioning system in SharedPreferences to trigger data migration scripts.
   - **Reset Safety:** The current `loadAlarms` logic includes a `try-catch` that resets data on failure. While this prevents crashes, it causes data loss. Future versions should prioritize field-by-field migration.

2. **Alarm Manager Persistence:**
   - **ID Derivation:** Alarms are scheduled using an `Int` ID derived from `calendarEventId`. If the logic for mapping `Long` event IDs to `Int` alarm IDs changes, old alarms will become orphaned. Always maintain backward-compatible ID mapping.
   - **Signing Key:** The app **must** be signed with the same keystore for every update. Changing the signature will cause the device to refuse the update and wipe all app data.

3. **Android Permission Evolution:**
   - **Exact Alarms:** `SCHEDULE_EXACT_ALARM` requirements change with every Android version. Always re-check permissions in `MainActivity.onCreate()` to ensure the background sync can still function.
   - **Battery Optimizations:** Continue to monitor Google Play policies regarding `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

4. **Background Sync:**
   - **Unique Work:** The `SyncWorker` is registered as a "Unique Periodic Work." This ensures that updates to the app don't create duplicate sync jobs.
