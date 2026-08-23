# Decisions

Durable choices and the evidence behind them. Newest first.

## 2026-08-23 — Bound v1 service work before native inference

**Decision.** Put every cross-app v1 generation and the manager self-test through one
process-owned bounded priority scheduler: one active request and at most two waiting.
Register the in-flight owner and callback
Binder death recipient before synchronous admission. Linearize cancellation/death with
registration, keep one callback terminal owner, remove waiting work immediately, expire
stale queued work, and return explicit `BUSY` when capacity is exhausted. Native work is
not pre-empted. Because the frozen v1 JSON has no trustworthy execution-context field,
all v1 tasks use the open-screen priority; live-assistant/background priority remains
available only to a future explicit contract.

Validate the complete raw and decoded request before admission: exact contract/task
shape, nonblank bounded strings and UTF-8 bytes, fact counts and fields, strict date
pairs/ranges, word limits, control/format characters, and reserved structured output.
`PERIOD_SUMMARY` and `NUDGE` require a period; `NUDGE` also requires
`lockScreenSafe=true`. The documented v1 health/new-number opt-outs remain accepted for
compatibility, while current clients retain strict defaults.

Set LiteRT-LM's request-sensitive `maxOutputToken` with a hard ceiling, then separately
bound accumulated characters and validate nonblank terminal word count. Map cancellation,
OOM, exhausted backends, acquisition subcategories, initialization, queue saturation,
invalid input and unexpected failures to the existing seven v1 codes with sanitized
category messages. V1 has no retryable bit, so adding one waits for a versioned contract.

**Why.** Serializing only inside the native engine allowed unbounded Binder coroutines to
wait while retaining callback and personal-fact payloads. Prompt-only limits did not stop
oversized input or overlong output, and a catch-all `INTERNAL` response erased actionable
model acquisition and backend distinctions. Inferring priority from `clientId`, task or
content would let descriptive, spoofable data become scheduling authority.

**Consequences.** The existing engine lifecycle coordinator remains the sole native
handle owner; prepare, generation, trim, unload and OOM recovery are not duplicated.
Queue priority changes waiting order only. The AIDL transaction layout and copied JSON
shape remain unchanged. Retryability is necessarily advisory at v1, and physical LiteRT,
Binder-death and memory-pressure behavior still require device validation.

## 2026-08-23 — Serialize model-file ownership and make resume protocol-strict

**Decision.** Give one coroutine-owned coordinator exclusive access to model transfers,
deletion, and pruning. Cancel the live OkHttp call with its owning coroutine, retain useful
partial bytes, continue across bounded valid 206 chunks with strict progress, and verify
the pinned size and SHA-256 before atomically replacing any installed model.

**Why.** The previous range implementation issued only one response per owner action,
could wait for an OkHttp timeout after coroutine cancellation, reported every 64 KiB
fragment, and allowed delete/prune to race an active transfer. Protocol, response-close,
local-file, checksum, and promotion failures were not consistently distinguishable.
These gaps made a multi-gigabyte recovery path both fragile and difficult to classify
without risking a known-good installed artifact.

**Consequences.** A valid partial reduces the free-space requirement to its remaining
bytes plus headroom. Invalid ranges, oversized/short bodies, repeated 416 responses, and
excessive partial-response loops stop deterministically without promoting the artifact.
Network reads and closes are reported separately from local open/write/sync failures.
Delete is suspending: it cancels a matching transfer and waits for ownership rather than
blocking the caller's thread. Progress is monotonic and percentage-coalesced. A verified
replacement is promoted atomically; checksum, cancellation, promotion failure, and OOM
preserve the previous installed target as applicable, and OOM remains an OOM for engine
recovery rather than being wrapped as an acquisition exception. Ordinary pruning failure
remains best-effort after a successful initialization, while pruning cancellation is
re-thrown so a cancelled prepare cannot publish or retain the new native engine.

