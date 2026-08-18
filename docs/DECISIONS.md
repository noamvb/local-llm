# Decisions

Durable choices and the evidence behind them. Newest first.

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
