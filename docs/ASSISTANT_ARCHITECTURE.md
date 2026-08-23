# Private assistant architecture

Status: accepted target design; version-two production grammar is not yet frozen.

This document records the boundary for expanding LocalLLM from a version-one summary
writer into a private, read-only assistant shared by the owner's approved apps. It is an
implementation contract, not a claim that the version-two interfaces or UI already exist.
The current shipped interface remains the version-one contract in `API_CONTRACT.md` until
the staged compatibility and release gates below are complete.

## Invariants

1. Each client app remains the authority over its own data, dates, filters, statistics,
   comparisons, and display values. LocalLLM receives bounded aggregate facts, never rows.
2. The assistant is read-only. No contract contains a write, edit, delete, archive,
   restore, timer, logging, purchase, correction, or synchronization operation.
3. LocalLLM authenticates a caller from its Binder UID, package name, and approved signing
   lineage. A caller-provided identifier is metadata only. Each client performs the
   reciprocal package-and-signer check before it sends any facts.
4. A question is scoped to the initiating app unless the owner explicitly selects or asks
   for both apps. Cross-app output keeps the sources in separate evidence groups and never
   claims correlation, causation, medical meaning, or behavioral effect.
5. The model writes language only. Providers calculate facts; deterministic code selects
   sources, validates the query, and validates every terminal sentence against cited facts.
6. LocalLLM alone owns durable assistant history and its deletion. History stays on the
   device, is excluded from backup and transfer, has no export path, and persists until the
   owner deletes a conversation or clears all history.
7. A failed generated output is retained only as escaped inert text behind a warning. It is
   never an answer, evidence, navigation instruction, model context, notification body,
   Insights card, or app action.
8. Automatic summaries are client-owned background jobs over fresh, settled client facts.
   Their fixed queries bypass the router and their public notifications contain neutral
   text only.
9. Feature work, client adoption, releases, and physical evidence remain separate gates.
   Publication never authorizes installing, launching, or reading production apps.

## Trust boundary

```text
initiating client
  verifies exact LocalLLM component + approved LocalLLM signer
      |
      | IAssistantServiceV2 (question, capability and history calls)
      v
LocalLLM
  derives Binder UID -> package set -> approved package + signer
  selects source deterministically and validates explicit cross-app consent
      |
      | IAssistantFactsProviderV2 (bounded aggregate query only)
      v
owning client provider
  verifies exact LocalLLM package + approved LocalLLM signer
  computes facts from its authoritative snapshot and returns provenance
```

The Android `signature|knownSigner` permissions remain defense in depth. They are not a
substitute for the runtime package-and-signing-lineage checks. `clientId`, thread IDs, and
source labels never grant authority.

LocalLLM owns a global access switch and one switch for each approved client. A verified
approved client is enabled by default. Disabling a client blocks its new assistant/history
calls and blocks provider queries to it; it does not silently erase existing history.

## Version-one compatibility repairs

The existing `IInsightService` and `IInsightCallback` transaction layout stays intact.
Version-one clients and the host gain signer verification, same-session API negotiation,
finite timeouts, exact-once death/cancellation handling, and authoritative terminal-text
reconstruction. A non-null `resultSchema` is rejected explicitly in version one because
the shipped service does not constrain or return structured JSON.

The canonical checked source lives in LocalLLM. Cannsheet and Poop Schedule receive it only
after the canonical change merges; a single copy procedure and CI hash check prevent the
three copies from drifting.

## Separate version-two surfaces

Version two uses new Binder interfaces rather than adding transaction codes to version
one:

- `IAssistantServiceV2` / `IAssistantCallbackV2`: capabilities, turns,
  clarification, cancellation, terminal results, and bounded history pages;
- `IAssistantFactsProviderV2` / `IAssistantFactsCallbackV2`: mutually
  authenticated, bounded, read-only aggregate fact requests.

The JSON contract defines `AssistantCapabilities`, `AssistantTurnRequest`,
`AggregateQuery`, `FactEvidence`, `AssistantEvent`, `AssistantTerminalResult`, and
`HistoryPage`. Unknown object fields are forward-compatible. Unknown enum values do not
silently acquire meaning: each enum either has an explicit `UNKNOWN` value or makes the
peer version incompatible.

Capabilities report protocol and grammar versions, provider versions, output/history
features, supported model roles, and the installed/loaded/partial/unavailable/unsupported
state of each role. A mixed-version peer must fail or degrade deterministically before any
fact transfer.

## Aggregate-query grammar: draft boundary

FunctionGemma may propose a value in this grammar; it may not execute it or widen it.
Deterministic code validates the parsed value, source permission, ambiguity, and all
bounds before a provider is contacted.