## 2026-08-23 — Persist a proven fallback and give one coordinator the native engine

**Decision.** Persist the ID of the last compatible build that initialized successfully,
try that still-installed build before a missing preferred artifact after process
recreation, and route preparation, generation, unload, close, critical trim, and
out-of-memory recovery through one mutex-owned native-engine lifecycle.

**Why.** A working CPU fallback became invisible after close or process death because
startup status considered only the preferred build. That could report no model and pay for
another multi-gigabyte preferred download. Separately, trim and close could invalidate a
native handle while preparation or generation still owned it, and a newly constructed
`Engine` leaked when `initialize()` threw.

**Consequences.** Installed compatible candidates precede missing ones. Acquisition and
backend-initialization failures remain different categories: a network, storage or
checksum failure stops acquisition and cannot become `UNSUPPORTED_DEVICE`, while a
verified artifact whose backend fails may fall through to another compatible backend.
Critical trim cancels process-owned preparation but waits for native generation to reach a
safe boundary before unload. OOM closes and forgets the poisoned handle while retaining
the verified artifact and proven-build preference for a clean retry. Model files are
pruned only after another build has initialized successfully.

## 2026-08-23 — Authenticate every Binder peer and make v1 delivery finite

**Decision.** Keep Android's signature-gated permissions as defense in depth, and add
reciprocal runtime identity checks. Before every bind, the canonical v1 client inspects
the pinned LocalLLM service class and authenticates an approved current signer or
Android-authenticated signing lineage. LocalLLM derives the caller independently from
`Binder.getCallingUid()` for every transaction and accepts only an approved production
package-plus-signing-lineage pair among the packages Android reports for that UID.
Caller-provided `clientId` values remain descriptive metadata and never grant access,
priority, quota, cancellation authority, or attribution.

The client negotiates `getApiVersion()` on the same binding it uses and accepts only
service API versions explicitly declared v1-capable, rather than treating every larger
integer as compatible. Bind, engine status,
first response and total generation each have a finite deadline. Null binding/Binder,
binding death, package replacement, service process death, and mismatched callback
request IDs terminate exactly once. Cancellation before the synchronous request call
returns its ID is retained and forwarded once that ID arrives. The service links each
callback Binder to death so a dead client cancels its native request rather than occupying
the serial engine queue.

A simple bind is inert. Only an exact authorized transaction may initiate engine work;
`getApiVersion()` starts the existing non-downloading prewarm after caller authorization,
while an authorized generation request still prepares on demand. One bind deadline spans
package/signing resolution and service connection. Generation's version negotiation uses
the status deadline. A CAS-owned `OPEN`/`SUBMISSION_BEGUN`/`STOPPED` gate linearizes
submission against timeout or collector cancellation. An already-entered synchronous
Binder request cannot be interrupted, so a late returned ID is cancelled immediately.
Bind connection, deadline, death, cancellation, and successful delivery similarly share
one synchronized state owner, preventing an already-failed bind from later resuming a dead
session. Registration cleanup has its own two-phase owner: a terminal outcome may request
cleanup at any time, but the actual unbind is delivered exactly once only after the
synchronous `bindService()` call returns. A timeout or cancellation immediately before or
during registration therefore cannot spend its only unbind before the connection exists.

The public streaming API emits typed progress, full replaceable draft snapshots,
authoritative completion, and terminal failure. `onComplete.text` replaces the streamed
draft, including completion-only responses; a conflated channel keeps the latest event,
and the queue that can form before request-ID assignment is capped at 64. The deprecated
string API emits only authoritative completion once so existing append-based collectors
remain correct. Contract v1 explicitly rejects non-null `resultSchema` because it does
not implement constrained structured decoding.

