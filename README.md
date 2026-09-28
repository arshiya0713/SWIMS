# SWIMS — Smart Water Intake Monitoring System
### CB4504 Mobile Application Development Project

---

## How to open and run

1. **Extract this zip** anywhere on your computer.
2. Open **Android Studio** → File → Open → select the `SWIMS` folder.
3. Wait for Gradle sync to finish (needs internet once to download dependencies).
4. Connect an Android phone (API 26+) or start an emulator.
5. Click **Run ▶**.

That's it. No Firebase setup, no API keys, no accounts, no cost.

---

## What the app does

| Feature | Description |
|---|---|
| Onboarding | Enter weight, age, activity level → personalised daily goal calculated |
| Home dashboard | Circular progress ring, streak counter, today's total |
| Quick-add | One tap to log 200 / 350 / 500 / 750 ml |
| Custom log | Enter any amount + optional note |
| Reminder engine | WorkManager fires "Drink water!" notifications at your chosen interval |
| History – Weekly | Bar chart of last 7 days |
| History – Monthly | Line chart of last 30 days |
| Streak tracker | Consecutive days goal was met |
| Settings | Edit profile, toggle reminders, adjust interval |
| Delete all data | Permanently wipes DB and profile (with confirmation) |
| Drink types | Water / tea / coffee / juice / milk with hydration factors (coffee credits 90%) |
| Home-screen widget | Live progress + one-tap "+250 ml" without opening the app |
| Achievements | 8 unlockable badges computed from your real history, confetti on goal |
| Calendar heatmap | Month grid tinted by goal completion — habits at a glance |
| ✨ Smart goal | On-device learner (EWMA) refines the formula goal from your own history |
| ✨ Smart reminders | Learns your hourly drinking pattern — skips nudges when you're on pace, just logged, or asleep |
| ✨ Anomaly detection | Flags unusually low-intake days (z-score) |
| ✨ Weekly insights | Auto-generated text summary of trends on the History screen |

## Adaptive AI (v2.0)

Three AI capabilities were added on top of the statistical layer below.

### 1. Contextual bandit for reminder timing — `ml/bandit/ContextualBandit.kt`

A Just-In-Time Adaptive Intervention (JITAI) policy that *learns whether a
reminder is worth sending at all*, rather than firing on a fixed interval.

- **Contexts** (36) — hour-of-day bucket × hydration deficit level × weekday/weekend.
- **Arms** (3) — stay silent · gentle nudge · motivational nudge.
- **Algorithm** — Beta-Bernoulli posteriors per (context, arm), solved with
  **Thompson sampling**; forced exploration warms up unseen arms.
- **Reward** — drank within 30 min, minus a 0.15 interruption cost, so an
  unnecessary notification scores *worse* than silence.
- **Availability gate** — hard safety rules (goal met, just logged, learned
  quiet hours) run *before* the bandit, so it can never explore at 3 a.m.
- **Credit assignment** — decisions are persisted and resolved once the
  attribution window closes, then folded back into the posterior.

### 1b. Ask SWIMS — on-device Q&A — `ml/nlp/HydrationQA.kt`

A chat interface (History → "Ask SWIMS") that answers questions about the
user's own hydration in plain language: *"how much today?"*, *"what's my
streak?"*, *"when do I usually drink?"*, *"how much coffee do I drink?"*,
*"what have the reminders learned?"*.

Intent detection is keyword-based and every answer is composed from live
database queries, so replies are **grounded in real data and cannot
hallucinate** — when the data isn't there it says so ("I need a few days of
logs before I can spot your drinking pattern").

### 2. Multimodal logging

| Mode | Implementation |
|---|---|
| Natural language | `ml/nlp/DrinkTextParser.kt` — rule-based parser: drink synonyms, container volumes, size modifiers, counts, explicit units (ml/l/oz), note extraction. Deterministic, offline, explainable. |
| Voice | In-app `SpeechRecognizer` with **live partial transcription** and 3 s silence tolerance, so it waits while you think. (Handing off to the system speech dialog was unreliable — it dismissed after ~1 s of perceived silence.) Requires `RECORD_AUDIO`; audio is handled by the device speech service and never stored by SWIMS. |
| Photo | `ml/vision/DrinkImageClassifier.kt` — **ML Kit bundled TFLite image labeling**. Maps labels to a drink type plus a volume inferred from the recognised container. Shows its labels and confidence, and on failure reports what it *did* see. |

