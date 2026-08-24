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

| Task | Required input | Output | Use |
| --- | --- | --- | --- |
| `PERIOD_SUMMARY` | one period and at least one fact | prose, up to `maxWords` | the insight card |
| `NUDGE` | one period, at least one fact and `lockScreenSafe=true` | one sentence, ≤ `min(maxWords, 20)` words | a notification |
| `PERIOD_COMPARISON` | two periods, each with at least one fact | prose comparing two fact sets | period-over-period cards |

Summary and nudge requests reject comparison-only fields. Comparison requests require
both period objects and both fact sets rather than silently changing task semantics.

### Service-owned v1 bounds

Validation completes before model preparation or scheduler admission. Limits count Unicode
code points and UTF-8 bytes separately:

| Value | Limit |
| --- | --- |
| complete request JSON | 32,768 UTF-8 bytes |
| `clientId` | 64 code points / 128 bytes; starts with a lowercase letter or digit, then uses lowercase letters, digits, `.`, `_`, `-` |
| `subject` | 256 code points / 1,024 bytes |
| period label | 128 code points / 512 bytes |
| facts | 64 per period and 96 across the request |
| fact label | 128 code points / 512 bytes |
| fact value or note | 256 code points / 1,024 bytes |
| `maxWords` | 1–120 |

Required strings are nonblank, have no surrounding whitespace, and reject ISO control,
invisible format, line/paragraph separator and malformed surrogate characters. Period dates must be
provided as a pair of real `YYYY-MM-DD` dates with start no later than end. Unknown task
enum values fail JSON decoding. When both comparison ranges carry dates, the comparison
range must end before the current range starts. These are service limits, not permission checks;
`clientId` remains descriptive and never grants authority or priority.

## Safety policy

`SafetyPolicy` defaults are the strict ones and are compiled into the system instruction:

- `forbidHealthClaims` — no diagnosis, no naming conditions, no medical or dietary
  advice, no causal claims, no reassuring or alarming the reader. Both client apps track
  health-adjacent data; this default stays on.
- `forbidNewNumbers` — the model may use only the numbers it was given.
- `lockScreenSafe` — no product names, quantities or dates in the output.

`lockScreenSafe` exists specifically for `NUDGE`. Because v1 defines that task as
notification output, the service now requires the flag for every `NUDGE`; a request that
does not opt into that restriction fails with `INVALID_REQUEST` before engine work.

The two other booleans remain deliberate v1 opt-outs for compatibility with the frozen
documented contract. Current health-adjacent clients use their strict defaults. A future
contract that removes the opt-outs needs an explicit versioned compatibility decision.

## Output limits

Prompt wording is not the output boundary. LiteRT-LM receives a request-sensitive
`maxOutputToken` with an absolute ceiling of 256 output tokens. The service independently
assembles at most 8,192 characters and validates the terminal text is nonblank and no
longer than the task's actual word limit. A violation is terminal `INTERNAL`; streamed
draft fragments are advisory and never turn an invalid terminal value into success.

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
also prepares an already-installed artifact on demand. Status, prewarm, and generation
cannot initiate acquisition. If no compatible artifact is installed, generation fails
with `MODEL_NOT_READY`; only the owner action in LocalLLM's manager can download one. This
prevents another package signed with a broadly known client certificate from using any
Binder transaction to force a multi-gigabyte transfer.

That owner action is implemented by a separate private, non-sticky `dataSync` foreground
service. It is not part of the AIDL service, does not change a v1 transaction or JSON field,
and cannot be started through status, warmup, generation, self-test, binding, boot, service
restart, or process recreation. Network policy, transfer bytes/stages, and acquisition
failures remain manager-internal typed state rather than new v1 wire values.

## Service scheduling and cancellation

The process admits at most one active generation and two waiting requests, including the
manager's local self-test. A further service request receives terminal `BUSY` without
starting model work. Waiting work is ordered by
priority then FIFO, never pre-empts active native inference, expires after a bounded wait,
and is removed synchronously when its caller cancels or its callback Binder dies.

Contract v1 has no execution-context or priority field. Every v1 task therefore maps
deterministically to the `OPEN_SCREEN` lane; the service does not infer priority from
`clientId`, task names or personal content. The `LIVE_ASSISTANT` and `BACKGROUND` lanes
are reserved for a future contract that can state trusted execution context explicitly.

The in-flight owner and callback death recipient are registered before scheduler
submission. One registration monitor orders cancellation/death immediately before or
during synchronous scheduler admission: cancellation either prevents submission, or
cancels the exact admitted entry. A separate callback monitor admits progress/tokens and
the sole terminal completion/failure, so no nonterminal callback follows a terminal one.
Completed, failed, expired and cancelled entries release both scheduler and Binder-death
registration ownership exactly once.

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

| Code | Meaning | Service categories and retry guidance |
| --- | --- | --- |
| 1 | `BUSY` | queue full, queue wait expired, or service closing; retry later |
| 2 | `MODEL_NOT_READY` | no compatible installed artifact, or an installed backend could not initialize; open LocalLLM for owner acquisition when missing, otherwise retry after the stated condition |
| 3 | `CANCELLED` | caller cancellation, callback death or service shutdown; a caller may start a new request |
| 4 | `INVALID_REQUEST` | malformed, unsupported, unsafe task shape or over-limit input; fix the request |
| 5 | `OUT_OF_MEMORY` | native allocation failed and the lifecycle owner released the engine; retry after memory pressure falls |
| 6 | `UNSUPPORTED_DEVICE` | all compatible native backends were exhausted; do not loop automatically |
| 7 | `INTERNAL` | unexpected engine/service failure or invalid terminal output; retry is caller policy |

Version one has no retryable field and cannot add one without changing the copied
contract. The stable code plus sanitized category message are the available v1 signal;
internal paths, URLs, digests and exception text are never returned to the client.
Owner acquisition failures remain manager-local diagnostics because Binder generation
never crosses the acquisition boundary.

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
