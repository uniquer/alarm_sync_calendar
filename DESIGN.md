# CalAlarm Sync - Architecture and Design

## Overview
CalAlarm Sync is a professional Android utility designed to bridge the gap between cloud calendar providers (Google and Outlook) and the Android system's Alarm Manager. It ensures that critical meetings always trigger high-priority system alarms, bypassing the "sync lag" often found in standard calendar apps.

## Core Features
1.  **Multi-Platform Cloud Sync:** Direct integration with Google Calendar (REST API v3) and Microsoft Outlook (Microsoft Graph API).
2.  **Unified Sync Engine:** Background worker that monitors all connected accounts and sub-calendars (shared, corporate, holiday) simultaneously.
3.  **Sub-Calendar Customization:** Users can toggle individual sub-calendars for each account. These selections are **persistently saved** and restored across app launches.
4.  **Recurring Alarm Chaining:** A custom logic that ensures only ONE active alarm entry exists for a recurring meeting, automatically scheduling the next instance once the current one fires.
5.  **Auto-Schedule Rules:** Powerful regex-based rules that match event titles and organizers to automatically create alarms with custom lead times.
6.  **Offline-First Local Storage:** All cloud events and user-defined alarms are **stored locally** in SharedPreferences. The app operates instantly on launch using this local cache, with background updates ensuring freshness.
7.  **Reliability-First Alarms:** Uses `AlarmManager` with Exact Alarms, WakeLocks, and custom OEM permission guidance to ensure alarms fire even if the device is asleep or locked.

## Architecture
-   **UI Layer:** 100% Jetpack Compose using Material 3 design standards. Follows a Single Activity architecture (`MainActivity`).
-   **Background Layer:** `WorkManager` performs a periodic sync cycle every 15 minutes.
-   **Data Layer:** Persistent storage via `SharedPreferences` with `GSON` serialization for all models (Alarms, Rules, Accounts, Caches).
-   **Security:** OAuth 2.0 based authentication using `GoogleSignInClient` and `AppAuth` for Android. No user passwords are ever stored.
-   **Networking:** Lightweight `HttpURLConnection` for REST API calls to minimize app size and dependencies.

## Technical Stack
-   **Language:** Kotlin
-   **UI:** Jetpack Compose, Material 3
-   **Auth:** Google Play Services Auth, OpenID AppAuth
-   **Networking:** Java standard HttpURLConnection
-   **Serialization:** Google Gson
-   **Background Processing:** Android WorkManager
-   **Scheduling:** Android AlarmManager, BroadcastReceivers

## Design Philosophy
The app follows a "Source of Truth" philosophy where the Cloud is the master record. The local phone state is forced to synchronize with the cloud every 15 minutes, ensuring that if a user changes a meeting on their computer, their phone alarm updates automatically within minutes.
