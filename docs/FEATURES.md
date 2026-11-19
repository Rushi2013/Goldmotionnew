# Feature coverage — OpenWA 0.30.3 (204 operations / 164 paths)

The app's API layer (`OpenWaApi.kt`) exposes **204 typed suspend functions**, one per operation in
the OpenWA OpenAPI document. `DevToolsScreen` additionally exposes a raw `method + path + body`
console, so no server capability is unreachable even when a route is admin-scope only.

Legend for **Surface**: `typed screen` = dedicated UI, `Dev Tools` = raw console, `engine` =
used internally by auto-reply/bulk.

## Auth & keys
| Path | Surface |
|---|---|
| `POST /auth/validate` | Settings → Test connection |
| `GET/POST /auth/api-keys`, `GET/PUT/DELETE /auth/api-keys/{id}`, `POST /auth/api-keys/{id}/revoke` | Dev Tools (admin) |

## Health & sessions
| Path | Surface |
|---|---|
| `GET /health`, `/health/ready`, `/health/live` | Settings, Sessions |
| `GET/POST /sessions`, `GET/DELETE /sessions/{id}` | Sessions |
| `POST /sessions/{id}/start`, `/stop` | Sessions |
| `GET /sessions/{id}/qr` | Sessions (QR image) |
| `POST /sessions/{id}/pairing-code` | Sessions (phone pairing) |
| `GET /sessions/{id}/me`, `/status`, `/stats` | Sessions, Stats |

## Chats & messages
| Path | Surface |
|---|---|
| `GET /sessions/{id}/chats`, `GET /sessions/{id}/chats/{chatId}` | Chats |
| `GET /sessions/{id}/chats/{chatId}/messages`, `/history` | Thread |
| `GET /sessions/{id}/messages` (filters: direction, type, orderBy, before/after) | Thread, Search |
| `POST /sessions/{id}/messages/send-text` | Thread, Home (voice), auto-reply engine |
| `POST /sessions/{id}/messages/send-reply` | Thread, Approvals |
| `POST …/send-image`, `send-video`, `send-audio`, `send-document`, `send-sticker`, `send-location`, `send-contact`, `send-poll`, `send-template`, `send-product` | Media Send |
| `POST …/messages/{messageId}/react`, `/star`, `/pin` | Thread |
| `POST …/messages/{messageId}/edit`, `/delete`, `/forward` | Thread |
| `POST …/messages/send-bulk` | Bulk |
| `GET …/messages/batches`, `/batches/{batchId}` | Dev Tools |
| `GET …/messages/{id}/media`, `/download-media` | Thread |
| `POST …/chats/{chatId}/read`, `/typing`, `/archive`, `/pin`, `/mute`, `/unmute`, `/clear` | Thread / Chats |
| `POST …/chats/send-seen`, `POST …/sessions/{id}/read-all` | Chats |

## Contacts & directory
| Path | Surface |
|---|---|
| `GET /sessions/{id}/contacts`, `/contacts/{contactId}` | Contacts |
| `POST /contacts/check/{number}`, `GET /contacts/check/{number}` | Number Checker |
| `POST …/contacts/block`, `/unblock` | Contacts |
| `GET /sessions/{id}/profile`, `/profile/{contactId}` | Dev Tools |
| `GET /sessions/{id}/chats/{chatId}/picture` | Dev Tools |
| `GET /sessions/{id}/search` | Search |
| `GET /sessions/{id}/chats/{chatId}/presence` | Dev Tools |

## Status / stories
| Path | Surface |
|---|---|
| `GET /sessions/{id}/status`, `POST …/status/send-text`, `POST …/status/send-image`, `DELETE …/status/{statusId}` | Status |
| `GET …/status/{statusId}` | Status |

## Groups
| Path | Surface |
|---|---|
| `GET /sessions/{id}/groups`, `POST …/groups` | Groups |
| `GET …/groups/{groupId}`, `PUT …/groups/{groupId}` | Groups (info / subject) |
| `POST …/groups/join`, `/leave`, `/add-participants`, `/remove-participants`, `/promote`, `/demote` | Groups |
| `GET/POST …/groups/{groupId}/invite-code`, `POST …/invite-code/revoke` | Groups |
| `GET …/groups/{groupId}/settings`, `PUT …/settings` (announce/locked) | Groups |
| `GET …/groups/{groupId}/participants` | Groups |
| `POST …/groups/{groupId}/picture` | Dev Tools |

