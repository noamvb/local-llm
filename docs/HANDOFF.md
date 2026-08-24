# Handoff

Last updated: 2026-08-23. LocalLLM 0.1.5 remains the released host. This document records
the latest repository state only; older release and device evidence remains in Git history.

## Current branch

`codex/localllm-manager-downloads` began from exact main
`5d537466fc9f7a48a5107d0b105c234f391bdf1a`, the merge of Stage 1 v1 service
reliability. The branch contains one coherent unreleased manager-acquisition foundation.
It has not been pushed, merged, released, installed, launched, or used to download a model.

## Completed work

- `LlmEngine.prepare()` and `generate()` now use installed compatible artifacts only.
  Binder status, authorized prewarm, Binder generation, and manager self-test hold only
  this interface and cannot call `ModelStore.ensureAvailable()`.
- A separate internal `ModelAcquirer` is wired only through
  `LocalLlmApplication.acquireAndPrepareModel()`, which is invoked by the manager's existing
  owner button. One application-owned job coalesces repeated taps, acquires at most the
  preferred artifact when no compatible artifact exists, then performs installed-only
  preparation.
- Missing installed artifacts throw `ModelNotInstalledException` and map to frozen v1
  `MODEL_NOT_READY` with owner guidance. They do not become `UNSUPPORTED_DEVICE`, enter
  ModelStore's transfer mutex, or wait behind an active owner download.
- Startup preserves the proven installed build first, followed by other installed
  compatible builds. Missing fallbacks are excluded from preparation, so acquisition or
  backend failure never automatically starts another multi-gigabyte download. Verified
  installed files remain intact on backend failure.
- `EngineStatusCoordinator` gives runtime and acquisition explicit ownership. Active
  acquisition overlays an unloaded runtime; a concurrent missing client cannot erase its
  progress, and late acquisition completion cannot overwrite `INITIALISING` or `READY`
  after atomic promotion.
- Owner cancellation propagates into the existing ModelStore cancellation boundary, which
  immediately cancels OkHttp and retains safely written partial bytes. Critical trim also
  cancels the owner job before coordinated engine unload.
- README, architecture, API guidance, and durable decisions now describe the installed-only
  request boundary and explicit owner acquisition.

No AIDL file, canonical client source, wire value, version, signing configuration,
production identity, or endpoint changed.

## Validation

Focused boundary gate, with JDK 17, Android SDK CLI, isolated Gradle home, and in-process
Kotlin:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
GRADLE_USER_HOME=/private/tmp/localllm-roadmap-20260823.pO7j0d/gradle-manager-downloads \
./gradlew --no-daemon :app:testDebugUnitTest \
  --tests 'com.noamv.localllm.ModelStoreTest' \
  --tests 'com.noamv.localllm.engine.EngineStatusCoordinatorTest' \
  --tests 'com.noamv.localllm.engine.OwnerModelAcquisitionCoordinatorTest' \
  --tests 'com.noamv.localllm.engine.ModelStartupPolicyTest' \
  --tests 'com.noamv.localllm.engine.PrewarmPolicyTest' \
  --tests 'com.noamv.localllm.service.ServiceFailureMapperTest' \
  --tests 'com.noamv.localllm.ui.ManagerViewModelSchedulerTest' \
  -Pkotlin.compiler.execution.strategy=in-process
```

Result: `BUILD SUCCESSFUL` in 1 minute 3 seconds. All 60 focused tests passed with zero
failures, errors, or skips. Coverage includes a real blocked ModelStore transfer versus a
nonblocking installed-artifact check, repeated owner taps, owner cancellation, resumable
partial preservation, runtime/acquisition status arbitration, installed/proven fallback
selection, missing-model v1 classification, prewarm policy, and manager self-test
separation.

Required full clean gate:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
GRADLE_USER_HOME=/private/tmp/localllm-roadmap-20260823.pO7j0d/gradle-manager-downloads \
./gradlew --no-daemon --rerun-tasks clean testDebugUnitTest assembleDebug lintDebug \
  -Pkotlin.compiler.execution.strategy=in-process
```

Result: `BUILD SUCCESSFUL` in 14 minutes 31 seconds; 100 tasks completed. All 191 JVM
tests passed: 146 app and 45 canonical client, with zero failures, errors, or skips. The
app debug APK and client debug AAR assembled. App lint reported 24 warnings and no errors;
client lint reported no issues.

The first focused attempt stopped before compilation because the throwaway worktree had no
SDK pointer. It was rerun with the documented `ANDROID_HOME` above and passed; no
`local.properties` was added or changed.

## Validation boundary and unfinished work

This is static/JVM/assembly/lint evidence only. No Android instrumentation, emulator,
physical device, real LiteRT initialization or generation, Binder process race, real model
network transfer, checksum/promotion on a multi-gigabyte artifact, or physical
cancel/resume scenario was run. No ADB or phone action was authorized or performed.

Foreground-service ownership and durable process-death transfer, mobile-network policy and
override, delete/repair/retry UI, benchmark and signer/drift UI, v2 role storage, canonical
client copies, versions, releases, and device/model downloads remain out of scope.

## Relevant files

- `app/src/main/java/com/noamv/localllm/engine/LlmEngine.kt`
- `app/src/main/java/com/noamv/localllm/engine/ModelAcquirer.kt`
- `app/src/main/java/com/noamv/localllm/engine/LiteRtEngine.kt`
- `app/src/main/java/com/noamv/localllm/engine/EngineStatusCoordinator.kt`
- `app/src/main/java/com/noamv/localllm/engine/OwnerModelAcquisitionCoordinator.kt`
- `app/src/main/java/com/noamv/localllm/LocalLlmApplication.kt`
- `app/src/main/java/com/noamv/localllm/service/ServiceFailureMapper.kt`
- `app/src/main/java/com/noamv/localllm/ui/ManagerViewModel.kt`
- focused tests beside those boundaries and `app/src/test/java/com/noamv/localllm/ModelStoreTest.kt`
- `README.md`, `docs/ARCHITECTURE.md`, `docs/API_CONTRACT.md`, and `docs/DECISIONS.md`

## Recommended next action

Review the single local commit and its full diff, then open the coherent feature pull
request against `main`. CI and later sandbox/device acceptance must preserve the evidence
boundary above; merging or releasing does not authorize installation, launch, ADB, or a
real model download.
