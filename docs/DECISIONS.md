# Decisions

Durable choices and the evidence behind them. Newest first.

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
- A build that fails to start has its model file deleted, and `ModelStore.pruneExcept`
  removes superseded files after a successful start. These files are 2-3 GB; an orphan is
  a real amount of the owner's storage.
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