Callback admission and delivery do not use a second terminal flag outside the callback
gate. One synchronized owner performs request-ID validation, first-response admission and
timer cancellation, nonterminal `trySend`, terminal selection and checked terminal
`trySend`, and flow closure. This gives an admitted callback and a concurrent generation
deadline or Binder death one explicit linearization order. Completion claims terminal
ownership before publishing authoritative `onComplete.text`; a later failure cannot close
or overwrite it. If a draft or progress value wins admission first, a later failure may
replace that advisory value in the conflated channel and becomes the sole terminal event.

**Why.** The current outbound client trusts only a package name, while the inbound
permission intentionally recognizes multiple certificates. That is broad enough for an
approved signer to claim another client's `clientId`, and a wrong same-package LocalLLM
installation could receive facts from a client that checks only the package name.

**Signer evidence.** Values were read on 2026-08-23 with Android build-tools 36.0.0
`apksigner verify --print-certs` from independently downloaded current release APKs:

| Identity | Artifact | Certificate SHA-256 |
| --- | --- | --- |
| LocalLLM | v0.1.5 | `f1f2632b76d0edbd40c839a86c7d6eec63ec74f3d5095726a6f676ba1ad3b95d` |
| Poop Schedule | v1.3.0 | `98198cd143954c564faa0cc6408918edbc1a2311f2f4817cfe89223923a55cde` |
| Cannsheet Mobile | v1.6.2 | `a9787249b106d98a421ed839789361a45753e367e243820d10d2f3a09708665e` |

**Consequences.**

- A different APK installed under `com.noamv.localllm` cannot receive tracker facts unless
  Android proves it descends from the pinned LocalLLM signing lineage.
- An app signed by an otherwise known certificate cannot call the service under a new
  package name. Shared UIDs are rejected because Binder cannot identify which package on
  the UID originated a transaction.
- Legitimate signing-key rotation remains possible through Android's authenticated
  signing history; unrelated current or multi-signer packages fail closed.
- Package replacement and signer mismatch make the peer unavailable before facts are read
  or transmitted.
- The AIDL method order and signatures remain byte-for-byte compatible. The canonical
  client is now built as a minSdk-24 Android library module so copy-only source cannot
  silently stop compiling for the oldest consumer. It fails closed below the API-31 host
  minimum before using newer signing APIs.
- `scripts/localllm_v1_client.sh` is the sole vendoring/check command. A checked canonical
  digest and LocalLLM CI protect the in-repository copy; client CI gains the matching
  cross-repository drift check when each merged canonical copy is vendored. Copying fails
  if canonical bytes differ from `HEAD`; consumer verification authenticates the recorded
  repository, exact commit, and digest instead of trusting a working-tree hash alone.
- New downstream collectors replace their draft text on each `generateEvents()` `Draft`
  and then replace it with `Complete.text`. Existing append-based `generate()` collectors
  remain behaviorally safe because that deprecated adapter emits completion only.

## 2026-08-23 — Add a separate aggregate-only version-two assistant protocol

**Decision.** Add new version-two assistant and fact-provider Binder interfaces beside the
existing version-one summary interface. Client providers accept only a bounded typed
aggregate-query grammar and return evidence-bearing facts calculated by the owning app.
There is no raw-row or arbitrary-expression escape hatch and no write-shaped operation.

**Why.** Expanding the version-one transaction layout would destabilize already installed
clients. An assistant needs capabilities, clarification, provider queries, citations,
terminal validation, history pagination, and partial-source states that do not fit the
one-shot summary API. Keeping providers aggregate-only preserves both the data boundary
and the rule that the model never calculates.

**Consequences.** The production grammar freezes only after deterministic-parser and
FunctionGemma feasibility tests exercise the same proposed shapes. Providers implement
the frozen contract rather than inventing fields locally. Unknown JSON fields remain
forward-compatible; unknown enum values are explicit `UNKNOWN` values or an incompatible
peer error. See `ASSISTANT_ARCHITECTURE.md`.

## 2026-08-23 — LocalLLM alone owns device-only shared assistant history