### 3. Federated learning — `sync/FederatedPolicyClient.kt`

Federated Thompson sampling, so a new user benefits from the population without
anyone's raw data being centralised.

- **Shared** — only the *change* in Beta pseudo-counts per (context, arm).
- **Never shared** — intake amounts, timestamps, drink types, profile, location.
- **Privacy** — contributions are **clipped** (bounding one user's influence),
  then **Laplace noise** at scale `clip/ε` gives ε-differential privacy;
  Firestore atomic increments mean the server stores only running sums.
- **Blending** — the global mean enters as a weak prior (4 pseudo-counts), so
  the population helps on day one but personal data dominates over time.
- Requires explicit opt-in **and** a Firebase config; otherwise fully local.

## On-device machine learning (`ml/HydrationIntelligence.kt`)

All learning runs locally from the encrypted database — no cloud, no telemetry:

- **AdaptiveGoalEngine** — exponentially weighted moving average over the last 28 days,
  blended 50/50 with the formula goal, stretched +5%, bounded to ±20% of the formula
  and the 1,500–5,000 ml safety clamp. Cold-starts to the formula until 7 days of history.
- **DrinkingPatternModel** — Laplace-smoothed hourly intake histogram; provides
  per-hour share, cumulative pace, and learned quiet hours.
- **SmartReminderEngine** — decides at each reminder tick whether a nudge is useful,
  and personalises the message ("you're 400 ml behind your usual pace").
- **AnomalyDetector** — flags days below mean − 1.25·σ of the user's own distribution.
- **InsightGenerator** — template NLG over averages, least-squares trend slope,
  weekday patterns and anomalies.

Everything is pure Kotlin (no Android imports) and can be unit-tested on the JVM.
The "Smart features" switch in Settings turns all of it off in one tap.

---

## Saving your data — two independent options

SWIMS never *requires* an account. Both of these are opt-in.

### 1. Backup file — works immediately, no setup, no account
**Settings → Account & backup → Export** writes your whole history to a plain
JSON file wherever you choose (Drive, Files, anywhere). **Restore** reads it
back. Restoring merges rather than replaces — logs you already have are matched
on timestamp and skipped, so importing twice is harmless.

Verified end-to-end: exported, wiped the app completely (`pm clear`), reinstalled,
re-onboarded with different details, restored the file — the full history came back.

### 2. Account (optional, needs Firebase) — `sync/AccountManager.kt`
Email + password sign-up / sign-in / sign-out, so history survives a lost phone
and appears on other devices.

- If the device was already syncing anonymously, creating an account **links**
  that identity rather than replacing it — data logged before signing up carries over.
- Signing in automatically enables cloud sync; signing out stops syncing but
  **never deletes local data**.
- Password reset by email is supported.
- Passwords go straight to Firebase Auth. SWIMS never stores them, and they are
  never used to derive the local database encryption key.
- Without `app/google-services.json` the card reports "not configured" and the
  app is local-only — the backup file above still works.

## Online features (offline-first)

SWIMS is **offline-first**: it works fully without a network, and uses the internet
only to make two features better when a connection exists.

| Feature | Online | Offline fallback |
|---|---|---|
| 🌡️ Weather-smart goal | Fetches today's max temperature (Open-Meteo, free, no API key) and raises the goal up to +800 ml on hot days | Reuses the last fetched temperature for up to 12 h, then applies no bonus |
| ☁️ Cloud sync & backup | Optional. Anonymous Firebase account; logs & profile sync across devices and survive reinstalls | Everything stays in the local encrypted DB; sync resumes when back online |

- Set your city in **Settings → Online features** (typed city, no GPS/location permission).
- All traffic is HTTPS-only (cleartext blocked by `network_security_config.xml`).
- Both features can be switched off — the app then behaves exactly like the
  original fully-offline version.

### Enabling cloud sync (one-time, ~5 min, free)

Cloud sync ships **disabled and unconfigured** — the app builds and runs without it.
To activate it you need your own (free) Firebase project:

1. Go to https://console.firebase.google.com → **Add project** (any name, Analytics off).
2. Add an **Android app** with package name `com.swims.app` and download `google-services.json`.
3. Drop that file into `app/` (next to `build.gradle`) and rebuild.
4. In the console enable **Authentication → Anonymous** and **Firestore Database**.
5. In the app: Settings → Online features → turn on **Cloud sync**.

Without step 1–4 the sync toggle simply reports "not configured" and the app stays local-only.

## Security & privacy — how your data is protected

### 1. Encrypted database (AES-256 via SQLCipher)
The Room database file (`swims_encrypted.db`) is encrypted on disk using **SQLCipher with AES-256**.
Even if someone physically extracts the `.db` file from the device, they cannot read it.

### 2. Hardware-bound key (Android Keystore)
The database passphrase is stored in **EncryptedSharedPreferences**, backed by the
**Android Keystore** hardware security module. The key never leaves the secure hardware element.

### 3. No cloud backup
`android:allowBackup="false"` and explicit exclusion rules in `backup_rules.xml` ensure
**zero health data is ever uploaded to Google Drive or any cloud service**.

### 4. Minimal, transparent network use
The app talks to at most two services, both over HTTPS only:
Open-Meteo (weather — receives only your chosen city's coordinates, no identity)
and, **only if you enable it**, your own Firebase project for sync.
No analytics, no ads, no trackers.

### 5. No cleartext traffic
`network_security_config.xml` blocks all HTTP traffic — HTTPS only, system-trusted
certificates only.

### 6. Minimal permissions
Only these permissions are requested:
- `POST_NOTIFICATIONS` — to send drink reminders
- `RECEIVE_BOOT_COMPLETED` — to reschedule reminders after reboot
- `INTERNET` / `ACCESS_NETWORK_STATE` — weather + optional sync (offline-first)

No location, no camera, no contacts, no microphone — your city is typed, not tracked.

No location, no camera, no contacts, no microphone.

### 7. User data control
Settings → "Delete all my data" permanently wipes the encrypted DB and all preferences.

---

## Cost breakdown

| Item | Cost |
|---|---|
| Android Studio | Free |
| Room + ViewModel + WorkManager (Jetpack) | Free (Apache 2.0) |
| SQLCipher | Free (BSD-style open source) |
| MPAndroidChart | Free (Apache 2.0) |
| Firebase / cloud services | **None used** |
| Play Store listing (optional) | One-time $25 developer registration |
| App itself for users | **Free, forever** |

---

## Project structure

```
app/src/main/
├── java/com/swims/app/
│   ├── SwimsApplication.kt          — App class, notification channel
│   ├── data/
│   │   ├── model/                   — UserProfile, IntakeLog, DailyStats
│   │   ├── db/                      — SwimsDatabase (SQLCipher), SwimsDao
│   │   └── repository/              — SwimsRepository
│   ├── viewmodel/                   — HomeViewModel, HistoryViewModel, SettingsViewModel
│   ├── ui/
│   │   ├── onboarding/              — OnboardingActivity
│   │   ├── home/                    — MainActivity, HomeFragment, LogAdapter
│   │   ├── history/                 — HistoryFragment
│   │   └── settings/                — SettingsFragment
│   ├── receiver/                    — BootReceiver
│   └── util/                        — GoalCalculator, ReminderScheduler, ReminderWorker
└── res/
    ├── layout/                      — All XML layouts
    ├── navigation/nav_graph.xml
    ├── menu/bottom_nav_menu.xml
    ├── drawable/                    — Icons, circular progress drawable
    └── xml/                         — Network security, backup rules
```

---

## Architecture

```
UI (Fragment/Activity)
        ↕  observes LiveData
ViewModel (no Android imports except Application)
        ↕  suspend functions
Repository (single source of truth)
        ↕
Room DAO ←→ SQLCipher-encrypted .db file
```

MVVM pattern — Unit II + III mapped directly to project.

---

## CB4504 Syllabus mapping

| Unit | Topics used |
|---|---|
| I | Platform planning, security issues, software-only architecture |
| II | Android Studio, SDK (AlarmManager), Manifest permissions, MVVM architecture |
| III | Activities (5 screens), Services, Broadcast Receivers, Fragments, Notification Listeners, Intents |
| IV | UI Widgets, ConstraintLayout, RecyclerView, Room DB, MPAndroidChart graphics |
| V | SQLite/Room, WorkManager (background APIs), REST/JSON networking, offline-first design |
