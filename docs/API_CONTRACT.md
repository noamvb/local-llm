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

A client must also handle the stream simply stopping: the service process can be killed
under memory pressure, which surfaces as `onServiceDisconnected`.

## Versioning

`getApiVersion()` returns the contract version the service implements. A client compares
it against its own copy and degrades gracefully rather than assuming. Adding optional
fields with defaults is backward compatible; anything else requires a version bump and a
coordinated update of every client.