**Decision.** LocalLLM persists multiple conversations, cited evidence, provenance,
terminal results, and a separate automatic-insights feed in its own Room database. Both
approved clients may read the complete bounded archive, but only LocalLLM's owner UI may
delete a conversation or clear history. History has no backup, transfer, cloud, sharing,
or export path and remains until manual deletion.

**Why.** One history owner gives both clients an identical archive without duplicating
sensitive text and deletion state. Client-owned copies would drift and make it unclear
which app controls retention.

**Consequences.** Question, cited evidence, terminal text, warning, and terminal state are
one atomic transaction. Cancellation or timeout cannot become success. Validated prose is
never evidence for a later turn; follow-ups re-fetch current facts. Failed generated text
is intentionally retained only as escaped inert text behind a prominent warning and is
excluded from answers, evidence, future context, navigation, cards, notifications, and
actions.

## 2026-08-23 — Cross-app answers require explicit permission and stay side by side

**Decision.** A normal turn is restricted to the initiating app. LocalLLM may query both
providers only after deterministic source selection confirms explicit wording or an
explicit owner choice. Facts from the two apps remain separately labelled, and the writer
cannot claim correlation, causation, medical meaning, or behavioral effect.

**Why.** Router output is not authorization, and combining health-adjacent and consumption
data can create both an unexpected privacy expansion and unsupported conclusions.

**Consequences.** An ambiguous source prompts clarification. A missing explicitly requested
provider yields current available facts plus a deterministic missing-source warning, never
saved historical evidence. Single-app conformance tests prove the other provider is not
bound. Every validated sentence cites current supplied fact IDs and every number is
grounded in that sentence's cited facts.

## 2026-08-23 — Separate router and writer roles with a deterministic shipping fallback

**Decision.** Treat FunctionGemma as an optional, tuned English router and the existing
Gemma E2B model as the writer. The router may propose the typed aggregate grammar but may
not execute it. Deterministic parsing, validation, intent choices, and clarification are
the required shipping fallback if FunctionGemma misses any accuracy, safety, licensing,
artifact, latency, memory, or thermal gate.

**Why.** Function routing and prose writing have different failure modes. A small router
can reduce ambiguity only if it is evaluated against the exact function vocabulary; it is
not authority and it is not a general assistant. Making the Android feature depend on an
unproven tuned artifact would block safe delivery.

**Consequences.** Model storage, preparation, status, and rollback become role-aware. The
initial runtime keeps one role resident and schedules one native inference at a time.
Simultaneous residency remains disabled until intended-phone evidence proves it safe.
Training/evaluation inputs are synthetic and reproducible; saved owner questions stay on
device as private evaluation only and never become training data.

## 2026-08-23 — Clients own automatic insight execution and notifications stay neutral

**Decision.** Cannsheet and Poop Schedule each own one daily post-fresh-sync WorkManager
job. Fixed activity-only highlights and current-30-versus-prior-30 comparisons bypass the
router, use the source's authoritative timezone, and save into a separate LocalLLM feed.
Public success and failure notifications contain neutral fixed text and no generated
prose or personal fact.

**Why.** Android foreground-execution eligibility belongs to the background caller, not a
bound LocalLLM service. Fixed scheduled queries gain nothing from probabilistic routing,
and lock-screen notification content must not disclose values, products, symptoms, dates,
or inactivity.

**Consequences.** Eligibility and idempotency are client-owned and tested. Missing models,
disabled access, stale data, blocked notifications, or battery restrictions skip
inference without repeated alerts. Releases remain staged: LocalLLM first, then compatible
clients; publication does not authorize production installation, launch, data access, or
device actions.

## 2026-08-19 — Split time-to-first-token telemetry and track download status

**Decision.** `EngineTimings` records both total time-to-first-token (`lastTimeToFirstTokenMillis`) and the post-preparation prefill segment (`lastPrefillMillis`), alongside a flag indicating whether the request triggered a model download (`lastRequestDownloaded`). Logcat and the manager screen distinguish pure prefill latency from download and model initialisation overhead.

