# Architecture

This document describes the shipped version-one summary host. The accepted, staged
version-two private-assistant target is specified separately in
`ASSISTANT_ARCHITECTURE.md`; its interfaces, history, providers, and UI must not be treated
as implemented until their feature and release gates complete.

## Processes

```
┌─ Poop Schedule ────────┐        ┌─ LocalLLM ─────────────────────────┐
│ Room (local records)   │        │ InferenceService (AIDL, exported,  │
│ EntryAnalytics         │        │   signature permission)            │
│   .summarize()         │        │ LocalLlmApplication                │
│        ↓               │ bind   │   holds LlmEngine for process life │
│ FactMapper (pure)      │───────▶│ LiteRtEngine → LiteRT-LM 0.16.1    │
│ LocalLlmClient         │◀───────│ ModelStore ← owner manager action  │
│ Insight card (Compose) │ tokens │   (download + SHA-256, ~2 GB)      │
└────────────────────────┘        └────────────────────────────────────┘
        Cannsheet Mobile ─── same client tree, same service
```

Three separate processes. LocalLLM holds the model; the trackers hold the data. Neither
tracker gains an inference dependency, and the 2 GB model exists once on the device.

## Why a bound service

| Option | Verdict |
| --- | --- |
| **AIDL bound service** | Chosen. Typed, streamable via a `oneway` callback, gated by a signature permission, and the client can be told when the service dies. |
| ContentProvider | `call()` is request/response only. Streaming a generation through it means polling. |
| Broadcasts / intents | No stream, no back-pressure, awkward correlation of request to reply. |
| localhost HTTP server | Any app on the phone can reach a localhost socket. It would need its own auth, and a resident socket is worse for battery. |
| `sharedUserId` | Deprecated, and it fuses the apps' storage and identity — far more coupling than this needs. |

## Security model

Access is controlled by one custom permission declared with
`android:protectionLevel="signature|knownSigner"` plus an `android:knownCerts` array.
Android grants it to apps signed by LocalLLM's own key **or** by any certificate whose
SHA-256 digest appears in `app/src/main/res/values/known_signers.xml`.

