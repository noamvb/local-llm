# Antigravity handoff — LocalLLM reliability and assistant roadmap

**Prepared:** 2026-08-23
**Status:** implementation paused for transfer; this is not a merge, release, or device-validation report.
**Audience:** a new Antigravity implementation agent. The document is deliberately explicit so
the work can be resumed safely without relying on prior chat context.

> **Critical reading rule:** inspect the preserved worktree and diff before editing. This
> handoff describes the state known at transfer time; it is not a substitute for source.

## 1. Hard safety and authority boundary

- Do not push, open a pull request, merge, tag, change a version, publish, sign, install,
  launch, use ADB, connect a device, start a real model download, or access production data
  while taking over this handoff.
- Do not reset/clean the worktree, discard local changes, clear app data, uninstall an app,
  delete a model/partial, or modify signing, package identity, production endpoints, or
  credentials.
- Preserve one coherent change per branch. Do not combine foreground transfer, assistant v2,
  provider, FunctionGemma, client UI, or release work.
- A passing JVM test, compile, lint run, CI job, or artifact inspection is not proof of
  Android runtime behavior. Never report it as physical/device evidence.
- Clients send bounded computed facts, never rows. The model writes language only; it never
  calculates facts or gains a write-shaped tool.
- Keep reciprocal signer trust and the LocalLLM signature/known-signer permission strict.
  A caller-provided client ID is metadata, never authorization.

## 2. Correct workspace and exact current state

Open the preserved **LocalLLM** feature worktree supplied by the supervising session, not
the Cannsheet checkout from which the handoff was requested.

Before editing, run:

~~~
git branch --show-current
git rev-parse HEAD
git status --short --branch
git diff --check
bash scripts/localllm_v1_client.sh check-local
bash scripts/localllm_v1_client.sh manifest
~~~

Expected transfer identity:

| Field | Expected value |
| --- | --- |
| Branch | codex/localllm-foreground-transfer |
| HEAD | adafbc69a1157de6453c5fad0c56effc18e18976 |
| Feature commit | adafbc6 — Add owner-started foreground model transfer |
| Exact base from local origin/main | c1e7721d0054d899c1f5bdbc450a4d42912ec96e |
| Working state | adafbc6 plus an intentionally uncommitted post-audit correction set; this handoff file is an additional documentation change |
| Canonical v1 digest | fafc994962f895399bdf2e2702255a31388236726847660e41f1a161aaa38f4b |
| Released LocalLLM host | 0.1.5 only |

If branch, HEAD, base, or dirty state differs, stop and locate the correct preserved
worktree. Do not recreate an unknown diff from this document.

The feature commit and correction set are local-only. No new version, signer, application
ID, endpoint, model artifact, AIDL, canonical v1 client, v1 JSON contract, tag, release,
or CI publication has occurred.

## 3. Antigravity project setup

1. Open the LocalLLM worktree as the project root and attach/read:
   AGENTS.md, README.md, docs/ARCHITECTURE.md, docs/API_CONTRACT.md,
   docs/ASSISTANT_ARCHITECTURE.md, docs/DECISIONS.md, docs/HANDOFF.md, and this file.
2. Keep file access workspace-scoped. Only add Cannsheet or Poop Schedule when a later
   explicitly scoped cross-repository task needs it.
3. Use ask/review-before-execute policy for GitHub, network, device, external filesystem,
   and release actions. Do not enable broad unattended execution.
4. Treat the worktree and test output as the source of truth. Documentation is
   evidence-bounded and must be updated when code/evidence changes.
5. Before accepting a task, state its authority boundary. A feature task does not authorize
   publication, production installation, device use, or production data access.

Suggested first Antigravity prompt:

> Read the LocalLLM handoff and required project documents. Inspect the preserved
> foreground-transfer diff without editing. Report the actual branch/base/dirty state,
> compare every uncommitted correction with its stated review finding, and propose the
> smallest validation sequence. Do not push, create a PR, release, use ADB, install an APK,
> touch model weights, or access production data.

## 4. Work already completed historically

The following are historical, previously verified milestones. They were not re-queried
during this handoff, so treat them as historical evidence rather than live remote state.

| Repository | Historical result |
| --- | --- |
| LocalLLM | acquisition foundation PR #22 merged as c1e7721; exact-main CI run 32679529256 had both required jobs green |
| Cannsheet | canonical v1 hardening PR #156 merged as e13a266ebe531d3190af47661b903e9516de3b0a; exact-main CI run 32677689341 attempt 2 had all six named jobs green |
| Poop Schedule | canonical v1 hardening PR #95 merged as cf462805df4a4409f2c1edc399d57a407f2caac2; exact-main CI run 32678887205 had all six named jobs green |