**Why.** The initial TTFT telemetry measured the single composite span from request arrival to the first emitted token fragment (`now - startedAt`). For a cold start requiring a model download, this produced samples exceeding 300 seconds labeled simply as "(cold start)", masking the difference between network transfer, GPU initialization, queue waiting, and token prefill. Splitting the post-preparation segment and explicitly flagging downloads ensures that latency telemetry directly informs backend performance and NPU evaluation without being distorted by multi-gigabyte transfers.

**Consequences.**
- `LiteRtEngine.generate` samples download state prior to `prepare()`, timestamps post-preparation completion, and logs `ttft warm=... downloaded=... totalMs=... prefillMs=...`.
- `EngineStatusPresentation.lastResponseText` formats download-inclusive requests as `"(cold start, including download)"` (or `"(including download)"`) rather than misrepresenting network transfers as pure inference latency.
- Internal telemetry structures (`EngineTimings`) remain app-internal and do not alter AIDL contracts.

## 2026-08-19 — Prewarm the engine on bind, and measure where the time goes

**Superseded security detail (2026-08-23).** A manifest-permission bind is no longer
allowed to prewarm by itself. The canonical warmup still starts the same non-downloading
work, but only through an exact caller-authorized `getApiVersion()` transaction. The
timing and process-lifetime rationale below remains historical context.

**Decision.** `InferenceService` initiates non-downloading model prewarming (`LocalLlmApplication.prewarmModel`) on `onBind` and `onRebind`, and `onUnbind` returns `true`. Model load duration and time-to-first-token (TTFT) metrics are measured in `LiteRtEngine` and surfaced on the manager screen and in logcat. `LocalLlmClient` gains an idempotent `warmup(): AutoCloseable` handle.

**Why.** Initialising the 2 GB model via LiteRT-LM takes multiple seconds (measured around four seconds on a Galaxy Z Fold 7). Because `InferenceService.requestInsight` previously prepared the engine inline upon request arrival, users waited for the entire initialization cost on every insight generation. Moreover, client apps checked `engineStatus()` and immediately unbound before issuing `generate()`, leaving a zero-binding window where Android could reclaim the process.

Prewarming on bind starts model initialization the moment a client connects to check status or render the screen, overlapping initialization with the user reading the UI.

**Consequences.**
- Prewarm is strictly conditioned on `status.modelDownloaded == true` and `state == EngineState.MODEL_MISSING` (`PrewarmPolicy.shouldPrewarmOnBind`). A service bind never triggers a network download.
- AIDL interfaces and cross-app contracts remain byte-identical; diagnostic timings are app-internal (`EngineTimings`).
- Logcat records duration and token count telemetry only; prompt and generated text are never logged.
- The manager screen displays the most recent model load duration/backend and generation time-to-first-token (distinguishing warm from cold starts).

**The general lesson.** An expensive resource cached "for the process lifetime" is not cached at all when the process does not survive; the fix is to start the work earlier, not to hold it longer.

## 2026-08-18 — An interrupted preparation never discards the model

**Decision.** `LiteRtEngine.prepare()` rethrows `CancellationException` instead of treating
it as a failed build, and no longer deletes a model file when a build fails to start. The
download runs on an application scope (`LocalLlmApplication.prepareModel`) rather than in
`ManagerViewModel`'s `viewModelScope`.

**Why.** The loop over candidate builds caught `Throwable`, and `CancellationException` is a
`Throwable`. The download ran in a ViewModel scope tied to `MainActivity`, so leaving the
app cancelled it, the catch fired, and `ModelStore.delete` removed the model *and* its
`.part` file. The next attempt started again from zero. `ModelStore` supports resume — it
writes a `.part` and sends a `Range` header — and the catch-all defeated it entirely.