## Channels (newsletters)
| Path | Surface |
|---|---|
| `GET /sessions/{id}/channels`, `POST …/channels` | Channels |
| `GET …/channels/{channelId}` | Channel detail |
| `POST …/channels/{channelId}/subscribe`, `/unsubscribe` | Channels |
| `GET …/channels/{channelId}/messages` | Channel detail |
| `POST …/channels/{channelId}/messages/send` | Channel detail |
| `POST …/channels/{channelId}/mute`, `/unmute` | Channels |

## Labels, templates, catalog
| Path | Surface |
|---|---|
| `GET/POST /sessions/{id}/labels`, `DELETE …/labels/{labelId}` | Labels |
| `POST …/chats/{chatId}/labels/{labelId}`, `DELETE` | Chats |
| `GET/POST /sessions/{id}/templates`, `DELETE …/templates/{templateId}` | Templates |
| `GET /sessions/{id}/catalog`, `GET …/catalog/products`, `GET …/products/{productId}` | Catalog |

## Webhooks, rules, prompts
| Path | Surface |
|---|---|
| `GET/POST /sessions/{id}/webhooks`, `DELETE/PUT …/{webhookId}` | Webhooks |
| `POST …/webhooks/{webhookId}/test`, `GET …/webhooks/delivery-failures` | Webhooks |
| `GET/POST /sessions/{id}/automation-rules`, `PUT/DELETE …/{ruleId}` | Rules |
| `GET/POST/PUT/DELETE /sessions/{id}/prompts` | Dev Tools |

## Realtime & reporting
| Path | Surface |
|---|---|
| Socket.IO `/events` (auth via key) | Dev Tools → Realtime, engine |
| `GET /stats/overview`, `GET /sessions/{id}/stats`, `GET /stats/messages` | Stats |
| `GET /metrics` | Dev Tools (admin) |
| `GET /audit`, `GET /audit/summary` | Dev Tools (admin) |

## Infrastructure & plugins (admin)
| Path | Surface |
|---|---|
| `GET/PATCH /infra/config`, `GET /infra/engines`, `GET /infra/engines/current` | Dev Tools |
| `POST /infra/engines/switch`, `POST /infra/restart` | Dev Tools |
| `GET /infra/status`, `/infra/health`, `/infra/update-check` | Dev Tools, Settings badge |
| `GET /infra/export-data`, `POST /infra/import-data` | Dev Tools |
| `GET /infra/storage`, `POST /infra/storage/prune` | Dev Tools |
| `GET /plugins`, `POST /plugins/{id}/install`, `/enable`, `/disable` | Dev Tools |
| `GET/POST /plugins/instances`, `DELETE /plugins/instances/{id}` | Dev Tools |
| `ALL /ingress/{path}` | Dev Tools |

## App-only features (not in the OpenWA API)

| Feature | Where | Implementation |
|---|---|---|
| Voice commands (Hinglish / English / Devanagari) | Home mic | `VoiceManager` + `IntentParser` (34 intents) |
| Safe calculator | Home | recursive-descent parser, no `eval` |
| Contact resolution | everywhere | exact → Jaro-Winkler ≥ 0.92, unique-match only |
| Auto-reply policy | Auto | 20 categories / 4 groups, 7 auto-send |
| Safety lint | engine | blocks money, promises, dates, URLs, phones, OTP, Aadhaar |
| Loop protection & rate limits | engine | 4-identical / 3-in-90 s → 6 h, 60 s cooldown, daily caps |
| Quiet hours | engine | wrap-around window (22→07) |
| Approvals queue | Approvals | Room-backed, 24 h TTL, quoted replies |
| Auto-reply Lab | More | full pipeline dry-run with exact payload |
| Intent Test | More | 20 fixture sentences from §10.3 |
| Bulk import / execute | Bulk | paste, CSV, templating, dry-run, CSV report |
| Room persistence | all | 14 entities, 12 DAOs, offline reads |
| Call log | Dev Tools | every request: method, path, status, latency |
| DEMO mode | global | nothing is ever sent |

## Known server-side limitations (honest handling, not hidden)

- `/catalog*`, some `/channels*` and `/products*` return **501** on `whatsapp-web.js`. The UI shows
  "Ye feature is engine par available nahi hai (501)".
- Admin routes return **403** for an operator key. Dev Tools shows the exact error.
- `/status*` support depends on the engine version; deletions of others' statuses are server-side
  forbidden.
