# Handoff

Last updated: 2026-08-23. LocalLLM 0.1.5 remains the released host. This document records
the current unreleased feature branch only; historical release and device evidence remains
in Git history.

## Current branch and scope

`codex/localllm-foreground-transfer` began from exact remote `main`
`c1e7721d0054d899c1f5bdbc450a4d42912ec96e` in the isolated worktree
`/private/tmp/localllm-foreground-transfer.Zzlr6a`.

This branch is the coherent Stage 1 user-started foreground model-transfer change. It has
not been pushed, opened as a pull request, merged, versioned, tagged, released, installed,
launched on a device, connected through ADB, or used for a real model download. No
production app, data, endpoint, signing configuration, or external repository was changed.

The repository has no `docs/PROJECT_STATE.md`. Current state is therefore recorded in the
repository's existing canonical README, architecture, API contract, decisions, and this
handoff.

## Implemented behavior

### Explicit foreground ownership

- Only a visible owner action in LocalLLM's manager calls
  `ModelTransferService.start(...)`. Binder status, authorized prewarm, generation,
  manager self-test, simple binding, boot, service restart, null-intent recreation, and
  process recreation have no acquisition entry point.
- The private non-exported service declares `foregroundServiceType="dataSync"` plus the
  base and data-sync foreground-service permissions. It calls `startForeground(...)`
  before model-directory, engine/status, network, or transfer work.
- The service owns the actual acquisition coroutine and cancellation boundary. Closing or
  recreating the manager screen does not cancel it. Repeated explicit starts coalesce onto
  the active session and cannot widen its one-run network policy.
- `START_NOT_STICKY`, rejection of null/unknown/retry commands, and a five-hour internal
  deadline prevent process death or platform retry from silently restarting a transfer.
- Both Android data-sync timeout callbacks route through synchronous cancellation,
  foreground removal, and `stopSelf` fenced by the latest `startId`. Service destruction
  cancels its owned job and retains safe partial bytes.
- Foreground lifetime ends after SHA-256 verification and atomic promotion. Model
  initialization/inference is deliberately outside the `dataSync` lifetime.
- A run token/session ID fences progress, terminal state, cleanup, and late events. A
  terminal or stale event cannot overwrite a newer/terminal session.

### Whole-transfer network-cost boundary

- Default admission requires the exact active Android `Network` to have `VALIDATED`,
  `INTERNET`, `NOT_METERED`, and Wi-Fi transport.
- The owner may confirm a one-transfer mobile/metered override. It relaxes only Wi-Fi and
  unmetered requirements, never validation or internet capability, and it is not persisted.
- Policy mismatch returns immediately as typed manager state. The service never waits
  indefinitely for a hidden network constraint.
- OkHttp is pinned to the admitted Android `Network` through its socket factory and DNS,
  with transparent `retryOnConnectionFailure` disabled.
- Every bounded HTTP operation revalidates the same lease. More importantly, the
  lease-bound `Call.Factory` makes call creation atomic with the one-way policy fence and
  registers every call under that fence. Invalidation synchronously cancels every call
  registered by the run. If invalidation wins, the delegate factory is never reached; if
  creation wins, invalidation observes and cancels the call before returning.
- Network callbacks close the fence on their callback thread but marshal component/status
  work to the service's main-confined scope. Admission is rechecked only after callback
  registration ownership is stored, closing the inspection-to-registration race.
- Registration, admission, pinned-factory, model/status, and other post-foreground setup
  exceptions route through one typed terminal-failure and exact session cleanup boundary;
  they cannot escape `onStartCommand` or strand an active manager state.
- A per-request policy exception wrapped by `ModelAcquisitionException` is recovered from
  a bounded cause chain and remains the exact `POLICY_BLOCKED` reason. A later monitor
  callback is idempotent and cannot replace it with generic `FAILED`.

### Resume, verification, and artifact safety

- `ModelStore` keeps its original source-compatible public
  `ensureAvailable(build, onProgress)` entry point and adds an internal transport-aware
  entry point for the foreground owner.
- A valid complete `.part` file takes a local-only verification/promotion path. The service
  neither requires nor acquires a network lease, and its no-network call factory fails if
  an HTTP call is unexpectedly attempted.
- Missing bytes use existing strict resume semantics: remaining-space calculation,
  validated ranges, bounded 206 response loops, HTTP 416 recovery, maximum-size checks,
  SHA-256 verification, and atomic promotion.
- Cancellation immediately reaches both the service-owned job and registered OkHttp calls.
  Safely written partial bytes remain available for a later explicit owner action.
- A known-good installed artifact is never deleted before a replacement verifies and
  atomically promotes. Current single-writer acquisition still refuses to replace an
  installed compatible fallback merely because it is not the preferred build.
- Transfer stages now distinguish downloading, verifying, and installing. Exact byte state
  includes expected size, retained partial at start, current available bytes, remaining
  bytes, and cumulative response-body bytes transferred during this run.
- Cumulative transfer accounting observes every positive file-write delta, even multiple
  writes within one integer percentage and a full re-download after HTTP 416/reset. It does
  not mislabel net file growth as bytes transferred.