Those facts do not authorize a release or a production installation. Preserve Cannsheet's
unrelated untracked screenshot if that repository is later opened.

LocalLLM does not have docs/PROJECT_STATE.md. The canonical current-state documents are
README.md, docs/ARCHITECTURE.md, docs/API_CONTRACT.md, docs/DECISIONS.md, and docs/HANDOFF.md.

## 5. Current foreground model-transfer branch

### Product intent

Model acquisition must happen only after an owner-visible action in LocalLLM's Manager
screen. It must never be a side effect of a client bind, status call, warmup, generation,
self-test, boot, service restart, or process recreation.

The default requires unmetered validated Wi-Fi. The owner may explicitly allow a single
metered/mobile run. The transfer continues when the Manager screen closes, can be cancelled,
retains safe partial bytes, SHA-256 verifies the artifact, and atomically promotes it.

### Main implementation locations

| Responsibility | Location |
| --- | --- |
| FGS lifecycle, timeout, cleanup, notification | app/src/main/java/com/noamv/localllm/service/ModelTransferService.kt |
| exact Android network lease and call fence | app/src/main/java/com/noamv/localllm/service/TransferNetworkMonitor.kt |
| state, terminal reasons, bytes, commit arbiter | app/src/main/java/com/noamv/localllm/transfer/ModelTransferTypes.kt |
| process-wide transfer session IDs | app/src/main/java/com/noamv/localllm/transfer/ModelTransferSessionOwner.kt |
| cancellation registry | app/src/main/java/com/noamv/localllm/transfer/ForegroundTransferCancellationRegistry.kt |
| notifications | app/src/main/java/com/noamv/localllm/transfer/TransferNotificationPresentation.kt |
| app integration and critical trim | app/src/main/java/com/noamv/localllm/LocalLlmApplication.kt |
| acquisition abstraction | app/src/main/java/com/noamv/localllm/engine/ModelAcquirer.kt |
| installed-only engine | app/src/main/java/com/noamv/localllm/engine/LiteRtEngine.kt |
| resume/hash/promotion/disk snapshot | app/src/main/java/com/noamv/localllm/model/ModelStore.kt |
| Manager controls | app/src/main/java/com/noamv/localllm/ui/ManagerViewModel.kt and ui/MainActivity.kt |
| service declaration | app/src/main/AndroidManifest.xml |

### Intended current behavior

1. Manager starts a private non-exported dataSync foreground service; it calls
   startForeground before status, disk, network, or transfer work.
2. The service is START_NOT_STICKY. Null/unknown/retry intents and process recreation
   never restart hidden acquisition. It has a five-hour internal deadline.
3. Default admission requires one exact Android Network with VALIDATED, INTERNET,
   NOT_METERED, and Wi-Fi transport. One-run override relaxes only Wi-Fi/unmetered.
4. OkHttp is pinned to the admitted network's socket factory and DNS; transparent retry is
   disabled. An atomic lease/call fence revalidates bounded requests and cancels all
   registered calls when policy is lost.
5. Complete retained partials are verified/promoted locally with no network lease/call.
   Missing bytes use strict resume, validated Content-Range, controlled HTTP 416 recovery,
   byte caps, SHA-256, and atomic promotion.
6. Transfer bytes mean cumulative successful HTTP response-body bytes read, not retained
   file growth. Rejected/rolled-back and re-downloaded bytes are still counted.
7. Cancellation and promotion share a commit arbiter. Cancellation that wins blocks
   promotion; promotion that wins cannot be relabelled cancelled. Terminal bytes are read
   from actual installed/partial disk state after ownership settles.
8. Repeated owner starts coalesce and cannot widen policy. Session IDs are process-wide
   monotonic so stale callbacks cannot match a newly created service instance.
9. The FGS stops after verified promotion. It never runs inference/initialization.
   Installed-but-unloaded compatible models keep a separate acquisition-free Load action.
10. Critical trim cancels the registered transfer job after invalidating preparation work
    and preserves safely written partials. The bound inference service remains non-foreground.

## 6. Post-audit correction set: dirty and not yet accepted

The original adafbc6 feature commit passed a full static/JVM gate, then review found the
following material issues. The corrections below are intentionally uncommitted. They require
a full diff review and a new full gate before a separate follow-up commit.

