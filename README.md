# SMS to Telegram

Forwards every incoming SMS from an old Android phone to your own Telegram bot.
Main goal: deliver every SMS as fast as possible (normally within a minute even if the
first attempt fails) and never lose one because of network problems.

Bot commands (the bot talks only to the linked chat):
- `/start`: links the chat (only the first private chat that sends it).
- `/ping`: replies `pong`.
- `/perf`: shows the current mode; `/energy`, `/balanced`, `/performance` switch it.

Each SMS arrives as: the sender in **bold**, an empty line, *SIM1 · 08.11 19:04* in italics
(the time the SMS was sent, from the operator), an empty line, then the text. The text is sent as
Telegram HTML with `&`, `<` and `>` escaped; if Telegram still rejects it, the same message
is resent as plain text, so formatting can never block delivery.

## 1. Create a bot
1. In Telegram, open **@BotFather** → `/newbot` → choose a name.
2. Copy the token (`123456789:AAE...`). Keep it secret: whoever has it controls the bot.

## 2. Build the APK (no Android Studio needed)
Requirements: JDK 17 and the Android SDK (`platforms;android-35`, `build-tools;34.0.0`).
`build.ps1` expects them in `D:\Tools\android-build` (`jdk\`, `sdk\`, `gradle-home\`); another
folder can be given with `-ToolsDir` or the `SMSTG_TOOLS` environment variable.

```powershell
.\build.ps1             # unit tests + APK → SmsToTelegram.apk
.\build.ps1 -SkipTests  # APK only
```

Output: `SmsToTelegram.apk` in the project folder (also `app/build/outputs/apk/debug/app-debug.apk`).

**Tests** (`app/src/test`) are plain JVM tests for the logic that needs no device: retry delays,
message formatting and escaping, duplicate filtering, connection tracking, network error reasons,
modes. JUnit is a test-only dependency; the APK has no third-party libraries.
Alternatively, open the folder in Android Studio and use *Build → Build APK(s)*.

## 3. Install on the old phone (tested target: Xiaomi, MIUI 13, Android 12)
Google Play Protect blocks sideloaded apps that request SMS access
("App blocked to protect your device"). Two ways around it:

- **Temporarily disable Play Protect:** Play Store → profile → Play Protect → ⚙️ →
  turn off "Scan apps with Play Protect", install the APK, then turn it back on.
  If Play Protect later warns about the app, choose "Keep" / "Ignore".
- **Install over USB with ADB** (not blocked): enable Developer options and
  USB debugging (plus "Install via USB" on MIUI), then `adb install -r SmsToTelegram.apk`.

## 4. First run
1. Open **SMS to Telegram**, paste the token, press **Start**.
2. Allow **SMS** access.
3. Allow "Ignore battery optimization".
4. In Telegram, open your bot and send `/start`. It replies "Chat linked".
5. Press **Send test message** in the app, or send `/ping` to the bot.
6. Send a real SMS to the old phone: it should appear in the bot.

## 5. Required MIUI settings (otherwise MIUI kills the app or blocks SMS)
*Settings → Apps → Manage apps → SMS to Telegram*:
- **Autostart**: on.
- **Battery saver**: **No restrictions**.
- **Other permissions**: allow everything SMS-related (MIUI has its own SMS
  permissions on top of Android's, e.g. "Service SMS" / "Notification SMS").
- **Notifications**: allowed (the persistent "Forwarding is active" notification keeps the service alive).

Also open recent apps, long-press the SMS to Telegram card and tap the **padlock**.

Tip: the phone sits on a charger all the time. If battery settings offer a charge
limit (e.g. 80%), turn it on.

## How it works

### Delivery
1. An SMS arrives → it is saved to the `messages` table first, then the sender is woken up.
2. The sender takes unsent messages oldest first and sends each one. If a send fails,
   it retries after **1, 2, 4, 8, 16 s** (2^(n-1)). A new SMS or "network is back" cuts a pause short.
3. If all 6 attempts fail:
   - **no connection / Telegram 429 or 5xx** → the message simply waits, with no limit,
     and is sent as soon as the connection is back;
   - **Telegram rejected it (4xx)** → `tries += 1`. After 5 such rounds the message is given up.
4. The message is sent as Telegram HTML; if Telegram rejects the markup (HTTP 400), the same
   text is resent as plain text, so formatting can never block delivery.

Delivery is **at least once**: if Telegram accepted a message but its answer was lost (timeout),
the message is sent again. A rare duplicate is preferred over a lost SMS. Only one delivery pass
runs at a time, even while the service is being restarted, so a message is never sent twice in parallel.

Sending is triggered immediately by: a new SMS, Android reporting that a network is available,
the chat being linked, a test message, a mode change. Two timers cover everything else.

### Performance modes
| Mode | Telegram reachability check | Resend of unsent messages |
|---|---|---|
| energy | every 240 s | every 240 s |
| **balanced** (default) | every 120 s | every 60 s |
| performance | every 30 s | every 15 s |

The mode only changes these background timers, to save battery and traffic. New SMS are always
sent immediately. Switch it with the slider in the app or with the bot commands.
The reachability check is a tiny HEAD request to `api.telegram.org`.

### Bot polling
`getUpdates` with long polling (`timeout=30`): about one request per 30 s while idle,
yet commands are answered immediately.

### Logs
Shown on the **Logs** tab (newest first). **Clear** (with confirmation) deletes all entries and leaves one "Logs cleared" entry. The `logs` table keeps the last 30 days
(also mirrored to logcat, tag `SmsTg`):
Short entries, e.g. `SMS #12 sent (retry 2)`, `Offline: no internet`, `Online again after 3m 5s`.
- **INFO**: message sent, back online (with the outage duration), mode change, service start/stop;
- **WARNING**: offline (with a short reason: no internet, timeout, network lost, ...);
- **ERROR**: all attempts of a round failed. Individual retries are not logged.

Connection state is tracked in one place (`ConnectionMonitor`). It is fed by everything that talks
to Telegram (sending, bot polling, the reachability check) and by Android's "network lost" event,
and only state changes are logged.

### Database (`smstg.db`)
- `settings`: one row with `bot_token`, `chat_id`, `is_enabled`, `update_offset`, `perf_mode`.
- `messages`: `id`, `sender`, `text`, `target` (SIM1/SIM2/UNKNOWN/TEST), `sms_time`,
  `is_sent`, `tries`, `created_at`, `updated_at`.
- `logs`: `id`, `created_at`, `level`, `text`.

Times are epoch milliseconds. Settings from versions up to 1.4 are moved into the database on first start.

### Code layout (`app/src/main/java/ua/home/smstg`)
| Package | Responsibility |
|---|---|
| `model/` | Shared types: `IncomingSms` DTO, `StoredMessage`, `LogEntry`, `Settings`, `PerfMode` |
| `sms/` | Receives SMS and hands them out as `IncomingSms` through `SmsHandler`. Knows nothing else |
| `data/` | SQLite schema and repositories; the only place with SQL |
| `telegram/` | Bot API client and message formatting |
| `network/` | Reachability check and Android network-available callback |
| `core/` | Delivery with retries, SMS inbox, bot commands |
| `service/` | Foreground service: threads and timers only; boot receiver |
| `ui/` | Main screen: `StatusTab` and `LogsTab` render `res/layout/*.xml`; `MainActivity` handles actions |

The UI uses only the Android platform (no libraries): XML layouts and shape drawables,
Material You dynamic colors on Android 12+ (`res/values-v31`), a fallback palette for older
versions, and an automatic dark theme (`res/values-night*`).

**Languages:** English and Ukrainian, switched with EN / UK in the header (saved in `settings.language`;
by default the system language is used). All texts are in `res/values/strings.xml` (English) and
`res/values-uk/strings.xml`; a missing translation falls back to English. Bot replies and the notification
follow the app language too. Logs are always English: they are stored as text and used for diagnostics.
To add a language, add `res/values-xx/strings.xml` and an entry in `AppLanguage`.
| `App.kt` | Composition root: creates and wires everything |

### Other
- Two receivers listen for `SMS_RECEIVED` (manifest + one registered by the service), because some
  firmwares block one of them; duplicates are dropped. Multi-part SMS are joined.
- The service holds a wake lock and a Wi-Fi lock so networking does not sleep while the screen is locked.
- The bot binds to the first private chat that sends `/start`; all other chats are ignored.
- **Stop** stops the service; new SMS are neither stored nor forwarded.
- After a reboot the service starts by itself (unless it was stopped with the button).

## Troubleshooting
The **Status** tab shows the state, since when Telegram is online/offline, bot polling,
waiting/given-up messages, and the last received and last sent SMS. Details are on the **Logs** tab.

- **"Last SMS received: none yet" after sending an SMS** → the app never received it:
  - Check MIUI "Other permissions" and Autostart (section 5).
  - Make sure it was a real SMS, not an RCS / "Chat" message (Google Messages and
    Samsung Messages send chat messages over the internet when both phones support it).
    Ask the operator for a balance SMS, or disable chat features on the sender.
- **HTTP 401/404** → wrong token.
- **HTTP 409** → the bot has a webhook or another program reads its updates. Open
  `https://api.telegram.org/bot<TOKEN>/deleteWebhook` and make sure the bot runs nowhere else.
- **Change the bot or re-link the chat** → press **Remove token and linked chat…** and confirm.
  This stops forwarding and deletes the token, the chat binding and unsent messages.
  The saved token and chat ID are only ever shown masked (last 3 characters).
- **Logs:** `adb logcat -s SmsTg`.
