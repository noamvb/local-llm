# Cross-app API contract

## The rule that everything else follows

**Clients send facts. The model writes sentences. The model never calculates.**

A 2-billion-parameter quantised model is a competent writer and an unreliable
arithmetician. Given a list of timestamps and asked "how did this month compare to last",
it will produce a fluent, confident, and frequently wrong answer. Given
`Entries recorded: 18` and `Previous period: 24`, it will write an accurate sentence.

Both client apps already compute exactly the right things:

- Poop Schedule has `EntryAnalytics.summarize()` returning an `AnalyticsSummary`.
- Cannsheet has `InsightsResponseDto` from its Apps Script backend.

Mapping those into a `List<Fact>` is a small, testable, deterministic function in the
client. That function — not the model — is the thing that must be correct.

This also solves a transport problem. Binder caps *all* in-flight transactions for a
process at roughly 1 MB. A few dozen facts is a couple of kilobytes; a year of raw rows
is not, and would fail with `TransactionTooLargeException` under load.

## Interface

Two AIDL files, `IInsightService` and `IInsightCallback`. Payloads are JSON strings
rather than Parcelables, because the three apps live in separate repositories and are
released independently; a JSON contract with `ignoreUnknownKeys` lets the service add
fields without every client being rebuilt in lockstep.

```
int    getApiVersion()
String getEngineState()
String requestInsight(String requestJson, IInsightCallback callback)
void   cancel(String requestId)
```

The callback is `oneway` throughout, so the service never blocks on a slow or dying
client.

The AIDL method order and signatures are the v1 ABI. Comments and JSON validation may be
tightened, but methods must not be reordered, removed or changed in place.

## Request

```json
{
  "contractVersion": 1,
  "clientId": "poop-schedule",
  "task": "PERIOD_SUMMARY",
  "subject": "your own bowel-movement records",
  "period": { "label": "the last 30 days", "start": "2026-07-19", "end": "2026-08-18" },
  "facts": [
    { "label": "Entries recorded", "value": "18" },
    { "label": "Average interval", "value": "1 day 8 hours" },
    { "label": "Most common Bristol type", "value": "4", "note": "9 of 18 entries" },
    { "label": "Entries noting pain", "value": "2 of 18" }
  ],
  "maxWords": 90,
  "stream": true,
  "safety": { "forbidHealthClaims": true, "forbidNewNumbers": true, "lockScreenSafe": false }
}
```

`value` is **already formatted**. Not `1.7333`, but `1.7`. Not an epoch, but
`1 day 8 hours`. Formatting is a client responsibility because the client knows the
user's locale and the app's conventions.

### Tasks

| Task | Output | Use |
| --- | --- | --- |
| `PERIOD_SUMMARY` | prose, up to `maxWords` | the insight card |
| `NUDGE` | one sentence, ≤ 20 words | a notification |
| `PERIOD_COMPARISON` | prose comparing two fact sets | period-over-period cards |

## Safety policy

`SafetyPolicy` defaults are the strict ones and are compiled into the system instruction:

- `forbidHealthClaims` — no diagnosis, no naming conditions, no medical or dietary
  advice, no causal claims, no reassuring or alarming the reader. Both client apps track
  health-adjacent data; this default stays on.
- `forbidNewNumbers` — the model may use only the numbers it was given.
- `lockScreenSafe` — no product names, quantities or dates in the output.

`lockScreenSafe` exists specifically for `NUDGE`. Cannsheet's own convention keeps
product names, quantities and dates out of notification content; a nudge request from
Cannsheet should set this flag so a generated line cannot undo that decision.

### Structured output

`resultSchema` is reserved for a later contract. LocalLLM v1 does not perform constrained
JSON decoding: the service rejects every non-null `resultSchema` with `INVALID_REQUEST`,
and `onComplete.resultJson` is always null. This is an explicit failure rather than a
false guarantee that arbitrary prose is schema-valid.

## Authentication and binding

Authentication is mutual and does not trust names alone:

- Android's `signature|knownSigner` permission is the first inbound gate.
- Every service method then reads `Binder.getCallingUid()` and requires that UID to map
  exclusively to an approved package-and-signing-lineage pair. `clientId` is never used
  for authorization.
- A client directly inspects the pinned
  `com.noamv.localllm.service.InferenceService` component, verifies its exported/enabled
  state and permission, verifies the installed LocalLLM current signer or
  Android-authenticated descendant of the pinned signing lineage, and binds that exact
  component. It verifies again when connected so package replacement cannot swap in a
  different service between inspection and use.

The approved SHA-256 values come from published APKs. A new package or unrelated signing
key is denied even when it can request the manifest permission.