Observed on a Galaxy Z Fold 7: LocalLLM held 2.08 GB (the 68.62 MB APK plus the
2,008,432,640-byte GPU model), and after the Insights path ran it held 70.14 MB. Neither
the model nor a `.part` remained. A killed process leaves the `.part` behind, so only the
explicit delete explains both files being gone.

The same failure hid the insight cards in Poop Schedule and Cannsheet. Both gate on
`EngineStatus.modelDownloaded`, so a deleted model makes the card render nothing — silently
and by design. The `UNSUPPORTED` status also let `modelDownloaded` fall back to its `false`
default, which kept reporting "no model" for the rest of the process lifetime even when the
file was intact; it now reports the truth.

**Consequences.**

- A build that cannot start keeps its file. The eager delete existed so a doomed retry
  would not repeat, but a start failure can be transient — a busy GPU, a low-memory
  moment — and paying a multi-gigabyte download to discover that is the worse trade.
  `pruneExcept` still reclaims superseded files once some build has actually started.
- Worst case two candidate models coexist (~4.6 GB) until one starts. `hasRoomFor` already
  requires the full size plus 250 MB free, so this cannot fill the volume.
- Closing the manager screen no longer stops a download. The work is owned by the process,
  not the Activity.
- `ModelStore`'s primary constructor now takes the models directory, with a `Context`
  overload for the real path, so `ModelStoreTest` can exercise resume against a temporary
  folder and an in-memory OkHttp interceptor.

**The general lesson.** `catch (Throwable)` around cancellable work is a data-loss bug, not
a robustness measure. Cancellation is a normal control-flow signal and says nothing about
whether the thing being cancelled was healthy.

## 2026-08-18 — The inference service is not a foreground service

**Decision.** `InferenceService` no longer calls `startForeground` while serving a bound
request. The `foregroundServiceType`, the `<property>` subtype and both
`FOREGROUND_SERVICE*` permissions are removed.

**Why.** The original design promoted the service for the duration of generation so a long
request would survive. That is not merely unnecessary, it is a crash waiting for the first
background-initiated request.

An app targeting API 31+ may not start a foreground service from the background. For a
bound service the system does not evaluate *this* app's state; it walks the binding clients
and re-runs the check against them (`ActiveServices.canBindingClientStartFgsLocked`,
yielding `REASON_FGS_BINDING`). Binding *propagates* a client's existing eligibility, it
does not create any. A client running an ordinary WorkManager job has none, so the
promotion is denied.

The denial surfaces in the worst possible place. `ForegroundServiceStartNotAllowedException`
is thrown into **this** process, gated on **this** app's `targetSdk`, so a `try/catch` in
the client catches nothing. This service would die mid-generation and the client would
observe only `onServiceDisconnected` and a `DeadObjectException` — a symptom that looks
nothing like the cause. Android would also post its own "restricted" notification, so the
owner gets system noise instead of a result.

Promotion was never needed. While a client holds a `BIND_AUTO_CREATE` binding this process
is pulled up by that binding, so it is neither frozen nor a near-term low-memory kill
candidate for the ten to sixty seconds a generation takes.

**Consequences.**

- A client that needs a stronger guarantee runs **its own** foreground service around the
  request; that state then propagates over the binding. Ownership of the foreground
  service belongs with the app the user is actually interacting with.
- `setExpedited` on WorkManager does not help. On API 31+ it is a JobScheduler expedited
  job, not a foreground service, and confers no ability to start one.
- There is no bind flag that grants this. `BIND_ALLOW_FOREGROUND_SERVICE_STARTS_FROM_BACKGROUND`
  is `@hide`, `@SystemApi`, deprecated and permission-gated.
- Verified on a Galaxy Z Fold 7 after the change: the Poop Schedule insight card still
  generates, with no crash and no denial in logcat.

**The general lesson.** For a bound service, background restrictions are evaluated against
the caller, not the callee, but the exception is delivered to the callee. A service that
promotes itself on behalf of an arbitrary client is holding a loaded gun pointed at itself.

