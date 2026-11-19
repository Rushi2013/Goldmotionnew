# JARVIS AI — OpenWA control app for Android

A complete Android client for the **OpenWA** WhatsApp gateway (v0.30.3), plus an AI auto-reply
engine, an approval workflow, voice control (Hinglish/English/Devanagari), and a bulk sender.

The APK is built by **GitHub Actions** on every push.

---

## 1. Get the APK

1. Open the **Actions** tab of this repository.
2. Pick the latest **Build JARVIS AI APK** run (green tick).
3. Scroll to **Artifacts** → download `jarvis-ai-debug-apk` (works on every device) or
   `jarvis-ai-release-apk` (minified; signed with the debug key unless you add signing secrets).
4. On builds from `main`, the same APKs are also attached to the
   [latest-apk](../../releases/tag/latest-apk) release, so you can download without logging in.

Install with "Install unknown apps" allowed. Minimum Android 10 (API 29), target Android 15.

### Optional: sign with your own key

Add four repository secrets — the workflow picks them up automatically:

| Secret | Value |
|---|---|
| `JARVIS_STORE_FILE_BASE64` | `base64 -w0 your-keystore.jks` |
| `JARVIS_STORE_PASSWORD` | keystore password |
| `JARVIS_KEY_ALIAS` | key alias |
| `JARVIS_KEY_PASSWORD` | key password |

Without them the release APK is signed with the standard Android debug key, which still installs.

---

## 2. First run

The app starts in **DEMO mode** — nothing is ever sent to WhatsApp.

1. **Onboarding** → enter your OpenWA base URL (e.g. `http://192.168.1.10:2785/api`) and an
   operator API key. *Test connection* validates the key with `GET /health/ready` + `/auth/validate`.
2. **Sessions** → *Start session* → scan the QR (or request a pairing code with your phone number).
3. **Settings → Switch to LIVE**. The green chip on the Home screen must read `ready`.
4. Optional: **Settings → AI** → pick OpenAI-compatible / Gemini / Rules-only, paste a key,
   *Verify*.

> Default URL `http://10.0.2.2:2785/api` points at the host machine from an Android emulator.

---

## 3. What is in the app (A → Z of OpenWA)

Every one of the **204 operations across 164 paths** of the 0.30.3 OpenAPI document is reachable.
Typed screens cover the common flows; **Dev Tools → API Console** can call literally any
`method + path` with a custom JSON body, so admin-only routes (audit, API keys, infra, plugins,
metrics, ingress proxy) are never blocked by the UI.

| Area | Screen | Notes |
|---|---|---|
| Health, sessions, QR, pairing code | Sessions | create / start / stop / delete, QR image, live status |
| Chats + message thread | Chats, Thread | text, reply, react, edit, delete, star, pin, forward, history, media download |
| All 10 media types | Media Send | image, video, audio (PTT), document, sticker, location, contact, poll, template, product |
| Status / stories | Status | list, post text, post image, delete |
| Groups | Groups | create, join, info, participants, subject, invite code, revoke, leave |
| Channels (newsletters) | Channels | list, subscribe, create, messages |
| Labels | Labels | list, upsert, delete |
| Templates | Templates | list, create, delete |
| Catalog | Catalog | catalog + products (honest 501 handling for engines without it) |
| Contacts | Contacts | list, block, unblock, check number |
| Global search | Search | server search + snippets |
| Webhooks | Webhooks | list, create, test, delete, delivery failures |
| Automation rules (server side) | Rules | list, create, update, delete |
| Statistics | Stats | overview, per-session, message stats — real JSON |
| Infra / update-check | Dev Tools | engine list, restart, update check, export/import |
| Raw API | Dev Tools | any method/path/body, response headers, timing, call log |
| Realtime | Dev Tools | Socket.IO connect/disconnect, event log |

### AI assistant (§ voice + chat)