A successful manifest-permission bind is not authorization. Binding alone performs no
engine work. The service begins non-downloading prewarm only after the caller passes the
exact package-and-lineage check on `getApiVersion()`; an authorized generation request
also prepares on demand. This prevents another package signed with a broadly known client
certificate from using bind/unbind cycles to force multi-gigabyte model initialization.

## Client delivery and deadlines

`generateEvents()` exposes a public typed stream: `Progress(percent, stage)`,
`Draft(text)`, `Complete(text)`, and `Failure(error)`. Draft text is a full replaceable
snapshot, not an append-only fragment. `onComplete.text` is authoritative and becomes
`Complete.text` even when it differs from every draft or is the only text returned. The
conflated channel may skip intermediate events for a slow collector but retains the latest
value. The deprecated source-compatible `generate()` adapter ignores progress and drafts,
throws a typed failure's error, and emits only authoritative completion text once; this
keeps existing append-based collectors correct when they copy the hardened client.

One synchronized delivery owner linearizes callback correlation and admission,
first-response timer cancellation, every nonterminal `trySend`, terminal `Complete` or
`Failure` selection, and channel closure. Once an event has been admitted, a timeout or
Binder death cannot close the channel between that event's delivery attempt and its state
transition. A terminal claim precedes its checked `trySend`, so only one terminal event can
win. On a conflated channel, a later winning failure may replace an already delivered draft
or progress value, but it cannot replace an authoritative completion that has already
claimed terminal ownership.

The client has finite deadlines for binding, status/version calls, first response and the
whole generation. One bind deadline covers package/component/signing resolution and the
subsequent service connection; resolution runs off the caller thread. Null binding, null
Binder, binding death, package replacement, service process death, callback-ID mismatch
and channel delivery failure are terminal failures.

Generation negotiation uses the status/version deadline. Its tracked submission worker
is fenced at terminal completion and collector cancellation, so a version call that
returns after its deadline cannot go on to transmit a new request. Android synchronous
Binder calls cannot be forcibly interrupted once entered. A compare-and-set state machine
is the single boundary between `OPEN`, `SUBMISSION_BEGUN`, and `STOPPED`: if stop wins,
the request block is never invoked; if begin wins, the client remembers a later
cancellation and calls `cancel(id)` as soon as the late ID returns. At
most 64 callbacks may wait for that synchronous request ID; a pre-assignment callback
flood fails closed instead of consuming unbounded memory.

Binding has a separate synchronized pending/delivered/terminal owner. Connection,
deadline, Binder death, null binding, binding death, cancellation, and success all claim
that state exactly once. A deadline that loses to successful delivery cannot kill the
winning session; a failure that wins before delivery prevents that session from later
resuming the continuation. Registration cleanup is separately deferred until the
synchronous `bindService()` call returns, so a timeout immediately before or during that
call cannot attempt its only unbind too early and leak the later registration.

The copy-ready client compiles with minSdk 24, matching the oldest current consumer. It
returns `Unavailable` below Android 12/API 31 before touching the newer package-signing
APIs required by LocalLLM's `knownSigner` trust model.

## Canonical source and drift checks

The version-one `client/src/main` tree is canonical. Run
`scripts/localllm_v1_client.sh copy <consumer-root>` to replace only its AIDL, client and
contract subtrees and write a source commit/digest provenance file. Run `check` against a
consumer and `check-local` inside LocalLLM. LocalLLM CI verifies its checked digest and
service-side duplicate; each consumer adds the cross-repository check when it vendors the
merged canonical commit. Copy and consumer-check commands reject canonical source that
differs from `HEAD`, and consumer checking requires both the recorded repository/commit
and digest to match the canonical checkout. No two repositories independently edit these
copied files.

## Errors

| Code | Meaning |
| --- | --- |
| 1 | `BUSY` |
| 2 | `MODEL_NOT_READY` |
| 3 | `CANCELLED` |
| 4 | `INVALID_REQUEST` |
| 5 | `OUT_OF_MEMORY` |
| 6 | `UNSUPPORTED_DEVICE` |
| 7 | `INTERNAL` |

A typed-event client handles these as terminal `Failure` events; the deprecated string
adapter rethrows their `error`. The service process can still be killed under memory
pressure, which surfaces through Binder death or `onServiceDisconnected`.

## Versioning

`getApiVersion()` returns the highest contract version the service implements. A client
calls it on the same authenticated binding it will use, but does **not** infer v1 support
from a numerically higher unknown value. The canonical v1 client has an explicit set of
service API versions declared v1-capable; a future version enters that set only after its
v1 compatibility is specified and tested. Adding optional fields with defaults is
backward compatible; anything else requires a version bump and coordinated client
support.