| Original issue | Current correction | Caution |
| --- | --- | --- |
| Cleanup remembered only start-action IDs; a later cancel/invalid command could leave a service started. | DeliveredStartIdTracker records every delivered start ID; cleanup uses the latest ID with stopSelfResult. | JVM/source coverage exists; Android multi-start behavior remains unproven. |
| Lease invalidation could cancel I/O before UI handling and surface FAILED rather than POLICY_BLOCKED. | resolveTransferNetworkBlockReason preserves the one-way lease terminal reason through bounded error unwrapping. | Review race ordering; real callbacks are untested. |
| Terminal bytes could be stale after rollback/checksum deletion/promotion. | ModelStore exposes exact storage snapshots; refreshStorageBytes preserves newer phase. | Inspect every terminal path. |
| HTTP 200 ignoring Range could undercount full re-download bytes. | Range-ignore reset is explicit; every successful body read is counted before write. | Preserve accounting semantics in UI. |
| File-growth accounting omitted rejected/rolled-back network cost. | ProgressReporter.recordNetworkBytes executes on successful reads. | Do not describe this as durable retained bytes. |
| Cancellation could publish CANCELLED while promotion committed. | PromotionCommitArbiter chooses exactly one cancellation/commit winner; target validation is inside commit. | Carefully inspect onDestroy and timeout interleavings. |
| A new transfer could start while prior ModelStore work still owned files. | Started cancellation retains FGS/session/collector until settlement; pre-start rejection cleans immediately. | Ensure every finally path settles exactly once. |
| Service recreation could collide session IDs. | ModelTransferSessionOwner has a private process-global AtomicLong. | Do not weaken process/session fencing. |
| Platform dataSync timeout could wait forever. | handlePlatformTimeout requests normal arbiter-aware cancellation, then schedules a two-second same-session watchdog. | Highest-risk unproven path: source/JVM coverage is not Android 15/16 grace proof. |

Expected dirty correction files after adafbc6:

~~~
README.md
app/src/main/java/com/noamv/localllm/LocalLlmApplication.kt
app/src/main/java/com/noamv/localllm/engine/LiteRtEngine.kt
app/src/main/java/com/noamv/localllm/engine/ModelAcquirer.kt
app/src/main/java/com/noamv/localllm/model/ModelStore.kt
app/src/main/java/com/noamv/localllm/service/ModelTransferService.kt
app/src/main/java/com/noamv/localllm/service/TransferNetworkMonitor.kt
app/src/main/java/com/noamv/localllm/transfer/ModelTransferSessionOwner.kt
app/src/main/java/com/noamv/localllm/transfer/ModelTransferTypes.kt
app/src/test/java/com/noamv/localllm/ModelStoreTest.kt
app/src/test/java/com/noamv/localllm/service/ModelTransferManifestTest.kt
app/src/test/java/com/noamv/localllm/service/TransferCallFenceTest.kt
app/src/test/java/com/noamv/localllm/transfer/ModelTransferSessionOwnerTest.kt
app/src/test/java/com/noamv/localllm/transfer/ModelTransferTypesTest.kt
docs/ARCHITECTURE.md
docs/DECISIONS.md
docs/HANDOFF.md
~~~

This document is also an expected local documentation addition. No source/contract file
should become newly untracked during transfer.

## 7. Validation evidence

### Historical full gate — adafbc6 only

The initial feature commit passed:

~~~
JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
GRADLE_USER_HOME=<isolated-gradle-home> \
./gradlew --no-daemon --rerun-tasks clean testDebugUnitTest assembleDebug lintDebug \
  -Pkotlin.compiler.execution.strategy=in-process
~~~

Result: BUILD SUCCESSFUL in 10 minutes 31 seconds; 221 JVM tests with zero failures,
errors, or skips. Host: 176 tests across 24 suites. Canonical client: 45 tests across nine
suites. Debug app/APK and client AAR assembled. App lint: 24 warnings, zero errors.
Canonical client lint: zero issues. Packaging could not strip three native libraries and
packaged them as-is.

**Do not apply this result to the dirty correction set.**

### Focused gate — after corrections

The current correction set passed:

~~~
JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
GRADLE_USER_HOME=<isolated-gradle-home> \
./gradlew --no-daemon --rerun-tasks :app:testDebugUnitTest \
  --tests 'com.noamv.localllm.transfer.*' \
  --tests 'com.noamv.localllm.service.ModelTransferManifestTest' \
  --tests 'com.noamv.localllm.service.TransferCallFenceTest' \
  --tests 'com.noamv.localllm.ModelStoreTest' \
  --tests 'com.noamv.localllm.engine.ProcessWorkEpochTest' \
  --tests 'com.noamv.localllm.ui.ManagerViewModelSchedulerTest' \
  -Pkotlin.compiler.execution.strategy=in-process