## 2026-08-18 — Use signature|knownSigner, because the apps do not share a key

**Decision.** The inference permission is `signature|knownSigner` with an
`android:knownCerts` array of client certificate digests, not a plain `signature` check.

**Why.** The original design assumed all three apps shipped from one personal keystore.
That assumption was never verified and is false. Reading the actual APKs:

| App | Certificate | SHA-256 |
| --- | --- | --- |
| Poop Schedule 1.0.1 | `CN=Poop Schedule` | `98198cd1…` |
| Cannsheet Mobile 1.3.4 | `CN=Android Debug` | `a9787249…` |
| LocalLLM | its own key | — |

Three different certificates. A plain `signature` permission would have been granted to
**none** of the intended clients. The bug was invisible during development because both
apps under test were built on the same machine and therefore shared one debug key; it
would have surfaced only after publishing, as a card that silently never appeared.

`knownSigner` (API 31, this app's minimum) grants the permission when the requesting app's
signing lineage matches any listed digest, which is exactly the multi-keystore case here.
Verified on device: `prot=signature|knownSigner`.

**Consequences.**

- Adding a client is a LocalLLM change: read the digest from its *published* APK, add it
  to `known_signers.xml`, ship a new LocalLLM. An installed one cannot learn a digest.
- Digests must come from the published artefact. A locally built APK is signed with the
  developer's debug key and will report a different digest than the release.
- If Cannsheet ever moves to a real release keystore its digest changes, and LocalLLM
  must be updated in the same cycle or the card disappears.

**Separately observed, not changed here.** Cannsheet Mobile's *published* release APK is
signed `CN=Android Debug`. Its `release-apk.yml` applies release signing only when the
keystore environment variables are present and otherwise falls back to debug signing
silently. Debug keystores are unpassworded and machine-local, so losing that file makes
in-place updates impossible forever. That is the repository owner's call to make, and out
of scope for this project, but it is why Cannsheet's digest above looks the way it does.

**The general lesson.** Verify the certificate, do not infer it from who built the app.
The check is one command against the published artefact.

## 2026-08-18 — Pin kotlinx-coroutines to 1.11.0

**Decision.** `kotlinxCoroutines = "1.11.0"`, which is newer than the version
`litertlm-android` declares in its own POM.

**Why.** Any older version crashed the process the instant a generation finished:

```
java.lang.NoSuchMethodError: No static method
  close$default(Lkotlinx/coroutines/channels/SendChannel;Ljava/lang/Throwable;ILjava/lang/Object;)Z
  at com.google.ai.edge.litertlm.Conversation$sendMessageAsync$1$1.onDone(Conversation.kt:452)
```

LiteRT-LM's `sendMessageAsync` Flow bridge calls `close$default` as a **static method on
the `SendChannel` interface itself**. Kotlin only emits it there when the library is
compiled with the `jvm-default` behaviour that became standard in Kotlin 2.2; older
coroutines releases put that synthetic in `SendChannel$DefaultImpls` instead. LiteRT-LM
0.16.1 is built with Kotlin 2.2.21 and needs the newer layout.

Both 1.10.2 and 1.9.0 fail identically. `litertlm-android:0.16.1` *declares*
`kotlinx-coroutines-android:1.9.0`, so trusting its POM and pinning down made things no
better — the declared version is simply not the one it was built against. Only 1.11.0
works, verified on a Galaxy Z Fold 7.

**Consequences.**

- Do not "fix" a coroutines conflict by matching the POM here; the POM is misleading.
- The symptom is deferred and points at the wrong place. Inference completes and tokens
  are produced; the process dies during channel teardown, which reads like a bug in the
  streaming code rather than a dependency mismatch.
- If a future LiteRT-LM bump reintroduces this, the durable escape is to stop using its
  Flow bridge and drive the plain `MessageCallback` overload through our own
  `callbackFlow`. That removes the dependency on its internal coroutines ABI entirely.

**The general lesson.** A prebuilt library that bridges native callbacks into coroutines
is bound to the coroutines ABI it was *compiled* against, which its published metadata may
not name. When the failure is a `NoSuchMethodError` on a `$default` synthetic, the
question is which Kotlin version generated the caller, not which version the POM requests.

## 2026-08-18 — Ship the GPU model build, not the NPU one

**Decision.** The app selects the portable GPU build of Gemma 4 E2B. The chipset-specific
NPU build stays in the catalogue but is selected only when vendor dispatch libraries are
actually bundled in the APK.

**Why.** The Galaxy Z Fold 7 reports `ro.soc.model` = `SM8750`, and `litert-community`
publishes `gemma-4-E2B-it_qualcomm_sm8750.litertlm` built for exactly that SoC. Selecting
it on the strength of the chipset alone looked correct and was not. The model downloaded
and verified, the engine registered an `NpuAccelerator`, and then initialisation failed
several seconds later during inference warm-up:

```
E litert: [litert_dispatch.cc:122] No dispatch library found in .../lib/arm64
E litert: [dispatch_delegate.cc:131] Failed to create a dispatch delegate kernel:
          No usable Dispatch runtime found
RET_CHECK failure (llm_litert_npu_compiled_model_executor.cc:1558)
          Inference warmup run for LLM (prefill) failed.
```

LiteRT-LM dispatches NPU work through Qualcomm libraries (`libQnnHtp*.so`) that are **not**
part of `litertlm-android`. Google distributes them only inside the QAIRT SDK, to be built
with Bazel and bundled by the app. There is no Maven artifact. Taking that on would mean
vendoring several hundred megabytes of vendor binaries, a Bazel toolchain, and a
redistribution licence question, to accelerate a feature that writes eighty words.

**Consequences.**

- `ModelCatalog.defaultFor` takes an `npuDispatchAvailable` flag, and
  `LiteRtEngine.hasNpuDispatchLibraries` inspects `nativeLibraryDir` for the vendor
  libraries. The SoC is necessary but not sufficient.
- `ModelCatalog.fallbacksFor` gives an ordered chain, NPU → GPU → CPU, and `prepare()`
  walks it, so a backend that cannot start degrades instead of failing the feature.
- A build that fails to start keeps its model file; `ModelStore.pruneExcept` removes
  superseded files once some build has actually started. These files are 2-3 GB, so an
  orphan is a real amount of the owner's storage — but discarding one eagerly turned out
  to cost far more than it saved. See the entry below.
- If NPU is ever wanted, the work is: obtain QAIRT, build
  `@litert//litert/vendors/qualcomm/dispatch:dispatch_api_so`, bundle the result in
  `jniLibs`, and the existing detection will select the NPU build on its own.

**The general lesson.** Probe for the capability, not for the hardware that usually implies
it. The failure surfaced only at warm-up, well after everything that could be checked
cheaply had already succeeded.

## 2026-08-18 — LiteRT-LM rather than MediaPipe LLM Inference

**Decision.** Build on `com.google.ai.edge.litertlm:litertlm-android`.

**Why.** The MediaPipe LLM Inference API (`com.google.mediapipe:tasks-genai`) is in
maintenance-only mode and Google directs new work to LiteRT-LM, which is where GPU/NPU
acceleration, constrained decoding and tool use are being developed. Starting on
`tasks-genai` would have meant starting on a dead end.

## 2026-08-18 — Clients send facts, never rows

**Decision.** The cross-app contract accepts a list of pre-formatted `Fact` values. It has
no way to send records, and the model is instructed to use no number it was not given.

**Why.** Two independent reasons converge. A 2B-parameter quantised model is a capable
writer and an unreliable arithmetician, so any figure it derives is a figure that can be
wrong while sounding right. And binder caps all in-flight transactions for a process at
roughly 1 MB, which a year of raw rows would breach. Both point the same way, so the
constraint is built into the contract rather than left to callers' discretion.
