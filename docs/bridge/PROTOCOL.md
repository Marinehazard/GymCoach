# GymCoach home-bridge protocol v1 (reduced protocol)

Normative for the phone side (`app/.../network/bridge/BridgeBackend.kt`, WP-06) and for the PC-side
bridge (a separate, later deliverable). Source of truth: SPEC §6.13, R1 §2.2 and §3.12. Status:
**experimental, off by default**, selectable only behind Settings ▸ "Show experimental".

## 1. Policy conditions (printed on the Backend settings screen)

The bridge runs Anthropic's **unmodified** `claude` CLI on Johannes' own PC, signed in with his own
Claude subscription. Conditions (R1 §2.2): unmodified binary; own account only; reachable only over
the user's VPN (Tailscale), never the public internet; no `--bare`; no resale or sharing; low,
personal volume. Anthropic may restrict subscription use by third-party apps **without prior
notice**; the mode can stop working at any time. The phone never handles a claude.ai login or a
Claude Code OAuth token (R1 §1.4) — only the bridge secret below.

## 2. Transport

| Item | Value |
|---|---|
| Endpoint | `POST {bridgeUrl}/v1/messages` — `bridgeUrl` = `AppSettings.bridgeUrl`, e.g. `http://gym-pc.tailnet-name.ts.net:8787` (trailing `/` ignored) |
| Auth | `Authorization: Bearer <bridge secret>`; the secret is a random string the user copies from the bridge's console into the app; stored on the phone with the same Keystore cipher as the API key (`ApiKeyStore.storeBridgeSecret`, DataStore `secrets`, excluded from backup) |
| Request headers | `content-type: application/json`; `accept: text/event-stream` (streaming) or `application/json` (non-streaming) |
| Request body | the **same Messages-API JSON** the direct backend would send (`model`, `max_tokens`, `stream`, `system`, `tools`, `tool_choice`, `thinking`, `output_config`, `cache_control`, `metadata`, `messages`), with the reductions of §3 |
| Streaming response | `200`, `content-type: text/event-stream`, Messages-API SSE events (`event: <type>` + `data: <json>`), byte-compatible with `api.anthropic.com` |
| Non-streaming response (`"stream": false`; compaction and the Test button) | `200`, one Messages-API `Message` JSON object |
| Optional response header | `request-id` (any opaque id; shown on the Backend screen and stored on the message) |

The phone sends no `x-api-key` and no `anthropic-version` to the bridge. The bridge must never log
the secret, the request body's `metadata`, or the transcript beyond its own `claude` session files.

## 3. Reduced semantics (v1)

The bridge cannot execute phone-side tools and cannot receive `tool_result` echoes, so v1 runs a
reduced protocol:

1. **Tools.** In HOME_BRIDGE mode `RequestBuilder` declares only the 16 `propose_*` tools (the four
   read-only lookup tools are omitted) and `CoachTurnService` never auto-follows. The bridge turns the
   declared tools into one `--json-schema`: the union of the 16 proposal inputs plus a `text` field.
2. **tool_result blocks are dropped** by the phone (`BridgeBackend.reduceForBridge`): every
   `{"type":"tool_result",…}` block is removed from user messages, and a user message left without
   content is removed. Proposal outcomes reach the model only through the `<delta>` text block
   (`proposals.applied / rejected / pending / invalid`, with the diff or the validation errors as text).
3. **Only the last user message is prompted.** The bridge maps the text blocks of the **last** user
   message onto the `-p` prompt (joined with a blank line). Earlier messages are not re-sent to
   `claude`; continuity comes from `--resume <session_id>`.
4. **Conversation key.** The bridge keys its stored `session_id` by
   `SHA-256(canonical JSON of messages[0])` — the first user message (the `<context>` snapshot) is
   immutable for the lifetime of a phone conversation, so the key needs no extra field. A request
   whose key is unknown starts a new `claude` session; a compaction or a model/effort change starts a
   new phone conversation and therefore a new key.
5. **System prompt.** `system[0].text` is passed as `--system-prompt` on the first turn of a session.
6. **At most one tool_use per turn.** The bridge synthesises at most one `tool_use` block from
   `structured_output` (see §4); `text` in the structured output becomes a text block.

Invocation (re-check the flags when the PC side is built, R1 §4.7):

```
claude -p "<last user text>" --output-format stream-json --verbose --include-partial-messages \
       --system-prompt "<system text>" --permission-mode dontAsk --json-schema '<union schema>' \
       [--resume <session_id>]
```

## 4. Stream mapping (bridge side)

| `claude` stream-json line | SSE event sent to the phone |
|---|---|
| `{"type":"stream_event","event":{…}}` | the inner `event` object verbatim: `event: <event.type>` / `data: <event JSON>` |
| `structured_output` with a proposal | a synthesised `content_block_start` (`{"type":"tool_use","id":"toolu_bridge_<n>","name":"propose_…","input":{}}`), one `content_block_delta` (`input_json_delta` with the full input JSON) and `content_block_stop` |
| final `{"type":"result",…}` | `message_delta` (`stop_reason`: `tool_use` when a block was synthesised, else `end_turn`; `usage` from the result when present) followed by `message_stop` |
| `system` / init lines | not forwarded (the bridge stores `session_id` from them) |

Events are dispatched on the payload's `type`, never on the SSE event name (SPEC §6.3), so the
phone parses bridge and API streams with the same `SseParser` / `StreamAccumulator`. Pings
(`event: ping`) may be sent at any time and are ignored.

## 5. Errors

Error body (non-2xx, or as a mid-stream SSE `error` event):

```json
{"type":"error","error":{"type":"bridge_unavailable","message":"login expired"}}
```

| Situation | Bridge answer | Phone maps to |
|---|---|---|
| PC off / bridge not reachable (connection refused, timeout before any output) | — | `BackendError.BridgeUnavailable("PC offline or unreachable")` |
| wrong or missing secret | `401` | `BridgeUnavailable("bridge secret wrong")` |
| `claude` not logged in / login expired / usage limit reached / disabled by policy / bridge paused | `503` with `error.type = "bridge_unavailable"` and a short message (`"login expired"`, `"usage limit"`, `"disabled by policy"`, `"bridge disabled"`) | `BridgeUnavailable(<message>)` |
| any status with `error.type = "bridge_unavailable"` | — | `BridgeUnavailable(<message>)` |
| mid-stream `event: error` with `error.type = "bridge_unavailable"` | — | `BridgeUnavailable(<message>)` (thrown) |
| other mid-stream `event: error` (e.g. `overloaded_error`) | forwarded verbatim | handled by `StreamAccumulator` like the direct API |
| other HTTP statuses | Messages-API error JSON | same mapping as the direct API (`HttpErrorMapper`, SPEC §6.10) |
| connection lost after output started | — | `Transport(partialOutput = true)`; partial text is shown as "interrupted" and not stored |
| bridge URL or secret not configured on the phone | — | `BackendError.NotConfigured` |

The phone never retries a bridge request automatically (SPEC §6.10).

## 6. Test button

Settings ▸ Backend ▸ "Test connection" sends `RequestBuilder.buildTest(settings)` (non-streamed,
`max_tokens: 32`, no tools, one user message `"ping"`) through `ClaudeBackend.test()`. The bridge
answers with a `Message` JSON (any short text) or an error per §5. The phone shows the mapped error
and the `request-id` (never the secret).

## 7. Versioning

This document describes protocol **v1**. A later bridge that can run tools must keep accepting v1
bodies; extensions are negotiated by a new path (`/v2/messages`), never by changing v1 semantics.