~~~

Result: BUILD SUCCESSFUL in 1 minute 33 seconds; 89 tests, zero failures/errors/skips:
ModelStore 44; ManagerViewModelScheduler 8; ProcessWorkEpoch 4; Manifest 3;
ModelTransferSessionOwner 10; TransferCallFence 3; ModelTransferTypes 17.

Immediately before creating this document, these cheap checks passed on the dirty tree:

~~~
git diff --check
bash scripts/localllm_v1_client.sh check-local
bash scripts/localllm_v1_client.sh manifest
git diff --name-only -- client app/src/main/aidl app/src/main/java/com/noamv/localllm/contract
~~~

They produced no whitespace errors, both digest checks reported
fafc994962f895399bdf2e2702255a31388236726847660e41f1a161aaa38f4b, and the final
contract/AIDL/client diff was empty.

### Missing evidence

No post-correction full clean gate, correction commit, push, PR, review, merge, release,
or exact-main CI exists. No emulator/instrumentation/device/ADB/installation/real download
exists. No real FGS timing/timeout, notification denial, network switch, resume, 416,
checksum, promotion, process death, trim, native inference, memory, thermal, or UI visual
evidence exists.

## 8. Immediate continuation checklist

### Intake before any edit

- [ ] Verify branch, HEAD, base, dirty state, whitespace, and v1 digest.
- [ ] Review git diff HEAD -- for post-audit corrections and git diff c1e7721..HEAD --
      for the complete feature separately.
- [ ] Read the foreground-transfer source/test files and prove each table row in section 6
      from code. Flag uncertainty; do not infer a result.
- [ ] Confirm no AIDL/client/contract/signing/version/endpoint/Gradle/workflow changes.
- [ ] Preserve unrelated user changes and no generated/model artifacts.

### Validate before any follow-up commit

- [ ] Re-run the focused gate and record a new exact result.
- [ ] Run the full clean gate from section 7 against the dirty worktree.
- [ ] Repeat whitespace and v1 digest checks after the full gate.
- [ ] Review these interleavings: all start intent types versus stopSelfResult; normal
      cancellation/policy loss/trim/onDestroy/timeout versus file ownership; watchdog versus
      new/finishing session; cancellation versus promotion; 206/200-after-Range/416/
      oversize/checksum cases; callback order versus exact policy terminal state.
- [ ] Add a focused regression test only for a confirmed gap. Do not pretend brittle source
      substring tests prove Android platform behavior.
- [ ] Update documentation to distinguish implementation, test, and physical evidence.
- [ ] Review the whole diff for secrets, binaries, credentials, unrelated changes, and
      version/signing changes.
- [ ] Only then create a separate local correction commit; do not amend adafbc6.

Stop for new authority before GitHub/network/PR/device/release work.

## 9. Updated implementation roadmap (without agent/model assignments)

### Current estimate

Evidence-weighted progress is approximately **29% of the full four-stage released roadmap**.
Stage 1 is about **87% implemented in source**. This is not a release claim: current
foreground corrections are dirty, lack a post-correction full gate, and have no PR, merge,
or physical evidence.

### Wave A — finish foreground transfer

1. Complete section 8 and make a separately reviewable correction checkpoint.
2. Keep transfer work feature-scoped. Do not add deletion/repair/manifests/router/v2 here.
3. Only under later explicit authority: PR with complete test evidence and Manager
   screenshots/recording, then normal CI/review. No device activity without a sandbox plan.
4. Release only through a later separate version-only PR after exact merged-main proof.

### Wave B — remaining Stage 1 foundation

1. LocalLLM: persisted viable backend fallback, precise acquisition/backend/storage/OOM/busy
   error categories, synchronized prepare/generate/trim/close, real manager reload/delete/
   partial-delete/repair/status.
2. v1 service/client: signer lineage, exact UID/package checks, same-session negotiation,
   finite binds/timeouts/death/cancellation, authoritative final result, request limits,
   hard output tokens, bounded priority queue. Preserve v1 transaction layout.
3. Cannsheet: correct recorded-spend coverage/all-unknown wording, time bands/ties/labels,
   settled-snapshot eligibility/fingerprint, cancellation/timeout/final validation/cache.
