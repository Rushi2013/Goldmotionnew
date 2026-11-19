# Connecting JARVIS AI to your OpenWA server

JARVIS AI is a **client**. The WhatsApp engine (Baileys / whatsapp-web.js) runs on your machine or
server through [OpenWA](https://github.com/rmyndharis/OpenWA). This page walks through the setup.

---

## 1. Run OpenWA

```bash
docker run -d --name openwa \
  -p 2785:2785 \
  -v openwa-data:/app/data \
  -e API_KEY=your-operator-key \
  rmyndharis/openwa:0.30.3
```

Or from source:

```bash
git clone https://github.com/rmyndharis/OpenWA
cd OpenWA && pnpm install && pnpm build
API_KEY=your-operator-key pnpm start
```

Health check: `curl -H "X-API-Key: your-operator-key" http://localhost:2785/api/health/ready`

Base URL for the app: **`http://<host>:2785/api`** — note the `/api` suffix.

> From an Android **emulator** on the same machine, `localhost` is the emulator itself. Use
> `http://10.0.2.2:2785/api` (this is the app's default).
> From a **physical phone**, use the computer's LAN IP, e.g. `http://192.168.1.10:2785/api`, and
> make sure both are on the same Wi-Fi and the port is not blocked by a firewall.

---

## 2. Create an operator API key

Dashboard → **API Keys** → create a key with role **operator** and scopes:

```
sessions:read sessions:write chats:read messages:read messages:write
contacts:read contacts:write groups:read groups:write status:read status:write
webhooks:read webhooks:write rules:read rules:write
```

Admin-only areas (`/auth/api-keys`, `/infra/*`, `/plugins/*`, `/metrics`, `/audit`, `/ingress/*`)
require an **admin** key. The app handles `403` on those routes honestly: Dev Tools shows the exact
error instead of pretending the feature does not exist. If you want them in-app, create an admin key
and paste it in Settings → API key.

Never use a key on a server reachable from the public internet without TLS in front of it.

---

## 3. Start a WhatsApp session

In the app: **More → Session / QR → Start session** with a name (`jarvis`).

- The first start takes 10–60 s while the engine boots.
- When the status becomes `qr_ready`, the QR image appears. Scan it:
  WhatsApp → Settings → **Linked devices** → **Link a device**.
- Status moves `qr_ready → authenticating → ready`.
- If you prefer: enter your phone number (E.164 digits, e.g. `919812345678`) → **Pairing code** →
  enter the 8 characters in WhatsApp on your phone.

The QR shown by the gateway encodes a `wa.me` link valid for about 20 s per refresh — the app
refreshes the image automatically. Only scan a QR from **your own** OpenWA instance; a QR from a
stranger is a scam that hands over your WhatsApp account.

---

## 4. Go LIVE in the app

**More → Settings → Connection**

| Field | Value |
|---|---|
| Base URL | `http://<host>:2785/api` |
| API key | your operator key |
| Session ID | the id of the session you started (`jarvis` or the UUID the server returned) |
| Mode | **LIVE** |

Press **Test connection**. Expected result: *Key valid · session ready*. The Home screen chip turns
green.

Use **DEMO** whenever you want to explore the UI without touching WhatsApp. In DEMO:
sends are simulated, auto-reply never fires, and bulk only does a dry run.

---

## 5. Turn on the auto-reply engine

1. **Settings → AI** → choose a provider (or leave **Rules** for a zero-cost, deterministic reply).
   - OpenAI-compatible: base URL `https://api.openai.com/v1`, model `gpt-4o-mini`, your key.
   - Gemini: model `gemini-2.0-flash`, your key.
   - **Verify** must report OK.
2. **Auto** tab → set mode `ALL` (everyone) or `ALLOWLIST` (only the contacts you add).
3. Start the foreground service with the Home screen toggle. The notification stays visible while
   the engine runs — that notification is the platform requirement, do not swipe it away.
4. Send yourself a WhatsApp from another phone: *"Hi"*. Expect a greeting reply in ~2 s, and the
   decision to appear in **Auto → Live decision log**.
5. Send *"please send me 5000 rupees"*: this must **not** be auto-answered. It appears in
   **Approvals** with suggestions.

### What never gets auto-answered

OTP / bank / security texts and spam are silenced. Money, business/work, legal, family conflict,
complaints and anything with a link are escalated to you.

### Limits worth knowing

- Quiet hours default 22:00–07:00 — replies are held, not queued forever.
- 60 s per-chat cooldown, 25 replies/chat/day, 60/hour total.
- Loop breaker: 4 identical replies or 3 sends in 90 s → 6 h block for that chat.
- If the foreground service dies (task killer, battery saver), auto-reply stops. Android
  Settings → Apps → JARVIS AI → Battery → **Unrestricted**.

---

## 6. Bulk sending

1. **Bulk** tab → paste numbers or import a CSV (`number,name` header is auto-detected).
2. Write the message; `{{name}}` is substituted per recipient.
3. Set the delay (minimum 1 s, default 3 s). Leave the randomiser on.
4. Tick the consent box (you have permission from these people) and press **SEND BULK**.
5. Progress is real: `sent/failed/pending`, current recipient, and a **CSV report** when finished.

Hard limits: 100 recipients per server request, 300/hour, 1000/day. Bulk sending to people who
did not opt in is the fastest way to get a number banned — that is why the warnings exist.

---

## 7. Realtime events

**Dev Tools → Realtime** connects to `/events` over Socket.IO with your API key. Incoming messages
appear live. If your network blocks WebSocket, the app falls back to polling — the auto-reply
engine then relies on the notification listener + periodic chat polling.

---

## 8. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `Connection failed` | Wrong host/port, server down, or phone on a different network. Test `/health/ready` in a browser first. |
| `API key rejected (401)` | Key revoked or mistyped. Create a new one. |
| `Scope denied (403)` | Role is operator but the route needs admin. Correct behaviour, not a bug. |
| `Engine not ready (409)` | The session is not `ready`. Start the session and scan the QR. |
| `Not supported on this engine (501)` | e.g. catalog on whatsapp-web.js. Switch engine in Infra or accept the limitation. |
| QR scanned but status never becomes `ready` | Delete the session, start a fresh one, scan again. Stale auth folders are the usual cause. |
| Auto-reply never fires | Check the foreground notification is present, mode is not `OFF`, quiet hours, and the decision log for `SILENCE`/`ASK_USER` reasons. |