### Manager and notification behavior

- The manager exposes default unmetered-Wi-Fi download, an explicit one-run metered/mobile
  confirmation, active cancellation, typed terminal policy/failure details, transfer
  stages/bytes, and a separate installed-only Load action.
- An installed compatible but unloaded fallback remains truthful: download controls are
  disabled, transfer-byte diagnostics are not fabricated while idle, and the owner can
  load the installed artifact without crossing the acquisition boundary.
- The old manager self-test remains generation-only and cannot download.
- Foreground-start denial is surfaced to the owner instead of being swallowed.
- Notification content is deterministic and neutral: checked-in role/model labels,
  known byte evidence, verification/install stage, and an immutable explicit cancel
  `PendingIntent`. It contains no generated prose or personal facts.
- The unknown-size preflight notification says `Starting transfer`, is indeterminate, and
  omits zero-valued expected/remaining claims. `POST_NOTIFICATIONS` is not an admission
  gate; the open manager retains its cancel control when notification permission is denied.

### Installed-only Binder/prewarm boundary

- `LlmEngine.prepare()` and generation remain installed-artifact-only. The foreground
  service alone holds the `ModelAcquirer` transport boundary.
- `prewarmModel()` does only ticket capture and synchronous registration on the authorized
  Binder path. Reading lazy engine/status and inspecting model storage happens inside the
  application-scope coordinator coroutine, never on the Binder caller thread.
- Critical trim invalidates the process-work epoch first, synchronously cancels the
  foreground transfer second, cancels installed-only prewarm third, then coordinates
  native unload only if the engine already exists.
- The production-critical `EpochProcessJobCoordinator` retains repeat coalescing,
  cancellation propagation, stale-pre-trim rejection, and fresh-post-trim behavior tests.

### Contract and release boundary

- No AIDL, canonical client source, copied JSON contract, v1 transaction, v1 error value,
  signer allowlist, permission identity, or consumer copy changed.
- Canonical v1 digest remains
  `fafc994962f895399bdf2e2702255a31388236726847660e41f1a161aaa38f4b`.
- No `versionCode`, `versionName`, application ID, package/namespace, signing, endpoint,
  tag, release, CI publication, or model artifact changed.

## Review findings incorporated

The implementation was repeatedly reviewed at the security/lifecycle boundary while it
was being built. The final source includes corrections for all of these findings:

- network admission and monitoring now precede the first missing-byte acquisition job;
- one startup connectivity check was replaced by exact-network pinning, per-request
  validation, and atomic call creation/cancellation;
- complete partials retain their existing no-request offline promotion path;
- network callbacks no longer mutate main-confined service fields directly;
- preflight no longer falsely names the GPU build or reports a zero-byte transfer;
- installed-but-unloaded models retain a separate acquisition-free Load action;
- setup failures cannot escape or strand `STARTING` state;
- policy loss detected by the request validator wins over a delayed callback;
- typed failure classification follows bounded wrapper cause chains;
- byte accounting is cumulative and reset-safe rather than net file growth;
- prewarm engine/status disk work remains off the Binder caller thread;
- the full four-way epoch/coalescing test evidence was retained after removing the old
  application-owned acquisition wrapper; and
- documentation now matches exact critical-trim ordering and the direct-FGS versus
  user-initiated data-transfer tradeoff.

## Validation performed

All commands used JDK 17, the Android command-line SDK, the isolated Gradle home
`/private/tmp/gradle-localllm-foreground-transfer`, and in-process Kotlin compilation.

### Final focused gate

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
GRADLE_USER_HOME=/private/tmp/gradle-localllm-foreground-transfer \
./gradlew --no-daemon --rerun-tasks :app:testDebugUnitTest \
  --tests 'com.noamv.localllm.transfer.*' \
  --tests 'com.noamv.localllm.service.ModelTransferManifestTest' \
  --tests 'com.noamv.localllm.service.TransferCallFenceTest' \
  --tests 'com.noamv.localllm.ModelStoreTest' \
  --tests 'com.noamv.localllm.engine.ProcessWorkEpochTest' \
  --tests 'com.noamv.localllm.ui.ManagerViewModelSchedulerTest' \
  -Pkotlin.compiler.execution.strategy=in-process
```

Result: `BUILD SUCCESSFUL` in 1 minute 47 seconds; all 27 Gradle tasks executed.
XML evidence reports 73 tests across seven suites, with zero failures, zero errors, and
zero skips:

- `ModelStoreTest`: 40;
- `ManagerViewModelSchedulerTest`: 8;
- `ProcessWorkEpochTest`: 4;
- `ModelTransferManifestTest`: 3;
- `ModelTransferSessionOwnerTest`: 4;
- `TransferCallFenceTest`: 3; and
- `ModelTransferTypesTest`: 11.

### Final full clean gate

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
GRADLE_USER_HOME=/private/tmp/gradle-localllm-foreground-transfer \
./gradlew --no-daemon --rerun-tasks clean testDebugUnitTest assembleDebug lintDebug \
  -Pkotlin.compiler.execution.strategy=in-process
```