```text
AggregateQuery
  sources: one initiating source, or both only with explicit owner permission
  metrics: source-scoped allowlisted metric IDs
  period: LAST_DAYS | explicit bounded dates | ALL_TIME
  comparison: none | PREVIOUS_EQUAL_PERIOD
  groupings: source-scoped allowlisted values
  filters: typed source-specific values
  resultMode: FACTS | COUNT_AND_NAVIGATION | EXPLANATION
```

The production field names, enum vocabulary, and numerical limits remain draft until the
deterministic parser and base-model spike are evaluated against the same frozen corpus.
The freeze must still enforce at most two sources, one comparison, bounded metrics,
filters, facts and bytes, and at most one validated navigation target per source. There is
no arbitrary expression, SQL, projection, cursor, selection string, record JSON, note
body, database ID, event ID, queue entry, or active-session payload.

Source-specific rules include:

- Cannsheet can answer explicit questions with current, live, settled activity,
  inventory, product-resolved, and correctly qualified recorded-spend aggregates. It
  cannot expose runway, spending projections, pending queue details, or raw records.
- Poop Schedule can answer from non-archived completed local records using approved
  descriptive aggregates. A local note search may return only a count and a validated
  read-only navigation target, never notes or snippets.
- Exact dates and a named Cannsheet product require explicit wording. Inactivity is used
  only when explicitly requested.
- Medical, diagnostic, causal, treatment, advice, write-shaped, raw-row, and unsupported
  requests never map to a provider operation.

An ambiguous source, product, period, or compound request produces clarification instead
of a guess. If one explicitly requested source is unavailable, current facts from the
available source may be returned with a deterministic missing-source warning; saved old
evidence is never substituted.

## Grounded writing and terminal policy

The writer returns structured sentences that cite fact IDs and deterministic limitation
IDs. Validation occurs before a terminal success is persisted:

- every sentence cites at least one supplied fact;
- every number is present in that sentence's cited facts with the same unit and meaning;
- cited IDs exist and remain within their source boundary;
- output respects maximum token and word limits;
- prompt leakage, refusal text, health/causal/advice language, malformed output,
  unsupported navigation, control characters, and bidirectional spoofing fail validation.

Data-quality caveats are app-rendered and are not delegated to the model. Highlights and
comparison cards buffer and validate the terminal result before display. Only an open
Assistant screen may show normalized, escaped fragments labelled `Unverified draft`.

History persists a terminal turn atomically: owner question, validated text or escaped
failed text, terminal status and issues, cited evidence and provenance, sources, period,
as-of time, and model/grammar/policy/provider versions. Successful turns omit uncited
facts. Cancellation and timeout cannot create a successful row. Follow-ups keep structured
references to earlier turns but re-fetch current evidence; old prose is never evidence.

Automatic insights use a separate feed and never enter conversational context.

## Model roles and execution

LocalLLM manages verified artifacts by role:

- `ROUTER`: tuned FunctionGemma when its exact evaluation, licensing, artifact, and
  intended-device gates pass; otherwise the deterministic router;
- `WRITER`: the existing Gemma E2B writer with a remembered viable backend/build.

The initial scheduler keeps one role resident, runs one native inference at a time, and
allows at most two queued requests. Live assistant work outranks open-screen summaries,
which outrank daily background work. Native inference is not preempted after it starts.
An idle model unloads after five minutes. Simultaneous role residency stays disabled until
the intended phone passes the agreed memory, latency, thermal, and alternation tests.

Model downloads are explicit owner-started foreground transfers. A signed manifest pins
role, artifact/version, size, SHA-256, source, terms, grammar compatibility, and rollback
metadata. Verification and atomic promotion finish before a known-good model can be
replaced.

## Background ownership

Each client owns one unique daily WorkManager job because the background caller must hold
the Android foreground-execution state. A run is eligible only after that app's first
fresh, settled sync of the local day and after charging, battery, notification, LocalLLM
access, model-readiness, and snapshot gates pass.

The fixed query uses the current 30 days and immediately preceding 30 days in the source's
authoritative timezone, activity-only facts, and an idempotency key containing source,
revision/fingerprint, local day, period, and output-policy version. It does not invoke the
router. Success or failed validation is saved once; notification text remains public and
neutral. Missed days do not backfill a burst.

## Freeze, merge, release, and evidence gates

The version-two grammar and fixtures freeze only after the deterministic and
FunctionGemma feasibility stream reports which shapes are ambiguous or reliably
unlearnable. Providers cannot merge against invented local variants. Cross-app completion
requires both real providers; a mock provider is not acceptance evidence.

Releases remain dependency-ordered: compatible LocalLLM first, then Cannsheet and Poop
Schedule. Every repository uses a feature PR without version changes, exact merged-main
validation, a separate version-only PR, exact versioned-main validation, an annotated tag,
signed publication, and independent artifact/checksum/package/version/signer verification.

Physical testing uses sandbox packages and synthetic facts only. It does not prove
production-data behavior. Connecting to a phone requires a separately announced and
explicitly authorized bounded ADB plan; release authorization alone does not grant it.
