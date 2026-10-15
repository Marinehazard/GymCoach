# R1 — Claude chat backend for GymCoach

Research date: 2026-09-04. All facts below were checked against live pages on that date unless marked
"NOT VERIFIED". Quotations are verbatim from the fetched page. Prices are USD.

Scope (from the task): (a) may a personal Android app legitimately run its chat on a Claude Max
subscription; (b) rank the realistic backends for THIS app and cost the direct-API option;
(c) pick the Android HTTP client approach and document the exact Messages API wire contract.

---

## 0. Decisions in one screen

| Question | Answer |
|---|---|
| Can the Android app itself use Max/claude.ai OAuth credentials (`/login` token, `claude setup-token`, `CLAUDE_CODE_OAUTH_TOKEN`) to call `api.anthropic.com`? | **No.** Prohibited by the Claude Code legal page ("developers may not collect, store, or intermediate Claude.ai credentials or session tokens") and by the Consumer Terms (automated access is only allowed "via an Anthropic API Key"). See §1.4, §1.5. |
| Is there any official feature that lets a third-party app bill to a Pro/Max subscription? | **No.** The only one ever announced (the "Agent SDK credit", planned for 2026-06-15) was paused on the day it was due. App Attest exists but is iOS/macOS-only and bills the *API workspace*, not a subscription. See §1.8, §1.9. |
| Is a "home bridge" (tiny HTTP server on his PC that runs the unmodified `claude -p` under his own subscription login, reached over Tailscale) within policy? | **Grey zone, currently tolerated, revocable without notice.** Anthropic's help centre states today that "`claude -p`, and third-party app usage still draw from your subscription's usage limits", and the legal page explicitly does not prevent "an end user from signing in to the unmodified Claude Code binary with their own Claude subscription". But the same page says OAuth is for "ordinary use of Claude Code and other native Anthropic applications" and Anthropic "may … enforce these restrictions … without prior notice" — and it did exactly that on 2026-04-04. Treat it as an optional, off-by-default, self-hosted experiment; never as the only path. See §1.10, §2.2. |
| Default backend | **Direct Anthropic API with an API key** from a Claude Console org, key held on-device. Fully in policy; works anywhere the phone has internet. Estimated **≈ $20/month on `claude-opus-5`** (≈ $8 on `claude-sonnet-5`) for the task's 30-turns/day profile, before thinking-token overhead; realistic band $20–45 (Opus) / $8–17 (Sonnet). See §2.4. |
| Android HTTP client | **Raw HTTPS: OkHttp + kotlinx.serialization + a ~60-line SSE parser.** Not the official Java SDK. The SDK (com.anthropic:anthropic-java 2.60.0) is Java-8 compatible and ships R8 keep rules, so it *could* run on Android, but it drags in Jackson databind + kotlin-reflect + a JSON-schema generator (27 MB core jar, 11,770 entries), has zero Android documentation or issues, and its builder API is exactly the kind of surface we cannot compile-check today. See §3. |

---

## 1. Policy research (a)

### 1.1 Sources fetched (primary unless noted)

| # | Page | URL |
|---|---|---|
| S1 | Claude Code → Authentication | https://code.claude.com/docs/en/authentication |
| S2 | Claude Code → Legal and compliance | https://code.claude.com/docs/en/legal-and-compliance |
| S3 | Agent SDK → Overview | https://code.claude.com/docs/en/agent-sdk/overview |
| S4 | Agent SDK → Quickstart | https://code.claude.com/docs/en/agent-sdk/quickstart |
| S5 | Claude Code → Run programmatically (headless) | https://code.claude.com/docs/en/headless |
| S6 | Claude Code → Remote Control | https://code.claude.com/docs/en/remote-control |
| S7 | Claude Code → Manage costs | https://code.claude.com/docs/en/costs |
| S8 | Help Center → Use the Claude Agent SDK with your Claude plan | https://support.claude.com/en/articles/15036540-use-the-claude-agent-sdk-with-your-claude-plan |
| S9 | Help Center → Use Claude Code with your Pro or Max plan | https://support.claude.com/en/articles/11145838-use-claude-code-with-your-pro-or-max-plan |
| S10 | Consumer Terms of Service (effective 2025-10-08) | https://www.anthropic.com/legal/consumer-terms |
| S11 | Usage Policy (effective 2025-09-15) | https://www.anthropic.com/legal/aup |
| S12 | Claude Platform → CLI authentication options (`ant auth login`) | https://platform.claude.com/docs/en/cli-sdks-libraries/cli/authentication |
| S13 | Claude Platform → Authentication (API keys, WIF, App Attest) | https://platform.claude.com/docs/en/manage-claude/authentication |
| S14 | Claude Platform → App Attest | https://platform.claude.com/docs/en/manage-claude/app-attest |
| S15 | claude.com/pricing | https://claude.com/pricing |
| N1 | (secondary) Zed blog, 2026-05-14, updated 2026-06-16 | https://zed.dev/blog/anthropic-subscription-changes |
| N2 | (secondary) VentureBeat, 2026-04-03 | https://venturebeat.com/technology/anthropic-cuts-off-the-ability-to-use-claude-subscriptions-with-openclaw-and |
| N3 | (secondary) VentureBeat, 2026-05-13 | https://venturebeat.com/technology/anthropic-reinstates-openclaw-and-third-party-agent-usage-on-claude-subscriptions-with-a-catch |

### 1.2 Claude Code authentication — what the tokens are and what they may do (S1)

Account types accepted by Claude Code: "**Claude Pro or Max subscription**: log in with your Claude.ai
account." … "**Claude Console**: log in with your Console credentials."

Credential precedence (abridged, S1 "Authentication precedence"): cloud-provider creds →
`ANTHROPIC_AUTH_TOKEN` ("Sent as the `Authorization: Bearer` header") → `ANTHROPIC_API_KEY`
("Sent as the `X-Api-Key` header") → `apiKeyHelper` → `CLAUDE_CODE_OAUTH_TOKEN` → Anthropic
profile / federation → "Subscription OAuth credentials from `/login`. This is the default for Claude
Pro, Max, Team, and Enterprise users."

`claude setup-token` (S1 "Generate a long-lived token"):

> "For CI pipelines, scripts, or other environments where interactive browser login isn't available,
> generate a one-year OAuth token with `claude setup-token`"
>
> "This token authenticates with your Claude subscription and requires a Pro, Max, Team, or
> Enterprise plan. It can only make model requests, so it can't establish Remote Control sessions
> or fetch claude.ai connectors."
>
> "Bare mode does not read `CLAUDE_CODE_OAUTH_TOKEN`. If your script passes `--bare`, authenticate
> with `ANTHROPIC_API_KEY` or an `apiKeyHelper` instead."