4. Poop Schedule: one end-bounded recorded-zone snapshot, exclude future rows, explicit
   lock-screen policy, accurate notification result, average/tie/custom-range semantics,
   terminal validation.
5. Copy frozen canonical v1 changes mechanically to both clients only after host merge.

Release gate: host first; each repository uses feature PR with no version change, exact
merged-main CI, separate version-only PR, exact versioned-main proof, tag, signed
publication, and independent checksum/package/version/signer verification.

### Wave C — freeze v2 host before providers

1. Freeze aggregate-query grammar and fixtures after deterministic/parser/base-model spike.
2. Add new V2 AIDL interfaces for assistant turns, callbacks, providers, and provider
   callbacks. Do not grow v1 transaction codes.
3. Define capabilities, requests, aggregate query, fact evidence, events, terminal result,
   and cursor history JSON with explicit unknown-enum handling.
4. Add mutual provider trust, host master/per-app switches, bounds, explicit cross-app
   consent, no raw rows/notes/IDs/queue details, and deterministic clarification.
5. Add LocalLLM-only Room history with migrations, no backup/export, atomic terminal
   persistence, multiple threads, pagination, delete UI, and automatic-insights feed.
6. Add WRITER/ROUTER role-aware model state/storage, one-role residency, idle unload,
   signed manifests, repair, diagnostics, and fixed synthetic benchmark.
7. Ship deterministic routing/clarification first. FunctionGemma stays disabled until all
   evaluation, license, artifact, memory/latency/thermal gates pass.
8. Add provider orchestration, structured writer citations, numeric grounding, failed-output
   retention as escaped inert text, partial source warnings, and terminal persistence.

Provider implementations cannot invent contract variations; cross-app completion needs both
real providers.

### Wave D — both clients after v2 host freezes

1. Cannsheet provider + Assistant tab + complete history + citations/limitations + settled
   snapshot facts + product/date/navigation bounds + grounded 30-day cards + idempotent
   post-sync daily worker.
2. Poop Schedule provider + Assistant tab/history + descriptive aggregate exclusions +
   local note count/navigation only + activity-only cards + daily worker + neutral
   notifications + retirement of old weekly generated nudge.
3. Host integration: prove single-app questions never bind the other provider; explicitly
   cross-app output uses separate evidence groups and deterministic missing-source warnings.

Daily background work cannot start from mocks and never exposes drafts or generated personal
notification text.

### Wave E — optional FunctionGemma activation

1. Check in synthetic English corpus, template-family split, reproducible tuning/conversion/
   evaluation, hashes/seeds/terms/model card, frozen adversarial suites; never weights/tokens.
2. Compare deterministic, constrained E2B, base FunctionGemma, and tuned FunctionGemma.
3. Require 100% schema-valid/parseable, >=99% exact supported arguments, 100% rejection of
   write/medical/causal/raw-row/grammar escape, 100% no implicit cross-app access, all
   ambiguous cases clarified, and device memory/latency/thermal gates.
4. Publish/install router only after artifact terms and immutable signed-manifest evidence.
   Otherwise keep deterministic routing as the released implementation.

## 10. Stable assistant acceptance rules

- The initiating app is default source. Cross-app access requires both explicit user wording/
  choice and deterministic source authorization. It is side-by-side description only, never
  correlation, causation, medical meaning, or behavioral effect.
- Only authoritative client code calculates periods, statistics, comparisons, deltas, and
  filters. Router proposes a grammar; deterministic code validates/executes it.
- Every successful generated sentence cites fact IDs; every number appears in its cited
  facts with the same meaning/unit. Warnings are app-rendered.
- Only an open Assistant screen may display escaped, labelled unverified draft fragments.
  Cards and notifications never display drafts.
- Failed output is stored only as collapsed escaped inert text with failure reasons. It is
  never evidence, navigation, model context, a card, action, or notification body.
- Follow-ups re-fetch current facts. Shared history is LocalLLM-owned; automatic insights
  are isolated from conversational context.
- Daily automatic summaries are client-owned, fresh/settled, activity-only, router-free,
  source-timezone 30-versus-prior-30 runs with neutral public notifications.

## 11. Definition of a safe next step

The next safe step is deliberately narrow: inspect and validate the uncommitted
post-audit foreground-transfer corrections, then create a separate local correction
checkpoint only if the full gate and source review pass. Do not move to broader assistant
implementation, GitHub, device testing, or release work until the supervising user gives
new authority.
