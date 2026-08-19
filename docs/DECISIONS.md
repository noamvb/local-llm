# Decisions

Durable choices and the evidence behind them. Newest first.

## 2026-08-19 — Prewarm the engine on bind, and measure where the time goes

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