Storage: on Windows "credentials are stored in `%USERPROFILE%\.claude\.credentials.json`".
Login lifetime: a `/login` credential expires and must be renewed ("`Your login expires in 3 days ·
run /login to renew`"); "a Remote Control session that outlives the login stops making progress once
the credential expires".

Take-away: `setup-token` / `CLAUDE_CODE_OAUTH_TOKEN` is a Claude-Code credential for running
*Claude Code* unattended. Nothing on S1 describes it as usable outside Claude Code, and S2 (below)
forbids exactly that.

### 1.3 Agent SDK — subscription login is not allowed for third-party products (S3, S4)

S3 (overview), verbatim note:

> "Unless previously approved, Anthropic does not allow third party developers to offer claude.ai
> login or rate limits for their products, including agents built on the Claude Agent SDK. Use the
> API key authentication methods described in the Quickstart instead."

S3 also: "Use of the Claude Agent SDK is governed by Anthropic's Commercial Terms of Service".

S4 (quickstart) repeats the note and its only documented credential is `ANTHROPIC_API_KEY`
("Get an API key from the Claude Console, then set it as an environment variable") plus the cloud
providers.

S3, on how the SDK relates to the CLI: "The SDK is available as a library for Python and TypeScript
only. To drive the same agent loop from another language, run the CLI as a subprocess with the `-p`
flag and `--output-format json`." (No Java/Kotlin/Android SDK exists.)

### 1.4 The controlling text: Claude Code "Legal and compliance" (S2)

This page is the most specific official statement and should be quoted in the app's settings UI.
Under "Authentication and credential use":

> "Claude Code authenticates with Anthropic's servers using OAuth tokens or API keys. These
> authentication methods serve different purposes:"
>
> "**OAuth authentication** is intended exclusively for purchasers of Claude Free, Pro, Max, Team,
> and Enterprise subscription plans and is designed to support ordinary use of Claude Code and other
> native Anthropic applications."
>
> "**Developers** building products or services that interact with Claude's capabilities, including
> those using the Agent SDK, should use API key authentication through Claude Console or a supported
> cloud provider. Anthropic does not permit third-party developers to offer Claude.ai login into
> their own applications, or to route requests through Free, Pro, or Max plan credentials on behalf
> of their users. Moreover, developers may not collect, store, or intermediate Claude.ai credentials
> or session tokens — sign-in to a Claude account must complete through Anthropic's own flow."
>
> "This does not restrict how customers provision and manage their own API keys … Nor does it
> prevent an end user from signing in to the unmodified Claude Code binary with their own Claude
> subscription, including where a platform hosts Claude Code as described under *Can customers offer
> Claude Code in their products?* above."
>
> "Anthropic reserves the right to take measures to enforce these restrictions and may do so without
> prior notice."

Under "Acceptable use": "Advertised usage limits for Pro and Max plans assume ordinary, individual
usage of Claude Code and the Agent SDK."

Under "Can customers offer Claude Code in their products?": "**The Claude Code binary must not be
modified.** … customers may not remove, disable, or restrict any authentication method built into
it" and "Each end user must authenticate with their own Anthropic API key, Claude subscription plan
credentials, or 3P inference provider credential".

Which terms apply: "Consumer Terms of Service - for Free, Pro, and Max users"; "Commercial Terms of
Service - for Team, Enterprise, and Claude API users".

### 1.5 Consumer Terms and Usage Policy (S10, S11)

Consumer Terms (effective 2025-10-08), definition: "Claude.ai, Claude Pro, and other products and
services that we may offer for individuals, along with any associated apps, software, and websites
(together, our 'Services')". Preamble: "Our Commercial Terms of Service govern your use of any
Anthropic API key, the Anthropic Console, or any other Anthropic offerings that reference the
Commercial Terms of Service. For clarity, this does not include Claude.ai or Claude Pro use for
individuals or entities."

Prohibited use, Section 3 (verbatim): "Except when you are accessing our Services via an Anthropic
API Key or where we otherwise explicitly permit it, to access the Services through automated or
non-human means, whether through a bot, script, or otherwise."

Also Section 2: "You may not share your Account login information, Anthropic API key, or Account
credentials with anyone else"; Section 11: "You agree that you will not use our Services for any
commercial or business purposes". (GymCoach is personal use by the subscriber himself, so §11 is
not an obstacle; §3 is the operative clause — an app hitting Anthropic servers with a claude.ai
credential is "automated … means" and is only excused for an *API key*.)

Usage Policy (effective 2025-09-15) has no clause specific to subscription credentials; relevant
generic clauses are the bans on bypassing product restrictions and coordinating across accounts to
"circumvent product guardrails".

### 1.6 `ant auth login` — Console OAuth, i.e. API billing, not a subscription (S12, S13)

S12: "`ant auth login` lets you call the API without creating or managing an API key. It opens a
browser-based OAuth flow against the Claude Console and stores the resulting credentials under
`$ANTHROPIC_CONFIG_DIR`". "During the browser flow, you select an organization and then a workspace.
The issued token is scoped to that workspace". "Interactive login is intended for local development
and scripting on your own machine. For non-interactive workloads such as CI, servers, and
containers, use Workload Identity Federation instead."

So `ant auth login` is an OAuth *profile for a Claude Console (API) organisation*; usage is metered
to that org at API rates. It has nothing to do with a Pro/Max subscription. S1 confirms Claude Code
treats such a profile as "the same kind of credential" as its own keyless Console sign-in.

S13 lists exactly three API authentication methods — API key, Workload Identity Federation, App
Attest — and none is a consumer subscription. Header note: "Send it as `Authorization: Bearer <key>`
on direct HTTP requests" … "The legacy `x-api-key: YOUR_API_KEY` header is still supported in place
of `Authorization`."

### 1.7 Remote Control and the Claude mobile app (S6)

"Remote Control connects claude.ai/code or the Claude app for iOS and Android to a Claude Code
session running on your machine." "**Subscription**: available on Pro, Max, Team, and Enterprise
plans. API keys are not supported." "`claude remote-control`" is the server mode; "You're
authenticated with a long-lived token from `claude setup-token` or the `CLAUDE_CODE_OAUTH_TOKEN`
environment variable. These tokens can only make model requests, so they can't establish Remote
Control sessions."

Remote Control is a *user interface* (Anthropic's own app) onto a Claude Code session; there is no
API for a third-party app to join or drive that session. It is therefore not a backend option for
GymCoach, though it is a legitimate way for Johannes to talk to a Claude Code session on his PC from
his phone (see §2.2, option D).

### 1.8 Timeline 2026 (why the policy reads the way it does)

- **Feb 2026** — Anthropic added explicit language that OAuth tokens from Free/Pro/Max accounts may
  not be used in other products/tools/services (reported by several secondary sources; the current
  official wording is the S2 text quoted in §1.4). NOT VERIFIED verbatim against the Feb-2026 page.
- **2026-04-04 12:00 PT** — Anthropic technically cut Pro/Max subscriptions off from third-party
  agent harnesses (OpenClaw et al.). N2 quotes Boris Cherny (Head of Claude Code): "our
  subscriptions weren't built for the usage patterns of these third-party tools" and "Capacity is a
  resource we manage thoughtfully and we are prioritizing our customers using our products and
  API." Named alternatives: pay-as-you-go "extra usage" and the API.
- **2026-05-13** — reversal: subscribers would get a monthly "Agent SDK credit" from 2026-06-15
  ($20 Pro / $100 Max 5x / $200 Max 20x per N1/N3, billed at API rates) covering "Claude Agent SDK
  usage in your own projects", "`claude -p`", GitHub Actions, and "third-party apps that
  authenticate with your Claude subscription through the Agent SDK" (S8's description of the paused
  plan).
- **2026-06-15** — paused on the day. S8 (help centre, last updated 2026-06-16), verbatim:

  > "Update June 15: We're pausing the changes to Claude Agent SDK usage described below. For now,
  > nothing has changed: Claude Agent SDK, `claude -p`, and third-party app usage still draw from
  > your subscription's usage limits."

  N1 (Zed) reports Anthropic will "share advance notice before any future change takes effect".

Reading: Anthropic *tolerates* a subscriber's own subscription being consumed by Agent-SDK /
`claude -p` based tooling (and even says "third-party app usage" draws from subscription limits),
while *prohibiting* developers from offering claude.ai login, intermediating tokens, or routing on
behalf of other users. The two official texts (S2 vs S8) are in tension; S2 is the normative one,
S8 describes the current billing status quo.

### 1.9 Any official feature that lets a third-party app bill to a subscription?

None today.
- Agent SDK credit: paused (§1.8).
- App Attest (S14): "App Attest authenticates iOS and macOS apps that call the Claude API directly
  from the device, with usage billed to your workspace." Tokens "expire after one hour, and
  authorize only Messages API calls". It requires Apple's `DCAppAttestService`; there is no Android /
  Play Integrity equivalent on the Claude platform. It also bills the *API workspace*, not a
  subscription.
- claude.com/pricing (S15): Claude Code "shares the same usage limits as the rest of your plan";
  after the limit "turn on usage credits to keep working at standard API rates". Usage credits are
  still consumed only through Anthropic's own apps.

### 1.10 Verdict

1. **Direct use of Max credentials by the GymCoach app is not legitimate.** Any design in which the
   phone holds a claude.ai OAuth token (from `/login`, `claude setup-token`, or
   `CLAUDE_CODE_OAUTH_TOKEN`) and calls `api.anthropic.com` is (i) "automated … means" that is only
   excused "via an Anthropic API Key" (S10 §3), (ii) a developer "collect[ing], stor[ing], or
   intermediat[ing] Claude.ai credentials or session tokens" (S2), and (iii) explicitly disallowed
   for products "including agents built on the Claude Agent SDK" (S3). Do not build it, do not offer
   a field for it.
2. **The direct API key path is the only fully compliant, fully supported option.** It is what every
   official page tells developers to use (S2, S3, S4, S13). It bills the Console org at API rates.
3. **A self-hosted home bridge running the unmodified `claude` binary under his own login** is the
   only subscription-funded design that is not expressly prohibited: he is the end user "signing in
   to the unmodified Claude Code binary with their own Claude subscription" (S2), and Anthropic
   currently says "`claude -p`, and third-party app usage still draw from your subscription's usage
   limits" (S8). It is not blessed either: S2 scopes OAuth to "ordinary use of Claude Code and other
   native Anthropic applications" and reserves enforcement "without prior notice"; usage-limit
   assumptions are "ordinary, individual usage". Classification: **tolerated grey zone, revocable**.
   Conditions that keep it as close to the line as possible: unmodified binary; his own account; the
   bridge is reachable only by him (Tailscale ACL + bridge secret); the OAuth credential never leaves
   the PC (the phone talks only to the bridge); no `--bare` (bare mode "never reads OAuth
   credentials"); no resale, no other users; low volume ("ordinary, individual usage").
4. **The settings UI must say this plainly** (draft copy in §2.5).

---

## 2. Backend options for this app (b)

Context: travelling user, phone, no server of his own except a home PC that *may* be online. Chat
must act via structured proposals (tool use). Everything else is offline-first.

### 2.1 Ranking

| Rank | Option | Policy status | Availability while travelling | Cost | Verdict |
|---|---|---|---|---|---|
| 1 | **Direct Anthropic API (API key in Android Keystore-backed storage)** | Compliant (S2, S3, S13) | Whenever the phone has internet | ≈ $20/mo Opus 5, ≈ $8/mo Sonnet 5 at the task profile (§2.4) | **Default. Ship first.** |
| 2 | **Home bridge: PC runs unmodified `claude -p` under his own login; app ⇄ bridge over Tailscale** | Tolerated grey zone, revocable (§1.10) | Only when the PC is on, awake, online and Tailscale is up; extra hop latency | $0 marginal (draws from Max limits) | **Optional, off by default, "experimental — may stop working without notice".** |
| 3 | Home bridge that uses an API key instead of the login | Compliant | Same PC dependency as 2 | Same as 1 | Only useful for key custody / logging; otherwise strictly worse than 1. Not worth building separately — if the bridge exists, let it read `ANTHROPIC_API_KEY` as a mode switch. |
| 4 | Remote Control (Claude Android app ⇄ Claude Code on the PC) | Compliant (Anthropic's own app) | PC dependency | $0 marginal | Not embeddable in GymCoach; a manual escape hatch only (e.g., an MCP server exposing GymCoach data to a Claude Code session). |
| — | `ant auth login` profile | Compliant but API-billed | — | API rates | Not a subscription path; no advantage over an API key for a phone app (short-lived tokens need a refresh loop on the PC). |
| — | Managed Agents / Agent SDK with an API key | Compliant | Needs API key anyway | API rates + session runtime | Overkill: GymCoach's tools run on the phone, so plain Messages API + tool use is the right tier. |
| ✗ | Any variant that puts a claude.ai OAuth token on the phone | Prohibited | — | — | Rejected (§1.10 item 1). |

### 2.2 Notes per option

**Option 1 — direct API.** Needs a Claude Console account (platform.claude.com), separate from
claude.ai; prepaid credits; "New users receive a small amount of free credits" (pricing page). New
orgs start in the Evaluation/Start tier ("New organizations … may start in the Evaluation tier";
Start-tier cap "$500 USD" per month; Opus 5 Start-tier limits 1,000 RPM / 2,000,000 ITPM / 400,000
OTPM — rate-limits page) — irrelevant at GymCoach volumes. Key type: create a *personal key* scoped
to one workspace with an expiration (S13: presets "3 hours, 1 day, 7 days, or 30 days, a custom
duration, or Never"). A single-workspace key needs no `anthropic-workspace-id` header. Rotate by
pasting a new key into the app.

**Option 2 — home bridge (subscription).** Design constraints derived from S1/S2/S5:
- Run the published `claude` binary unmodified; sign in with `/login` (or set
  `CLAUDE_CODE_OAUTH_TOKEN` from `claude setup-token` for a headless PC — that token "can only make
  model requests", which is all the bridge needs). Renew logins (S1: they expire).
- Do **not** use `--bare` (bare mode "never reads OAuth credentials or the system keychain" — S5).
- Invoke per turn: `claude -p "<prompt>" --output-format stream-json --verbose
  --include-partial-messages` and/or `--output-format json`; continue a conversation with `--resume
  <session_id>` (S5: `session_id=$(claude -p "…" --output-format json | jq -r '.session_id')`).
  Text deltas appear as `{"type":"stream_event","event":{...}}` lines whose `event` is the raw
  Messages API stream event (S5's jq filter selects `.event.delta.type == "text_delta"`), so the
  phone can reuse the same SSE-event parser as option 1 (§3.6).
- Replace the coding persona with `--system-prompt` (S5: "`--system-prompt` to fully replace the
  default prompt") and lock tools down with `--permission-mode dontAsk` plus an empty/explicit
  `--allowedTools` list; GymCoach's structured proposals can be obtained with `--output-format json
  --json-schema '<schema>'` (S5: "structured output in the `structured_output` field").
- The bridge listens only on the Tailscale interface, requires its own random bearer secret, and
  never returns or logs the Claude credential. The phone stores only the bridge URL + secret.
- Failure modes to surface in the UI: PC offline; Tailscale down; login expired (S1: "Login expired
  · Please run /login"); Max usage-limit hit ("You've hit your session limit" / weekly limit — S7);
  and *policy enforcement* (Anthropic disabled this class of use once already on 2026-04-04).
- `claude -p` `--output-format json` reports `total_cost_usd` — informational only on a subscription
  (S7: "Claude Max and Pro subscribers have usage included in their subscription, so the session
  cost figure isn't relevant for billing purposes").

**Option 4 — Remote Control.** Useful to know: Johannes can already chat with a Claude Code session
on his PC from the official Claude Android app (subscription-funded, in policy). If GymCoach ever
exposes its database through an MCP server, that gives a zero-cost, fully compliant "talk to my
data" path — but outside the GymCoach UI.

### 2.3 Pricing facts used (Claude Platform → Pricing, fetched 2026-09-04)

| Model | Base input | 5m cache write | 1h cache write | Cache read | Output |
|---|---|---|---|---|---|
| Claude Opus 5 (`claude-opus-5`) | $5 / MTok | $6.25 / MTok | $10 / MTok | $0.50 / MTok | $25 / MTok |
| Claude Sonnet 5 (`claude-sonnet-5`) | $2 / MTok | $2.50 / MTok | $4 / MTok | $0.20 / MTok | $10 / MTok |

Notes from the same page: Sonnet 5's "$2/$10 … is now the standard price. The previously scheduled
increase to $3/$15 … on September 1, 2026 will not occur." Cache read = "0.1x base input price"
(Fable 5.1 is 0.025x, not relevant). Tool-use system-prompt overhead: Opus 5 "286 tokens" with
`tool_choice` `auto`/`none`; Sonnet 5 "354 tokens". Minimum cacheable prefix: "512 tokens for …
Claude Opus 5"; "1,024 tokens for … Claude Sonnet 5" (prompt-caching page). Thinking tokens "are
billed as output tokens" (steering-thinking page). Batch API (50 % off) is not applicable to chat.

### 2.4 Monthly cost estimate — direct API

Task assumptions: 30 turns/day; 3,500-token stable system prompt served from cache (10 % of input
price); 2,000 fresh input tokens/turn; 400 output tokens/turn; 30-day month.

Per-turn arithmetic:

| Component | Opus 5 | Sonnet 5 |
|---|---|---|
| Cached system prompt read: 3,500 × cache-read | 3,500 × $0.50/M = **$0.00175** | 3,500 × $0.20/M = **$0.00070** |
| Fresh input: 2,000 × base input | 2,000 × $5/M = **$0.01000** | 2,000 × $2/M = **$0.00400** |
| Output: 400 × output | 400 × $25/M = **$0.01000** | 400 × $10/M = **$0.00400** |
| **Per turn** | **$0.02175** | **$0.00870** |
| Per day (× 30) | $0.6525 | $0.261 |
| **Per month (× 30 days)** | **$19.58** | **$7.83** |
| (× 31 days) | $20.23 | $8.09 |

Sensitivities that the task's headline number does not include:

| Effect | Opus 5 / month | Sonnet 5 / month | Note |
|---|---|---|---|
| No caching at all (system prompt at base price every turn) | $33.75 | $13.50 | 3,500 × $5/M = $0.0175/turn vs $0.00175 cached. |
| Cache *writes* (5-min TTL), 3 cold starts/day | +$1.97 | +$0.79 | 3,500 × $6.25/M = $0.0219 per write (Opus). If every turn were cold (30 writes/day) the total would be $37.69 — *worse* than not caching; so cache only helps when turns cluster within 5 min. A 1-hour TTL write costs 2× ($0.035 Opus) and wins if ≥ 2 cold starts happen within an hour. |
| Tool schemas (~1,500 tokens) + tool-use system prompt (286/354 tokens), cached | +$0.80 | +$0.33 | Tools render *before* system in the cache prefix, so a breakpoint on the system block covers them. |
| Adaptive thinking at default `effort: high`, assume +1,000 thinking tokens/turn | +$22.50 (→ ≈ $42) | +$9.00 (→ ≈ $17) | Thinking is on by default on Opus 5 and Sonnet 5 and is billed as output. `effort: low` "Skips thinking for simple tasks" — assume +200/turn: +$4.50 Opus (→ ≈ $24), +$1.80 Sonnet (→ ≈ $9.6). |
| Conversation history growth, 10-turn sessions, 3/day, no history caching | +$48.60 | +$19.44 | Turn k re-sends (k−1)×2,400 tokens at base price: Σ over 10 turns = 108,000 tokens = $0.54/session (Opus). With incremental caching (top-level `cache_control`) ≈ $0.20/session (+$18.40/month). **Design consequence:** keep "fresh context" bounded (last N turns + rolling summary + compact JSON of profile/plan/logs), and cache the history. |

Working budget to put in the UI: **Opus 5 ≈ $20–45/month, Sonnet 5 ≈ $8–17/month** at 30 turns/day;
proportionally less on light days. For comparison (S15): Pro "$20 if billed monthly", Max from
"$100 Per month" (the 20x tier was not readable in the fetch — NOT VERIFIED; commonly $200). The API
spend is *additional* to the Max subscription he already pays for — that is the honest trade-off
the settings screen must show.

Recommended default request profile for chat turns (all documented in §3.5): `claude-opus-5`,
`thinking: {"type":"adaptive"}` (display omitted), `output_config: {"effort":"low"}` or `"medium"`
(effort page: low is "Suitable for chat and non-coding use cases"), `max_tokens` 2,048–4,096 (thinking
counts toward `max_tokens`), system prompt + tools cached, top-level `cache_control` for history,
`tool_choice: {"type":"auto"}`. Keep effort constant within a conversation ("Changing the top-level
effort value between requests invalidates prompt caching").

### 2.5 Draft wording for the settings screen (honest and explicit)

> **Coach chat backend**
>
> ○ **Anthropic API key (recommended).** GymCoach calls Anthropic directly with a key from your
> Claude Console account. Billed per token at API rates (typically a few cents per message — see
> Usage). This is the only method Anthropic documents for third-party apps. Your key is stored in
> this device's hardware-backed keystore and never leaves the device except to api.anthropic.com.
>
> ○ **Home bridge (experimental).** GymCoach talks to a small server on your own PC, which runs
> Anthropic's unmodified Claude Code signed in with *your* Claude subscription. Nothing is billed
> per token; usage counts against your plan's limits. Anthropic currently states that `claude -p`
> and third-party-app usage "still draw from your subscription's usage limits", but its policy
> restricts subscription logins to "ordinary use of Claude Code and other native Anthropic
> applications" and it may enforce that "without prior notice" (it did so in April 2026). Use this
> only for yourself, keep the PC reachable only via your VPN, and expect it to stop working
> someday. Requires the PC to be on and online.
>
> ✗ GymCoach will never ask for, store, or use a claude.ai login or Claude Code OAuth token on this
> phone; Anthropic prohibits apps from handling those credentials.

---

## 3. Android client decision (c)

### 3.1 Official Java SDK — facts (all live-checked 2026-09-04)

- Coordinates: `com.anthropic:anthropic-java:2.60.0` — Maven Central `maven-metadata.xml`:
  `<latest>2.60.0</latest>`, `<lastUpdated>20260901182150</lastUpdated>`; GitHub release v2.60.0
  dated 2026-09-01 ("Added support for claude-fable-5-1 and claude-mythos-5-1"). Releases are
  roughly weekly (v2.56 … v2.60 all in Aug/Sep 2026).
- Java level: "This library requires Java 8 or later." (platform Java SDK page). Build:
  `jvmTarget = JVM_1_8`, `-Xjdk-release=1.8`, Kotlin language 2.0 / API 1.9, compiled with the
  Kotlin 2.3.21 Gradle plugin (buildSrc + `gradle/libs.versions.toml`).
- Module split (Java SDK page): `anthropic-java-core` ("does not depend on OkHttp"),
  `anthropic-java-client-okhttp`, `anthropic-java` (aggregator).
- **Published dependency graph** (from the 2.60.0 POMs on Maven Central):
  - `anthropic-java` → `anthropic-java-client-okhttp` + `org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.0`
  - `anthropic-java-client-okhttp` → core + `com.squareup.okhttp3:okhttp:4.12.0` (runtime)
  - `anthropic-java-core` → `jackson-core`, `jackson-databind` 2.19.4 (compile);
    `jackson-annotations`, `jackson-datatype-jdk8`, `jackson-datatype-jsr310`,
    `jackson-module-kotlin` 2.19.4 (runtime); `org.jetbrains.kotlin:kotlin-reflect:1.9.0`;
    `com.github.victools:jsonschema-generator` / `jsonschema-module-jackson` /
    `jsonschema-module-swagger-2` 4.38.0 (runtime); `io.swagger.core.v3:swagger-annotations:2.2.50`;
    `com.google.errorprone:error_prone_annotations:2.50.0`; `com.standardwebhooks:standardwebhooks:1.1.1`.
  - Jackson compatibility (Java SDK page): "compatible with version 2.13.4 or higher, but depends on
    version 2.19.4 by default"; the SDK "throws an exception if it detects an incompatible Jackson
    version at runtime".
- Size: `anthropic-java-core-2.60.0.jar` downloaded = **27,231,996 bytes, 11,770 entries** (before
  Jackson, kotlin-reflect, victools). Kotlin/Jackson reflection means R8 cannot strip most of it.
- R8/ProGuard: the jar contains `META-INF/proguard/anthropic-java-core.pro` (1,447 bytes) with rules
  such as `-keep class kotlin.reflect.** { *; }` and `-keepclassmembers class com.anthropic.** {
  <init>(...); @com.fasterxml.jackson.annotation.* *; }`. Docs: "Although the SDK uses reflection,
  it is still usable with ProGuard and R8 because `anthropic-java-core` is published with a
  configuration file containing keep rules." (Java SDK page, accordion "ProGuard/R8 configuration").
- Android: **no statement anywhere.** README/docs never mention Android or a min API; the GitHub
  issue search for "android" returns only an unrelated merged PR (#111, Bedrock/Vertex). Java-8
  bytecode + `java.time` (jsr310) is fine on minSdk 26 in principle, but nothing is tested or
  promised.
- Useful SDK features we would give up: `MessageAccumulator`, typed `RawMessageStreamEvent`, typed
  exceptions (`RateLimitException`, `UnauthorizedException`, `SseException` …), automatic retries
  (2, on connect errors/408/409/429/5xx), dynamic timeouts, `putAdditionalBodyProperty` for new
  fields.

### 3.2 Raw HTTPS stack — facts

- **OkHttp** — Maven Central latest `5.5.0` (`lastUpdated 20260816`); CHANGELOG "Version 5.5.0 —
  2026-08-16", "Version 5.0.0 — 2025-07-02" ("OkHttp is now packaged as separate JVM and Android
  artifacts"). README: "OkHttp works on Android 5.0+ (API level 21+) and Java 8+." `4.12.0` (what the
  Anthropic SDK pins) also exists and is the most battle-tested 4.x.
- **okhttp-sse** — same versions on Central (`5.5.0` latest). API (source, `okhttp3.sse`):
  `EventSources.createFactory(callFactory: Call.Factory): EventSource.Factory`;
  `EventSourceListener` with `onOpen(eventSource, response)`, `onEvent(eventSource, id: String?,
  type: String?, data: String)`, `onClosed(eventSource)`, `onFailure(eventSource, t: Throwable?,
  response: Response?)`.
- **kotlinx-serialization-json** — Central latest `1.12.0-RC` (2026-09-04, Kotlin 2.4.10); stable
  releases and their Kotlin baseline (CHANGELOG): `1.11.0` (2026-04-10, Kotlin 2.3.20), `1.10.0`
  (2026-01-21, Kotlin 2.3.0), `1.9.0` (2025-06-27, Kotlin 2.2.0), `1.8.1` (2025-03-31, Kotlin 2.1.20).
  **Pin it to the Kotlin plugin version chosen for the project** (the serialization Gradle plugin
  version = Kotlin version; the runtime must not be newer than the compiler baseline).
- Key storage: `androidx.security:security-crypto` (EncryptedSharedPreferences) is **deprecated**
  ("Deprecated all APIs in favour of existing platform APIs and direct use of Android Keystore",
  release notes 1.1.0-beta01, 2025-06-04; last release 1.1.0, 2025-07-30). Use Android Keystore
  (`AndroidKeyStore` AES key + `Cipher`) directly to wrap the API key — out of R1's scope but noted
  because REQUIREMENTS.md says "Android Keystore-backed encrypted storage".

### 3.3 Decision: raw HTTPS (OkHttp + kotlinx.serialization + hand-written SSE parser)

Justification, in order of weight:
1. **Compile-conservatism.** We cannot compile `:app` today. The SDK's surface (builders, `JsonValue`
   for schemas, `ContentBlock.ofX`, beta vs non-beta `MessageCreateParams`) is broad and moves weekly;
   a single misremembered method is a build break we cannot detect. The wire protocol, by contrast,
   is versioned (`anthropic-version: 2023-06-01`, "Anthropic preserves: existing input parameters,
   existing output parameters") and fully documented; a small `@Serializable` model over it is
   inspectable line by line.
2. **Pluggable backend.** The home bridge does not speak the Java SDK; it emits Claude Code
   `stream-json` lines that wrap the *same* Messages stream events. Owning the transport lets one
   event model serve both backends (§3.6).
3. **Weight and R8 risk.** 27 MB core jar + Jackson databind + kotlin-reflect + victools schema
   generator + swagger annotations, all reflection-heavy and held by keep rules, for an app that needs
   one endpoint. Longer R8 runs, larger APK, unverified Android behaviour.
4. **What we lose is cheap to rebuild.** Retries (429/5xx with `retry-after`), timeouts, and a
   message accumulator are ~150 lines in Kotlin.

Concrete pins (to be confirmed with the Kotlin/AGP versions chosen in the build research):
`com.squareup.okhttp3:okhttp:4.12.0` (or `5.5.0` — both verified on Central; 4.12.0 is the more
conservative choice and matches the SDK's own pin); `org.jetbrains.kotlinx:kotlinx-serialization-json`
at the version matching the Kotlin plugin (1.9.0 ↔ Kotlin 2.2.x, 1.10.0 ↔ 2.3.0, 1.11.0 ↔ 2.3.20);
optional `com.squareup.okhttp3:okhttp-sse` (same version as okhttp) if we prefer the listener over
the manual parser. No Jackson, no reflection.

### 3.4 Wire contract — endpoint and headers

Base URL `https://api.anthropic.com` (API overview). Endpoint: `POST /v1/messages`.

Request headers (API overview + Messages reference):

| Header | Value | Notes |
|---|---|---|
| `x-api-key` | `sk-ant-api03-…` | "Legacy fallback for `Authorization`, still supported". Alternative: `Authorization: Bearer <key>`. Send one, not both. |
| `anthropic-version` | `2023-06-01` | Required. "Anthropic recommends using the latest API version"; `2023-06-01` is the current (and only non-deprecated) version. |
| `content-type` | `application/json` | Required. |
| `anthropic-workspace-id` | `wrkspc_…` | Only for multi-workspace personal keys; omit for a single-workspace key (400 `anthropic-workspace-id is required …` otherwise). |
| `anthropic-beta` | comma-separated beta ids | Only when a beta feature is used. Not needed for anything in this document. |
| `accept` | `text/event-stream` | Optional for streaming; okhttp-sse adds it automatically. |

Response headers: `request-id` (e.g. `req_018EeWyXxfu5pfWkrYcMdjWG` — log it), `anthropic-organization-id`,
`anthropic-workspace-id`, plus the rate-limit headers in §3.11. Request size limit: 32 MB.

### 3.5 Wire contract — request body

Full example for a GymCoach chat turn (all fields documented on the Messages reference / feature pages):

```json
{
  "model": "claude-opus-5",
  "max_tokens": 4096,
  "stream": true,
  "system": [
    {
      "type": "text",
      "text": "<stable coach system prompt, ~3,500 tokens, byte-identical every turn>",
      "cache_control": { "type": "ephemeral" }
    }
  ],
  "tools": [
    {
      "name": "propose_plan_change",
      "description": "Propose a structured change to the user's current training plan. The app shows the proposal to the user and applies it only after explicit confirmation. Use this instead of describing changes in prose whenever the user asks to swap an exercise, change sets/reps/load, add or remove a session, or change the goal.",
      "strict": true,
      "input_schema": {
        "type": "object",
        "properties": {
          "kind": { "type": "string", "enum": ["swap_exercise", "change_volume", "add_cardio_day", "change_goal", "set_checkin_question"] },
          "rationale": { "type": "string" },
          "payload": { "type": "object", "additionalProperties": false, "properties": { "...": {} } }
        },
        "required": ["kind", "rationale", "payload"],
        "additionalProperties": false
      }
    }
  ],
  "tool_choice": { "type": "auto" },
  "thinking": { "type": "adaptive" },
  "output_config": { "effort": "low" },
  "metadata": { "user_id": "<opaque, ≤512 chars, optional>" },
  "messages": [
    { "role": "user", "content": [ { "type": "text", "text": "<compact JSON context: profile, active gym, current plan, recent logs>" }, { "type": "text", "text": "<user's message>" } ] }
  ]
}
```

Field rules (with source):
- `model`: `"claude-opus-5"` / `"claude-sonnet-5"` — dateless ids are pinned snapshots ("Every Claude
  model ID is a pinned snapshot, including the dateless IDs used from the 4.6 generation on").
  Context 1M tokens, max output 128K, default effort `high` (models overview).
- `max_tokens`: required; hard cap on thinking + text ("thinking counts toward `max_tokens`").
- `system`: string **or** array of `{"type":"text","text":…,"cache_control":{"type":"ephemeral"[,"ttl":"5m"|"1h"]}}`.
  Only the array form can carry `cache_control`. Up to 4 breakpoints per request; prefix order is
  `tools` → `system` → `messages`, so a breakpoint on the system block also caches the tool list.
  Alternative: top-level `"cache_control": {"type":"ephemeral"}` on the request body ("automatically
  manages cache breakpoints as conversations grow") — recommended for the message history.
- `messages`: array of `{"role":"user"|"assistant"|"system","content": string | ContentBlockParam[]}`.
  First message must be `user`. **No assistant prefill**: a request that ends with an assistant
  message returns 400 `"This model does not support assistant message prefill. The conversation
  must end with a user message."` `role:"system"` inside `messages` (mid-conversation operator
  message) is supported on Opus 5 (not Sonnet 5) and must follow a user message. Consecutive
  same-role messages are allowed.
- `tools[]`: `{name (regex ^[a-zA-Z0-9_-]{1,64}$), description, input_schema (JSON Schema object),
  strict?, cache_control?, input_examples?, eager_input_streaming?, defer_loading?, allowed_callers?}`.
  `strict: true` requires `"additionalProperties": false` and `required` and guarantees
  "Tool `input` strictly follows the `input_schema`". Strict tool use "is HIPAA eligible" but schema
  text is cached separately — keep no personal data in schemas.
- `tool_choice`: `{"type":"auto"|"any"|"tool"|"none", "disable_parallel_tool_use"?: bool}`;
  `tool` form adds `"name"`. `auto` is the default when tools are present. Changing `tool_choice`
  "will invalidate cached message blocks". (Forced `any`/`tool` returns 400 only on Fable 5.1 /
  Mythos 5.1, not on Opus 5.)
- `thinking`: `{"type":"adaptive", "display"?: "summarized"|"omitted"}`. On Opus 5 and Sonnet 5
  "thinking is already on and needs no configuration. `display` defaults to `"omitted"`". Opus 5
  accepts `{"type":"disabled"}` only at effort `high` or lower (400 at `xhigh`/`max`) and the docs
  warn that with thinking disabled Opus 5 "can occasionally emit tool calls as plain text"; prefer
  adaptive + low effort. `{"type":"enabled","budget_tokens":N}` → 400 on these models.
- `output_config`: `{"effort": "low"|"medium"|"high"|"xhigh"|"max", "format"?: {...}}`. Default
  `high` ("Setting `effort` to `"high"` produces exactly the same behavior as omitting"). Effort and
  thinking config are rendered into the prompt: changing them between requests invalidates the cache.
- `temperature`, `top_p`, `top_k`: **rejected with 400 on Opus 5 / Sonnet 5** ("non-default
  `temperature`, `top_p`, or `top_k` values return a 400 error on every request"). Do not send.
- `stop_sequences`: optional string array. `metadata.user_id`: optional opaque id (≤ 512 chars).

Content block param shapes used by GymCoach:
- text: `{"type":"text","text":"…"}` (+ optional `cache_control`).
- tool_result (user turn): `{"type":"tool_result","tool_use_id":"toolu_…","content": "string" |
  [{"type":"text","text":"…"}], "is_error"?: true}`. Rules: "Tool result blocks must immediately
  follow their corresponding tool use blocks"; "the tool_result blocks must come FIRST in the content
  array. Any text must come AFTER all tool results"; return **all** results of one assistant turn in
  **one** user message; errors go back as `is_error: true` with an instructive message.
- assistant turn echo: append the previous assistant `content` array **unchanged**, including
  `thinking` blocks (even with empty `thinking` text — the `signature` carries the encrypted
  reasoning) and any `redacted_thinking` blocks. "Modified thinking blocks are rejected with a 400
  error."

GymCoach confirmation flow: the app must answer a `tool_use` with a `tool_result` in the *next*
request. Two valid patterns: (1) wait for the user's Confirm/Reject, then send
`{"type":"tool_result","tool_use_id":…,"content":"applied"}` or `"content":"rejected by user: <reason>"`
as the first block of the next user message (that request may carry the user's next text after the
tool_result); (2) respond immediately with `"content":"proposal recorded; awaiting user confirmation"`
and let the assistant finish its text. Pattern 1 keeps Claude's view of the plan consistent.

### 3.6 Wire contract — streaming (`"stream": true`, SSE)

Event flow (streaming page): "1. `message_start` … 2. A series of content blocks, each of which has
a `content_block_start`, one or more `content_block_delta` events, and a `content_block_stop` event.
… 3. One or more `message_delta` events … 4. A final `message_stop` event." "Event streams may also
include any number of `ping` events." "new event types may be added, and your code should handle
unknown event types gracefully." "The token counts shown in the `usage` field of the `message_delta`
event are *cumulative*."

Verbatim event examples from the docs:

```
event: message_start
data: {"type": "message_start", "message": {"id": "msg_1nZdL29xx5MUA1yADyHTEsnR8uuvGzszyY", "type": "message", "role": "assistant", "content": [], "model": "claude-opus-5", "stop_reason": null, "stop_sequence": null, "usage": {"input_tokens": 25, "output_tokens": 1}}}

event: content_block_start
data: {"type": "content_block_start", "index": 0, "content_block": {"type": "text", "text": ""}}

event: ping
data: {"type": "ping"}

event: content_block_delta
data: {"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "Hello"}}

event: content_block_stop
data: {"type": "content_block_stop", "index": 0}

event: message_delta
data: {"type": "message_delta", "delta": {"stop_reason": "end_turn", "stop_sequence":null}, "usage": {"output_tokens": 15}}

event: message_stop
data: {"type": "message_stop"}
```

Tool use in a stream:

```
event: content_block_start
data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_01T1x1fJ34qAmk2tNTrN7Up6","name":"get_weather","input":{}}}

event: content_block_delta
data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"location\":"}}

event: content_block_delta
data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":" \"San"}}
...
event: content_block_stop
data: {"type":"content_block_stop","index":1}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":89}}
```

"the deltas are *partial JSON strings*, whereas the final `tool_use.input` is always an *object*.
You can accumulate the string deltas and parse the JSON once you receive a `content_block_stop`".
Note the first delta may be an empty string and `content_block_start` carries `"input": {}`.

Thinking in a stream (display `summarized`):

```
event: content_block_start
data: {"type": "content_block_start", "index": 0, "content_block": {"type": "thinking", "thinking": "", "signature": ""}}

event: content_block_delta
data: {"type": "content_block_delta", "index": 0, "delta": {"type": "thinking_delta", "thinking": "I need to find the GCD of 1071 and 462 using the Euclidean algorithm.\n\n1071 = 2 × 462 + 147"}}

event: content_block_delta
data: {"type": "content_block_delta", "index": 0, "delta": {"type": "signature_delta", "signature": "EqQBCgIYAhIM1gbcDa9GJwZA2b3hGgxBdjrkzLoky3dl1pkiMOYds..."}}

event: content_block_stop
data: {"type": "content_block_stop", "index": 0}
```

With the default `display: "omitted"` (Opus 5 / Sonnet 5): "the thinking block opens, a single
`signature_delta` arrives, and the block closes without any `thinking_delta` events." With adaptive
thinking a turn may contain *no* thinking block at all ("Don't build application logic that assumes
every assistant turn starts with one"). `usage.output_tokens_details.thinking_tokens` "appears only
on the final `message_delta` event".

Mid-stream error (HTTP status is already 200):

```
event: error
data: {"type": "error", "error": {"type": "overloaded_error", "message": "Overloaded"}}
```

Delta type catalogue: `text_delta{text}`, `input_json_delta{partial_json}`, `thinking_delta{thinking}`,
`signature_delta{signature}`, plus `citations_delta` (only with citations — not used). Refusal in a
stream: the final `message_delta` carries `"stop_reason":"refusal"`; "A refusal can arrive before any
output, or mid-stream after partial output. In either case, treat any partial output as incomplete
and discard it." Stop details for refusals are on the final message, see §3.8.

SSE parsing rules for the hand-written parser (SSE spec + the above): read the body as UTF-8 lines;
a blank line terminates an event; `event:` sets the name, `data:` appends a payload line (join
multiple `data:` lines with `\n`), ignore `:` comment lines and `id:`/`retry:`; JSON-parse `data` and
**dispatch on the JSON `type` field** (it always matches the event name); ignore `ping` and unknown
types; for `error` surface `error.type`/`error.message` and abort; maintain a per-`index` block
accumulator (text → append; tool_use → append `partial_json`, parse on stop; thinking → keep
`signature`, keep text if any) and a top-level `stop_reason`/`usage` merged from `message_start` and
each `message_delta`. OkHttp: read `response.body!!.source()` incrementally; never install a
BODY-level logging interceptor on the streaming client; set a generous read timeout (`ping` events
keep the connection alive but their cadence is not documented) and a call timeout of several
minutes; treat `IOException` before `message_stop` as a retryable transport failure.

### 3.7 Wire contract — non-streaming response shape (also the accumulated shape)

```json
{
  "id": "msg_01…", "type": "message", "role": "assistant", "model": "claude-opus-5",
  "content": [
    { "type": "thinking", "thinking": "", "signature": "EosnCkYICxIMMb3LzNrMu..." },
    { "type": "text", "text": "…" },
    { "type": "tool_use", "id": "toolu_01A09q90qw90lq917835lq9", "name": "propose_plan_change", "input": { "…": "…" } }
  ],
  "stop_reason": "tool_use",
  "stop_sequence": null,
  "stop_details": null,
  "usage": {
    "input_tokens": 50, "output_tokens": 348,
    "cache_creation_input_tokens": 0, "cache_read_input_tokens": 3546,
    "output_tokens_details": { "thinking_tokens": 312 }
  }
}
```

`total_input_tokens = cache_read_input_tokens + cache_creation_input_tokens + input_tokens`
(`input_tokens` is only the part after the last cache breakpoint). `redacted_thinking` blocks
(`{"type":"redacted_thinking","data":"…"}`) may appear and must be echoed back unchanged.

### 3.8 `stop_reason` values and handling (handling-stop-reasons + refusals pages)

| `stop_reason` | Meaning | GymCoach handling |
|---|---|---|
| `end_turn` | finished naturally | render |
| `max_tokens` | hit `max_tokens` | show "truncated"; offer continue (append assistant turn unchanged + new user message) or raise cap / lower effort |
| `stop_sequence` | custom stop hit | n/a |
| `tool_use` | tool call(s) pending | execute/confirm, reply with `tool_result`(s) |
| `pause_turn` | server-tool loop paused | resend assistant content as-is (only with server tools; n/a) |
| `refusal` | classifier declined (Opus 5, Fable 5/5.1) | read `stop_details`; discard partial output; offer retry on Sonnet 5 |
| `model_context_window_exceeded` | response filled the context | treat as truncated; compact history |

Refusal shape (HTTP 200): `"stop_reason":"refusal","stop_details":{"type":"refusal","category":"cyber",
"explanation":"…"}`; `category` ∈ `"cyber"`, `"bio"`, `"frontier_llm"`, `"reasoning_extraction"`,
`"general_harms"` or `null` (both `category` and `explanation` may be `null`; `stop_details` is
`null` for every other stop reason). Billing: "You are not billed for a refusal that arrives before
any output … A mid-stream refusal bills the input tokens and the output already streamed at normal
rates." Fitness/nutrition content is far from these categories; the app only needs a graceful
"Claude declined; retry with Sonnet 5?" path. (Server-side `fallbacks` is a beta and not needed.)

### 3.9 Error JSON and HTTP status codes (errors page)

Body shape (always): 

```json
{ "type": "error", "error": { "type": "not_found_error", "message": "The requested resource could not be found." }, "request_id": "req_011CSHoEeqs5C35K2UUqR7Fy" }
```

| Status | `error.type` | Retry? | Notes |
|---|---|---|---|
| 400 | `invalid_request_error` | no | also returned when a *self-set* spend limit is reached ("You have reached your specified API usage limits") |
| 401 | `authentication_error` | no | malformed/revoked/**expired** key → prompt for a new key |
| 402 | `billing_error` | no | top up credits |
| 403 | `permission_error` | no | key lacks access to model/workspace |
| 404 | `not_found_error` | no | wrong path or model id |
| 409 | `conflict_error` | yes (SDKs retry) | |
| 413 | `request_too_large` | no | > 32 MB |
| 429 | `rate_limit_error` | yes, honour `retry-after` | tier spend-cap variant has **no** `retry-after` and `error.details.error_code: "enforced_spend_limit_reached"` — do not retry |
| 500 | `api_error` | yes, backoff | |
| 504 | `timeout_error` | yes | use streaming |
| 529 | `overloaded_error` | yes, backoff | can also arrive mid-stream as `event: error` |

Retry policy to replicate the SDKs: retry connection errors, 408, 409, 429 (with `retry-after`),
5xx; 2 retries with exponential backoff; never retry 400/401/402/403/404/413 or the spend-cap 429.

Model-specific 400s worth guarding in code: prefill (§3.5), `thinking.type: "enabled"` (removed on
4.7+: `"thinking.type.enabled" is not supported for this model`), sampling params (§3.5), modified
thinking blocks (`` `thinking` or `redacted_thinking` blocks in the latest assistant message cannot be
modified ``), text before `tool_result` in a user message.

### 3.10 Prompt-caching contract (prompt-caching page)

- Breakpoint: `"cache_control": {"type":"ephemeral"}` on a block; optional `"ttl": "5m"` (default) or
  `"1h"`. Up to 4 breakpoints. Minimum cacheable prefix: 512 tokens (Opus 5), 1,024 tokens (Sonnet 5)
  — shorter prefixes silently don't cache.
- Top-level `"cache_control": {"type":"ephemeral"}` on the request = automatic breakpoint management
  ("recommended starting point for most use cases").
- Prices: 5-min write 1.25× base input, 1-h write 2×, read 0.1×. Cache reads do **not** count toward
  ITPM rate limits ("only uncached input tokens count toward your ITPM rate limits").
- Invalidators: any byte change in the prefix; changing `tool_choice` (messages part); changing
  `thinking` config or top-level `effort`; changing the tool list. Keep the system prompt byte-stable
  (no timestamps — pass "today" inside the user turn instead).
- Verify with `usage.cache_read_input_tokens > 0` on the second request within 5 minutes.

### 3.11 Rate-limit headers (rate-limits page, verbatim names)

`retry-after` (seconds; "Not sent with the spend-cap 429"), `anthropic-ratelimit-requests-limit`,
`anthropic-ratelimit-requests-remaining`, `anthropic-ratelimit-requests-reset` (RFC 3339),
`anthropic-ratelimit-tokens-limit`, `anthropic-ratelimit-tokens-remaining`,
`anthropic-ratelimit-tokens-reset`, `anthropic-ratelimit-input-tokens-limit`,
`anthropic-ratelimit-input-tokens-remaining`, `anthropic-ratelimit-input-tokens-reset`,
`anthropic-ratelimit-output-tokens-limit`, `anthropic-ratelimit-output-tokens-remaining`,
`anthropic-ratelimit-output-tokens-reset` (+ `anthropic-priority-*` for Priority Tier only, which
Opus 5 / Sonnet 5 do not support). Token `remaining` values are "rounded to the nearest thousand".
Limits are per model and per organisation; token-bucket algorithm ("capacity is continuously
replenished"). Start tier: Opus 5 and Sonnet 5 each 1,000 RPM / 2,000,000 ITPM / 400,000 OTPM;
monthly spend cap $500.

### 3.12 One event model for both backends (design note)

Define the app-side stream event as the Messages-API event JSON (§3.6). The direct backend feeds it
from SSE `data:` payloads. The home bridge feeds it from Claude Code's `stream-json` lines
(`{"type":"stream_event","event":{...}}` per S5 — the nested `event` is the same object) and maps the
final `result` line to `message_delta`/`message_stop`. Tool proposals from the bridge come via
`--json-schema` structured output rather than `tool_use` blocks, so the bridge adapter must
synthesise a `tool_use` event from `structured_output`. Everything above the adapter (accumulator,
UI, confirmation flow) stays backend-agnostic.

---

## 4. Unverified or open items

1. The exact February-2026 wording of the OAuth restriction (before it was rewritten into the current
   S2 text) — only secondary sources quote it.
2. claude.com/pricing Max 20x price (fetch summariser returned "$100" for both Max tiers; 5x = $100
   is consistent with N1; 20x commonly $200). Not load-bearing.
3. Whether Anthropic considers a *single-user* bridge on the user's own PC "ordinary use of Claude
   Code" — no official page answers this; §1.10 item 3 is an interpretation.
4. `ping` cadence in SSE streams is undocumented; timeout values in §3.6 are engineering defaults.
5. The browser-only CORS gate (`anthropic-dangerous-direct-browser-access`) was not re-verified; it
   is irrelevant to a native OkHttp client, which is not subject to CORS.
6. kotlinx-serialization version must be re-pinned once the Kotlin Gradle plugin version is fixed
   (see §3.2); OkHttp 4.12.0 vs 5.5.0 likewise once AGP/minSdk toolchain is fixed.
7. `claude -p` flags for the bridge (`--system-prompt`, `--permission-mode dontAsk`, `--json-schema`,
   `--resume`) were read from S5 on 2026-09-04; Claude Code changes weekly — re-check when the bridge
   is built. The bridge is not part of the first release.

## 5. Additional platform pages fetched for §3 (all platform.claude.com/docs/en/…, 2026-09-04)

`api/overview`, `api/messages`, `api/versioning`, `api/errors`, `api/rate-limits`,
`about-claude/pricing`, `about-claude/models/overview` (models overview), `build-with-claude/streaming`,
`build-with-claude/prompt-caching`, `build-with-claude/thinking`,
`build-with-claude/thinking-steering-and-cost`, `build-with-claude/effort`,
`build-with-claude/handling-stop-reasons`, `build-with-claude/refusals-and-fallback`,
`agents-and-tools/tool-use/overview`, `agents-and-tools/tool-use/define-tools`,
`agents-and-tools/tool-use/handle-tool-calls`, `agents-and-tools/tool-use/tool-reference`,
`agents-and-tools/tool-use/strict-tool-use`, `cli-sdks-libraries/sdks/java`,
`manage-claude/authentication`, `manage-claude/app-attest`, `cli-sdks-libraries/cli/authentication`.
Java SDK repo: https://github.com/anthropics/anthropic-sdk-java (README, releases, `build.gradle.kts`,
`buildSrc/…/anthropic.kotlin.gradle.kts`, `gradle/libs.versions.toml`); Maven Central POMs and jar for
2.60.0. OkHttp: README + CHANGELOG (https://github.com/square/okhttp), `okhttp-sse` sources.
kotlinx.serialization CHANGELOG (https://github.com/Kotlin/kotlinx.serialization). AndroidX Security
release notes (https://developer.android.com/jetpack/androidx/releases/security).