Result: `BUILD SUCCESSFUL` in 10 minutes 31 seconds; 100 actionable tasks, 99 executed and
one up-to-date. XML evidence reports 221 JVM tests total, all passing with zero failures,
errors, or skips:

- host app: 176 tests across 24 suites; and
- canonical client: 45 tests across nine suites.

The host debug APK and canonical client debug AAR assembled. App lint reports 24 warnings
and no errors; canonical client lint reports no issues. Packaging warned that
`libandroidx.graphics.path.so`, `libdatastore_shared_counter.so`, and `liblitertlm_jni.so`
could not be stripped and were packaged as-is.

### Contract and hygiene checks

```bash
bash scripts/localllm_v1_client.sh check-local
bash scripts/localllm_v1_client.sh manifest
git diff --check
git diff --name-only -- \
  client \
  app/src/main/aidl \
  app/src/main/java/com/noamv/localllm/contract
```

Results:

- canonical client, app AIDL, and app contract are synchronized;
- both digest commands report
  `fafc994962f895399bdf2e2702255a31388236726847660e41f1a161aaa38f4b`;
- the canonical/client/AIDL/contract diff is empty; and
- `git diff --check` reports no whitespace errors.

### Transient failures corrected before the final gates

- The first compile attempt found incorrect OkHttp `Dns` construction and Kotlin
  visibility exposure. The pinned factory now uses an object implementation and host-only
  types have compatible internal visibility; the next compile passed.
- An early focused attempt exposed ModelStore source compatibility after adding transport
  parameters. The original public signature was restored and the internal overload was
  added separately.
- A later focused run reported three failures: one stale `DownloadProgress` fixture, one
  brittle source assertion, and one notification test that omitted the explicit cumulative
  byte value. The fixture was updated, service control flow was made an exhaustive explicit
  local/network branch, and the presentation test now supplies the production byte
  evidence. The forced 73-test rerun and full 221-test gate then passed.

## Evidence boundary: not validated

The evidence above is source inspection, pure/JVM tests, Android manifest/static tests,
compilation, debug assembly, and lint only. It does **not** prove Android runtime behavior.

Not run or observed:

- Android instrumentation, emulator, or physical-device execution;
- actual foreground-service start timing or Android 15/target-36 timeout delivery;
- runtime behavior with `POST_NOTIFICATIONS` denied;
- real connectivity callback timing, Wi-Fi/cellular/VPN transitions, or data charging;
- a real HTTP model transfer, multi-gigabyte resume, checksum, or atomic promotion;
- real process death, system kill, critical trim, thermal pressure, or storage exhaustion;
- physical cancel/resume, screen-close continuation, or notification action behavior;
- LiteRT model initialization, inference, fallback loading, or native memory behavior;
- ADB, package installation, production-package readback, or production data access; and
- screenshots or recordings of the visible manager changes.

No platform-runtime claim should be inferred from the passing JVM/static suite. A later
sandbox/device task must announce its exact bounded plan and receive separate authorization
before ADB, installation, or a real model transfer.

## Deliberate non-goals still outstanding

This branch does not implement installed-model deletion, partial deletion, repair,
unload/reload, signed model manifests, automatic update downloads, multi-role v2 storage,
benchmarks, assistant protocols/history/providers, version changes, tags, releases, device
actions, or a real model download. It does not publish or copy a new v1 client because that
contract is unchanged.

## Relevant files

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/noamv/localllm/service/ModelTransferService.kt`
- `app/src/main/java/com/noamv/localllm/service/TransferNetworkMonitor.kt`
- `app/src/main/java/com/noamv/localllm/transfer/ModelTransferTypes.kt`
- `app/src/main/java/com/noamv/localllm/transfer/ModelTransferSessionOwner.kt`
- `app/src/main/java/com/noamv/localllm/transfer/ForegroundTransferCancellationRegistry.kt`
- `app/src/main/java/com/noamv/localllm/transfer/TransferNotificationPresentation.kt`
- `app/src/main/java/com/noamv/localllm/LocalLlmApplication.kt`
- `app/src/main/java/com/noamv/localllm/engine/ModelAcquirer.kt`
- `app/src/main/java/com/noamv/localllm/engine/LiteRtEngine.kt`
- `app/src/main/java/com/noamv/localllm/model/ModelStore.kt`
- `app/src/main/java/com/noamv/localllm/ui/ManagerViewModel.kt`
- `app/src/main/java/com/noamv/localllm/ui/MainActivity.kt`
- focused tests beside those boundaries and
  `app/src/test/java/com/noamv/localllm/ModelStoreTest.kt`
- `README.md`, `docs/ARCHITECTURE.md`, `docs/API_CONTRACT.md`,
  `docs/DECISIONS.md`, and this file.

## Recommended next action

Review the single local feature commit and its complete diff, then open one coherent pull
request against current `main`. CI should repeat the repository's required gates. Visible
manager changes should receive screenshots or a short recording in a later authorized
emulator/device validation step. Merging or releasing this branch would not authorize
installation, launch, ADB, production data access, or a real model download.