- Home has a text box and a mic. Examples that work:
  - `Pramila ko hi bhej` → resolves the contact, **asks for confirmation**, then sends.
  - `auto reply on` / `auto reply off` / `auto reply sirf Pramila ke liye on`
  - `sabko good morning bhej do` → opens the bulk composer pre-filled (never auto-fires)
  - `kal 6 baje meeting yaad dilana` → reminder row
  - `1250 ka 18% kitna hai` → safe calculator (no `eval`)
  - Devanagari commands are normalised to Roman before matching.
- Anything that sends to a human is `requiresConfirmation` and shows a Confirm & send card.
- Resolution uses exact match → Jaro-Winkler ≥ 0.92 and only auto-selects when the match is unique.

### Auto-reply engine

- 20 reply categories in 4 policy groups: 7 AUTO, 3 opt-in, 8 always-ask, 2 silent
  (OTP/security and spam never get a reply).
- `SafetyLint` blocks money, promises, dates, URLs, phone numbers, OTPs and Aadhaar numbers from
  any auto-sent text.
- Loop breaker (≥4 identical or 3 in 90 s → 6 h block), 60 s per-chat cooldown, daily/hourly caps,
  quiet hours.
- Every inbound is deduplicated by `sessionId|messageId|hash(text)` and written to `autopilot_log`
  before any network call, so a crash cannot double-send.
- Office/paise/legal/urgent chats are **escalated**, never auto-answered → **Approvals** tab,
  where suggestions can be edited and sent (quoted).
- **Auto-reply Lab** runs a message through the real classifier → policy → draft → lint pipeline
  and shows the exact request payload — without sending anything.

### Bulk sender

- Paste, CSV/TXT (RFC 4180, header auto-detect, BOM, `;` separators), or manual entry.
- `{{name}}`, `{{number}}`, `{{date}}`, `{{var}}` templating, preview, dry-run, CSV report.
- ≤100 recipients per `send-bulk` request, 1–60 s delay with jitter, 429 honours `Retry-After`,
  5 consecutive failures pause the batch.
- Consent checkbox + ban-risk banner are mandatory.

---

## 4. Build locally (optional)

```bash
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest  # 5 unit-test classes
```

Requires JDK 17 and Android SDK 35. No `local.properties` is needed when `ANDROID_HOME` is set.

---

## 5. Project layout

```
app/src/main/java/ai/jarvis/
├── core/             models, ApiResult/ApiError, phone normaliser, formatting
├── data/
│   ├── api/          HttpCore, OpenWaApi (204 ops), DTOs, SessionManager, Socket.IO
│   ├── db/           Room: 14 entities, 12 DAOs
│   ├── contacts/     recipient resolver (Jaro-Winkler + transliteration)
│   └── SettingsStore.kt   EncryptedSharedPreferences + StateFlow
├── ai/               LLM providers (OpenAI-compatible, Gemini), classifier, prompts
├── domain/
│   ├── policy/       20 categories, policy engine, safety lint, rule classifier
│   └── intents/      intent parser (34 intents) + safe calculator
├── autoreply/        engine, loop breaker, rate limiter, quiet hours
├── whatsapp/bulk/     CSV/paste parser, batch executor
├── service/          foreground service, notifier, boot receiver, notification listener
├── ui/               theme (AMOLED/cyan), components, 29 routes, 27 screens
└── voice/            speech recognition + TTS
```

## 6. Honest limits

- Auto-reply runs **only while the app's foreground service is alive**. If Android kills it
  (battery saver, vendor task killer), replies stop. Whitelist the app for battery optimisation.
- The WhatsApp engine still runs on your server. This app is a control surface.
- Automatic sending to people who did not ask for it can get a WhatsApp number banned — that is
  what the consent checkbox, quiet hours, caps and the ban-risk banner are for.
- Webhook, plugin, infra and API-key routes are admin-scope on the server; with an operator key
  you will correctly get `403`, which the UI explains instead of hiding.