The permission is necessary but not sufficient. `InferenceService` authorizes every AIDL
transaction using `Binder.getCallingUid()`: the UID must map only to one exact approved
package whose Android-authenticated signing lineage contains its pinned published
certificate. This prevents a different app signed with an otherwise known certificate
from inheriting inference access. The host declares visibility for only those two package
names so signing information is queryable even before Android's bound-service interaction
[makes a caller automatically visible](https://developer.android.com/training/package-visibility/automatic).
The request's self-declared `clientId` is never trusted.

Authentication also runs in the other direction. Before every bind, the canonical client
directly inspects the pinned `com.noamv.localllm.service.InferenceService` component and
verifies the installed LocalLLM package's current certificate or a legitimate rotated
descendant of its pinned release lineage. It re-checks that exact component after
connection. A same-package APK with an unrelated signer, or a wrong service class inside
the right package, is therefore not allowed to receive client facts.

A plain `signature` permission does not work here, and assuming it did was a real defect.
The three apps are signed by three different keys — Poop Schedule with its own release
keystore, Cannsheet Mobile with a debug certificate, LocalLLM with its own — so a plain
signature check would have been granted to none of them. `knownSigner` requires API 31,
which is this app's minimum.

Three consequences worth remembering:

1. **Install order matters.** A signature permission is granted only if the app that
   *defines* it is already installed. Install or update LocalLLM before the clients.
2. **Adding a client is a LocalLLM change.** Its certificate digest must be added to the
   array and a new LocalLLM shipped; an installed one cannot learn a new digest.
3. **Package visibility.** From Android 11, a client cannot even see the service unless
   it declares `<queries><package android:name="com.noamv.localllm" /></queries>`.
   Without it `bindService` returns `false` and nothing else explains why.
4. **Package names are not authentication.** Both directions verify package and signing
   lineage. Adding or rotating an approved identity is a reviewed resource change backed
   by a published artifact.
5. **A bind is intentionally inert.** The broad manifest permission can admit another
   package signed by a known certificate, so model prewarm starts only after an exact
   caller check succeeds on `getApiVersion()` (or preparation begins on an authorized
   generation request).

## Lifecycle and memory

`Engine.initialize()` takes several seconds and the loaded model occupies a large,
contiguous allocation. Two decisions follow:

- The engine lives on `LocalLlmApplication`, not on the service, so it survives unbind
  and rebind. Otherwise every app switch would pay a reload.
- The canonical client's `warmup()` performs package/signing verification and a bounded,
  authorized version transaction. That transaction may start non-downloading prewarm;
  merely acquiring a service binding never does.
- One lifecycle coordinator owns the native handle and serializes preparation,
  generation, unload, close and critical trim. A trim request waits for active native
  inference to finish before closing the handle; it never races `Engine.initialize()` or
  `Engine.close()` against generation.
- A process-owned scheduler bounds admission before that coordinator: one generation is
  active and at most two wait, across both Binder requests and the manager self-test.
  Priority affects only waiting order and never pre-empts a native call. Current v1
  requests all use the deterministic open-screen lane because the frozen request has no
  trusted execution-context field.
- Each callback Binder and in-flight record exists before scheduler submission.
  Cancellation or Binder death before/during synchronous registration either prevents
  admission or removes the admitted entry; terminal scheduler state releases that record
  and its death recipient exactly once.
- `onTrimMemory(TRIM_MEMORY_RUNNING_CRITICAL)` cancels process-owned preparation while
  preserving any resumable partial download, then schedules a coordinated unload. Paying
  a reload is better than having the process killed mid-generation.

Engine preparation and model acquisition are separate types. `LlmEngine.prepare()` and
`generate()` inspect only compatible artifacts already installed on disk. The Binder
service, authorized prewarm, status calls, and manager self-test hold only that interface,
so none can reach `ModelStore.ensureAvailable()`. Missing artifacts fail immediately as
frozen v1 `MODEL_NOT_READY`, without entering the transfer mutex or waiting behind an
owner download.

Only the manager's existing owner action holds the acquisition interface. One
application-owned job coalesces repeated taps, downloads at most the preferred artifact
when no compatible artifact exists, then calls installed-only preparation. Acquisition
does not iterate the backend fallback list.

The ID of the last build that initialized successfully is persisted in private app
preferences. On process recreation, a still-installed compatible proven fallback is tried
before other installed compatible candidates. Missing candidates are excluded from engine
preparation entirely. A missing artifact is not a backend failure and can never become
`UNSUPPORTED_DEVICE`; a backend failure may fall through only to another already-installed
compatible artifact. Neither failure starts a fallback download.

A fresh `Conversation` is created per request. Conversations are cheap and this keeps
state from leaking between the two client apps. Its LiteRT configuration has a hard
service-owned output-token ceiling, followed by terminal word-count validation; prompt
instructions are not treated as enforcement.

## Why there is no foreground service

Generation is **not** wrapped in a foreground service, and the app declares no
`foregroundServiceType` at all. Promoting a bound service is denied whenever the calling
client is itself in the background, because the system evaluates the *binding client's*
eligibility and then throws `ForegroundServiceStartNotAllowedException` into *this*
process, where the client cannot catch it. The `BIND_AUTO_CREATE` binding already keeps
this process alive for the duration of a request. A client that wants a stronger guarantee
runs its own foreground service around the call, and that state propagates over the
binding. See `docs/DECISIONS.md`.

## Model selection

`ModelCatalog.defaultFor(board)` inspects `Build.SOC_MODEL`. A chipset-specific NPU build
is preferred when it matches, because it is both faster and easier on the battery than
the GPU path; otherwise the portable GPU build is used. Every build is pinned to a
SHA-256 taken from the HuggingFace LFS metadata and verified after download.

## Model acquisition durability

Acquisition is an explicit owner-started manager action. Its application-owned job survives
screen recreation, coalesces repeated taps, and is independent of the native-engine
lifecycle lock so a missing-model client request never waits behind a multi-gigabyte
transfer. Critical memory trim cancels that job; durable process-death transfer and a
foreground-service handoff remain outside this Stage 1 boundary.

One coroutine-owned transfer coordinator serializes download, deletion, and pruning
mutations. Cancelling a transfer immediately cancels its active OkHttp call, including a
blocked connection or body read, then retains every safely written partial byte. A delete
request cancels the matching transfer, waits for file ownership to be released, and only
then removes the installed and partial artifacts; concurrent requests cannot write the
same partial independently.

Pruning is best-effort for ordinary filesystem failures, but never for coroutine
cancellation. Cancellation while pruning waits for ownership propagates through engine
preparation, which closes the just-created native engine instead of publishing a cancelled
load as ready.

Resume requests pin and validate `Content-Range`, accept multiple bounded 206 chunks with
strict forward progress, recover once from HTTP 416, and cap both response count and total
bytes. Space checks charge only the bytes still missing plus fixed headroom. Network,
HTTP/range, storage, incomplete body, checksum, and promotion failures remain separate
typed causes for later service-level classification. Progress is coalesced to changed
integer percentages while retaining monotonic initial and terminal states.

A complete partial is SHA-256 verified before promotion. Promotion uses an atomic
same-volume replacement, so the previous known-good target remains installed until its
replacement is both complete and verified. Cancellation or out-of-memory during
verification/promotion preserves the recoverable partial and never converts an
`OutOfMemoryError` into an ordinary acquisition failure.

Public status has two explicit owners. Native runtime status is durable; active owner
acquisition is a temporary overlay while the runtime is unloaded. A concurrent client
missing-model failure therefore cannot erase download progress, and acquisition completion
after atomic promotion cannot overwrite `INITIALISING` or `READY` published by a client
that already observed the new artifact.

## What is deliberately absent from version one

- No chat UI. LocalLLM is infrastructure; its screen manages the model.
- No analytics computation. Clients compute their own statistics.
- No network use beyond the model download.
- No storage of prompts or generated text. Nothing is persisted between requests.
