# Handoff

Last updated: 2026-08-23. LocalLLM 0.1.5 remains the released host. The client repositories
have since advanced independently to Poop Schedule 1.3.0 and Cannsheet Mobile 1.6.2. The
older LocalLLM integration and device evidence below remains historical evidence for the
versions named in each paragraph; it is not evidence that the accepted assistant roadmap
or its version-two protocol has been implemented.

The accepted target boundary is recorded in `docs/ASSISTANT_ARCHITECTURE.md` and the
2026-08-23 decisions. Work is staged: version-one trust/reliability repairs first, then a
separate version-two host, then both client providers and assistant surfaces. No device or
production-data action is implied by that roadmap.

## Merged Stage 1 model-acquisition foundation

PR #19 merged as `7ad2bd1f2ebb194b071d061c7c7483dabc9d8116`. Exact-main run
`32650705249` passed both required jobs. It serializes transfer/delete/prune ownership;
immediately cancels blocked OkHttp work; retains resumable partial bytes; validates
Range/206/416, response count, pinned size, storage headroom, and checksum; coalesces
monotonic progress; and atomically replaces a previous target only after verification.
Engine pruning awaits that suspending ownership boundary and preserves coroutine
cancellation.

Local JDK 17 evidence on 2026-08-23 was 35 focused ModelStore tests and 82 full JVM tests,
all passing with zero failures, errors, or skips, plus successful debug assembly and lint.
This remains static/JVM evidence only: no real model network transfer or physical
cancel/resume scenario was exercised.

## Unreleased v1 Binder hardening

Branch `codex/localllm-v1-security` adds mutual package/signing-lineage authentication,
same-session API negotiation, finite client deadlines, exact-once connection failure
handling, callback request-ID validation, callback Binder-death cancellation, and
typed progress/draft/completion/failure delivery with authoritative final text. It pins
the exact service class, caps callbacks queued before ID assignment, explicitly declares
which service API versions support v1, and rejects the unimplemented v1 `resultSchema`
instead of promising structured output.

Independent review additionally moved prewarm behind exact transaction authorization,
made one bind deadline cover package resolution and connection, fenced and tracked the
generation submission worker, compiled the copy-ready client at minSdk 24, and added the
deterministic `scripts/localllm_v1_client.sh` copy/digest/check procedure. A timed-out
version call cannot later transmit facts. The residual platform boundary is explicit: an
already-entered synchronous Binder request cannot be interrupted, so its late request ID
is cancelled as soon as it returns.

A second independent review found and drove three final race/provenance corrections: a
CAS-defined submission-begin boundary, one synchronized pending/delivered/terminal bind
owner, and a copy/check rule that refuses uncommitted canonical bytes and verifies both
recorded commit and digest. These replace the earlier read-only check and split bind state
that could still lose a narrow timeout race.

Final bind-registration review then found that terminal cleanup could still run before a
synchronous `bindService()` call finished registering its `ServiceConnection`. Cleanup is
now remembered and the actual unbind is delivered exactly once after that call returns;
the Binder/death-recipient link is also published and unlinked as one pair. Fake binding
tests force both timeout-immediately-before-registration and timeout-during-registration
orderings and prove that neither leaves the fake connection registered.

A final delivery-arbitration review found that callback admission still lived in a
different state machine from timeout/death failure and channel closure. The callback gate
now owns request-ID validation, first-response admission and timer cancellation, every
checked `trySend`, terminal selection, and close under one lock. Latch-forced races cover
authoritative completion versus total timeout, an admitted draft versus Binder death on
the actual conflated channel, and both winners of first response versus its deadline.

The AIDL method order and signatures are unchanged. The canonical `client` tree is now a
buildable Android library module with unit coverage for package/signer policy, signing-key
rotation, shared-UID rejection, pre-ID callback races, ID mismatch, completion-only text,
authoritative final replacement, progress payloads, pre-ID callback flood, future unknown
API versions and channel-delivery failure. The deprecated string wrapper remains
source-compatible and emits completion only, so all three current append-based Cannsheet
and Poop Schedule call sites remain behaviorally safe after vendoring.

After the delivery-arbitration fix, an isolated JDK 17 focused gate passed all 15 callback
delivery and arbitration tests after their competing threads were forced to block on the
shared monitor. The final isolated command used `--no-daemon`, `--rerun-tasks`, `clean`,
`testDebugUnitTest`, `assembleDebug`, `lintDebug`, and
`-Pkotlin.compiler.execution.strategy=in-process`. It passed all 140 JVM tests (95 app and
45 client) with zero failures, errors or skips; both debug artifacts assembled; app lint
completed with 24 warnings and no errors; client lint had no issues.
`bash scripts/localllm_v1_client.sh check-local` passed at canonical digest
`fafc994962f895399bdf2e2702255a31388236726847660e41f1a161aaa38f4b`, and the earlier
negative copy test confirmed dirty canonical source is rejected before any provenance file
is written. No Android instrumentation, physical-device or cross-process
package-replacement test has been run.

Signer pins were re-derived from independently downloaded current release APKs on
2026-08-23: LocalLLM v0.1.5, Poop Schedule v1.3.0 and Cannsheet Mobile v1.6.2. This work has
not been merged, released, installed or exercised on a device. Downstream client
copies and their append-style collectors are intentionally not changed in this branch;
they can vendor the canonical client without changing their append behavior in coordinated
follow-up PRs, while new UI should consume the typed event API.

## Unreleased Stage 1 service reliability

Branch `codex/localllm-v1-service-reliability`, based on exact-green security main
`4e588cbde0a5447dd1785819b8c9488639a4eead`, integrates the scheduler into both Binder
generation and the manager self-test. One process-owned scheduler admits one active request
and at most two waiting requests. Waiting work is priority-ordered and FIFO within a lane,
expires after 120 seconds, and is removed on explicit cancellation, callback Binder death or
owner cancellation. Native work is deliberately not pre-empted. Because contract v1 has no
trusted execution-context field, every v1 task maps deterministically to `OPEN_SCREEN`;
`LIVE_ASSISTANT` and `BACKGROUND` remain reserved for a future contract.

Requests are registered before their lazy jobs start. A service-lifetime gate coordinates
synchronous admission with callback death, cancellation and terminal cleanup, while one
callback gate serializes progress/token delivery with the sole completion or failure. Service
destruction fences new registrations and cancels only that service instance's records; it does
not close the process scheduler. The manager self-test uses the same admission boundary, reports
busy/expiry/failure states without mixing replacement runs, and cannot bypass already-admitted
Binder generation.

Contract v1 validation now happens before scheduler or engine entry. Raw JSON is capped at
32 KiB UTF-8; version, known task, client ID, nonblank bounded strings, UTF-8 sizes, control and
invisible characters, fact counts, strict real ISO dates, date ordering and `maxWords` are
checked. Summary and nudge require a period and facts, comparison requires both periods and fact
sets, comparison-only fields are rejected elsewhere, and nudge requires
`lockScreenSafe=true`. Every non-null `resultSchema` is still rejected. The documented v1
compatibility opt-outs `forbidHealthClaims=false` and `forbidNewNumbers=false` remain accepted;
this service does not silently reinterpret those client choices.

LiteRT receives a request-derived `maxOutputToken` with an unconditional 256-token ceiling.
Assembled terminal text is separately capped at 8192 UTF-16 characters, rejected when blank,
and checked against the request's actual word limit; nudge remains tighter at 20 words. Model
acquisition, network, download protocol, checksum, storage, initialization, unsupported-backend,
out-of-memory, cancellation, busy and internal failures are mapped to the seven frozen v1 codes
with sanitized category-specific guidance. Retryability is retained for diagnostics and prose
only because v1 has no retryable wire field; no AIDL transaction, signature or error-code value
changed.

The final isolated JDK 17 command was
`./gradlew --no-daemon --rerun-tasks clean testDebugUnitTest assembleDebug lintDebug
-Pkotlin.compiler.execution.strategy=in-process`, with
`GRADLE_USER_HOME=/private/tmp/localllm-roadmap-20260823.pO7j0d/gradle-service-reliability`.
It completed 100 Gradle tasks successfully in 5 minutes 42 seconds. All 181 JVM tests passed
(136 app and 45 client; zero failures, errors or skips), both debug artifacts assembled, app
lint completed with 24 warnings and no errors, and client lint reported no issues. The focused
service-reliability gate passed 54 scheduler, lifecycle-race, callback-arbitration, validation,
output-policy, prompt, failure-mapping, authorization and manager-self-test cases. Final
`git diff --check` and frozen-contract drift checks passed, and
`bash scripts/localllm_v1_client.sh check-local` retained canonical digest
`fafc994962f895399bdf2e2702255a31388236726847660e41f1a161aaa38f4b`.

This remains static/JVM evidence. No Android instrumentation, emulator, physical-device,
cross-process Binder-death timing, real LiteRT generation, real model acquisition, OOM, trim or
native-cancellation scenario was exercised. LiteRT cancellation remains cooperative at the
coroutine boundary rather than native pre-emption. The 256-token parameter is compilation- and
unit-verified against LiteRT 0.16.1 but has not been measured with a device model. This branch has
not been pushed, merged, released, installed or used to download a model.

## Where everything stands

| Repository | Released | State |
| --- | --- | --- |
| `noamvb/local-llm` | **v0.1.5** | Public. Releases publish in-repo. |
| `noamvb/poop-schedule` | **v1.3.0** | Private, releases to `poop-schedule-releases`. Its later NFC timer-tag release is independent of the LocalLLM roadmap. |
| `noamvb/cannsheet-mobile` | **v1.6.2** | Public, releases to `cannsheet-mobile-releases`. Its later NFC quick-log releases are independent of the LocalLLM roadmap. |

All three publications were verified by downloading the asset and checking it, not by
trusting the pipeline:

| App | versionCode | Signer SHA-256 | Continuity |
| --- | --- | --- | --- |
| Poop Schedule 1.2.2 | 21 | `98198cd1…a55cde` | identical to 1.1.0/1.2.0/1.2.1 |
| Poop Schedule 1.2.3 | 22 | `98198cd1…a55cde` | identical to 1.2.2 |
| Poop Schedule 1.3.0 | 24 | `98198cd1…a55cde` | identical to 1.2.4 |
| Cannsheet Mobile 1.4.2 | 38 | `a9787249…08665e` | identical to 1.3.4/1.4.0/1.4.1 |
| Cannsheet Mobile 1.4.3 | 39 | `a9787249…08665e` | identical to 1.4.2 |
| Cannsheet Mobile 1.6.2 | 47 | `a9787249…08665e` | identical to 1.6.1/1.6.0/1.5.2 |
| LocalLLM 0.1.2 | 3 | `f1f2632b…d3b95d` | identical to 0.1.1 |
| LocalLLM 0.1.3 | 4 | `f1f2632b…d3b95d` | identical to 0.1.2 |
| LocalLLM 0.1.4 | 5 | `f1f2632b…d3b95d` | identical to 0.1.3 |
| LocalLLM 0.1.5 | 6 | `f1f2632b…d3b95d` | identical to 0.1.4 |

Both digests are the ones `local-llm`'s `app/src/main/res/values/known_signers.xml` grants
inference permission to, so a clean install binds. Obtainium updates both in place.

## What is verified, and what only looks verified

Verified on the Galaxy Z Fold 7 (`SM8750`, Android 16):

- LocalLLM 0.1.x loads Gemma 4 E2B on the GPU and reaches `READY`.
- Poop Schedule's insight card generates against **real records**, every figure matching
  the app's own statistics (33 entries / 7.7 per week / 21 h 2 min / 11 min).
- `INFERENCE: granted=true` across two *different* signing certificates, which is the
  whole point of the `knownSigner` permission.
- Graceful degradation with no model present: the card renders nothing rather than an error.
- **0.1.2 holds (2026-08-18).** The model survived closing the app and switching away:
  storage stayed at 2.08 GB, and the manager screen went MODEL_MISSING → INITIALISING →
  READY in about four seconds with no download.
- **Both insight cards render on a device**, each within about eight seconds of opening
  Insights from a cold LocalLLM process. Poop Schedule's figures match its own statistics
  exactly (35 entries / 8.2 per week / 20 h 19 min / 11 min). **This was Cannsheet's first
  observed render on hardware** — ADR-025 should be updated accordingly.

**0.1.5 / 1.2.4 / 1.4.4 publication was verified from a Claude Code session, not on the
device (2026-08-20).** All three release workflows completed with conclusion `success`,
each tagged commit was confirmed to still be the exact tip of `main` before tagging, and
each published APK was independently re-downloaded and checked: the file SHA-256 against
the published `.sha256` asset, and the signing certificate SHA-256 extracted directly from
the APK's v2 signing block (hand-parsed — the session's environment had no `apksigner` or
`aapt`). All three signing certificates are byte-identical to the previous release, so
Obtainium updates in place without an uninstall. This confirms the artefacts are genuine
and correctly signed; it does not confirm any of them run correctly on a phone. See each
repository's own `docs/HANDOFF.md` for full release provenance — workflow run IDs, job
names, and exact digests.

**Regression found and fixed on 2026-08-18 (0.1.2).** The insight cards stopped appearing
in *both* clients, and the model appeared to need re-downloading after every close. One
cause: `LiteRtEngine.prepare()` caught `Throwable`, which includes `CancellationException`,
and deleted the model plus its `.part`. The download ran in `viewModelScope`, so leaving the
app cancelled it and triggered the delete. Because both clients gate on
`EngineStatus.modelDownloaded`, a deleted model makes the card render nothing — silently, by
design. Measured on the Fold: 2.08 GB before the Insights path ran, 70.14 MB after, with no
`.part` surviving. Fixed in #4; see `docs/DECISIONS.md`.

**The 0.1.3 UI fix, and why it was needed (2026-08-18).** With 0.1.2 working, the owner
still reported the model "disappearing" after switching apps. It had not. `MODEL_MISSING`
means "no engine is loaded", not "no model file", and Android kills this process whenever it
is backgrounded — so that state is what you see on every return, over a fully verified model.
The button compounded it by offering to *download* in every state but READY. The screen now
states the file's presence on its own line and labels the button "Load model" when nothing
needs fetching. Note also that `engineStatusText` used to concatenate the state and
`detail` unconditionally, which rendered "Ready Ready" and "Loading Loading Gemma 4 E2B
(GPU)" — both observed on the device.

**Not verified, and do not claim otherwise:**

1. **No nudge has ever been delivered on a device.** The code path has unit tests and has
   never once run to a posted notification. It fires only when there are ≥5 entries across
   ≥3 distinct days in a 7-day window with ≥6 days since the last nudge, so the first real
   one arrives on its own schedule.
2. **`setPublicVersion` behaviour is unconfirmed.** The owner chose full detail on the lock
   screen with a neutral public version for casting/DeX. Nobody has looked at a locked
   screen to check which one appears. This needs the owner's eyes; it cannot be seen over
   `adb`.
3. **An interrupted download has never been resumed on a device.** `ModelStoreTest`
   covers the resume contract and the model now survives closing the app, but nobody has
   killed a transfer part-way and watched it pick up from its `.part`.
4. **The loading indicators and scroll fixes are not confirmed on a device.** Both client
   cards gained loading indicators and scroll regeneration fixes; only their unit tests and
   CI have run.
5. **On-device prewarm timing numbers are pending.** Prewarming on bind, TTFT telemetry,
   and load timing displays have been unit-test and compilation verified; on-device load and
   TTFT measurements on the Galaxy Z Fold 7 are left for the owner.
6. **The 0.1.5/1.2.4/1.4.4 follow-up fixes are not confirmed on a device.** The unbind-leak
   fix in `LocalLlmClient.warmup()`, the nudge-worker prewarm reordering (battery exemption
   checked before touching the client), the split TTFT/prefill timing, and both clients'
   Insights warmup being gated on there being enough data to summarise are all
   timing/lifecycle changes with no visible UI difference. Nothing here has been observed
   running against a physical phone; only unit tests, CI, and the publication verification
   above have run.

## Do this first

1. **Update all three apps via Obtainium** (v0.1.5, v1.2.4, v1.4.4) and confirm each
   installs in place rather than prompting an uninstall — that is the on-device signing
   check the publication verification above could not perform itself.
2. Watch for the first real nudge and check the lock screen.
3. **Back up `/Users/sophiaparis/LocalLLM-signing/`.** Still outstanding. It exists nowhere
   but that folder and the `RELEASE_KEYSTORE_BASE64` secret. Lose both and LocalLLM is
   permanently un-updatable — every user would have to uninstall and re-download the model.

## The nudge, as actually built

The original plan — worker binds LocalLLM, LocalLLM promotes itself to the foreground for
the generation — is impossible, and the reasoning is worth keeping because it is not
obvious:

An app targeting API 31+ cannot start a foreground service from the background. For a
*bound* service the system does not evaluate the service's own state; it walks the binding
clients and re-runs the check against each (`ActiveServices.canBindingClientStartFgsLocked`,
yielding `REASON_FGS_BINDING`). Binding **propagates** a client's existing eligibility and
never creates any. A plain WorkManager job has none. Worse, the resulting
`ForegroundServiceStartNotAllowedException` is thrown into *LocalLLM's* process, gated on
*LocalLLM's* `targetSdk`, where the client cannot catch it — LocalLLM would die mid-generation
and the client would see only `DeadObjectException`.

Dead ends, already checked so nobody re-checks them:

- `setExpedited` does not help. On API 31+ it is a JobScheduler expedited job, not a
  foreground service, and confers no ability to start one.
- There is no usable bind flag. `BIND_ALLOW_FOREGROUND_SERVICE_STARTS_FROM_BACKGROUND` is
  `@hide`, `@SystemApi`, deprecated and permission-gated.
- Exact alarms give a foreground-service allowlist window of about **ten seconds**, nowhere
  near enough for a cold Gemma load.
- `shortService` is wrong even though it sounds right: AOSP explicitly withholds
  `PROCESS_CAPABILITY_BFSL` from short services.

**What shipped instead.** LocalLLM no longer calls `startForeground` at all on the bound
path (v0.1.1). The foreground service is owned by the *client*: `NudgeWorker` calls
`setForeground` with `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`, and eligibility comes from the
battery-optimisation allowlist, which is the only exemption a headless weekly worker can
hold. The worker checks notifications → due → model present → battery exemption **before**
generating, so it never wakes the model to throw the result away, and always returns
`Result.success()` so WorkManager does not back off.

**Output is never trusted.** `NudgeText.validate` rejects a generated sentence that invents
a number, uses clinical language, refuses, leaks the prompt, runs to multiple sentences, or
carries control/bidi-override characters. Numeric grounding normalises number *words* to
digits first — the model writes `"33 entries"` and `"twelve entries"` interchangeably, both
observed, so a digit-only check passes trivially by finding no digits at all. The rejection
reason surfaces in a Settings row, because otherwise silence is indistinguishable from a bug.

## CI: the emulator job used to hang forever

Both Android repos run `reactivecircus/android-emulator-runner`. Its teardown is a bare
`adb emu kill` with **no timeout around it**, so anything still holding the emulator's
shared client connection stalls the step until the job timeout. Two distinct causes, both
fixed in `poop-schedule`'s `android-pr-checks.yml`; apply the same fix if `cannsheet-mobile`
ever starts hanging:

1. **`crashpad_handler` outlives the emulator.** It is a sibling of qemu, holds
   `--initial-client-fd`, and the runner only reaps it at `Complete job` — a line a hung job
   never reaches. Kill it explicitly.
2. **Process names are truncated to 15 characters by Linux.** `ps -eo comm` reports
   `qemu-system-x86` and `crashpad_handle`, so `pkill -x qemu-system-x86_64` matches nothing.
   Measure the name; do not guess it. Use `-x`, not `-f` — `-f` matches `pkill`'s own command
   line and kills the shell.
3. **The action feeds `script:` to `sh -c` one line at a time.** Every line appears in the
   log as its own `/usr/bin/sh -c <line>`. Multi-line shell constructs are impossible; a
   `for` loop dies with `end of file unexpected (expecting "done")`. Keep it on one line.

Result: the emulator job runs ~5m20s with a 0.15-second teardown.

**Reading a stuck run.** The API cannot serve logs for an in-progress run — `gh run view
--log` returns `BlobNotFound`. A run that hangs never completes, so its logs are *only*
visible in the browser. Diagnosing from runs that actually failed gives a false picture,
because the failing runs are not the stuck ones.

## Things that will bite

- **Never narrate an absence.** A gap in logging is itself sensitive — people stop logging
  when unwell, travelling, or low. "You haven't logged in twelve days" on a lock screen is
  the regrettable outcome, and it is exactly what a naive summariser produces. The entry
  floor exists for this, not for tidiness. Keep the nudge and the inactivity reminder
  semantically separate.
- **Failures here are silent by construction.** Notifications are off by default on
  Android 13+ and `notify()` is dropped with no exception and no log. Hence the Settings
  heartbeat row.
- **Never put generated text in logcat.** `adb logcat` is readable by anyone holding the
  phone. Log an identifier; keep bodies in app storage.
- **Facts, not rows.** Clients send pre-computed, pre-formatted `Fact` values over the
  binder. A 2B model is an unreliable arithmetician, and binder transactions cap near 1 MB.
- **Cannsheet must never send projections.** `AGENTS.md` there requires runway/spend
  projections not be persisted, transmitted, or treated as confirmed. `CannsheetLlmFacts`
  sends only recorded figures, and takes its period from `response.range`, never the device
  clock.
- **litertlm 0.16.1 needs coroutines 1.11.0.** Its POM declares 1.9.0 but it was compiled
  against newer; 1.9.0 and 1.10.2 both fail at runtime with
  `NoSuchMethodError: close$default(SendChannel…)` *after* a successful generation.
- **Pre-existing defect in `poop-schedule`:** `InactivityReminderWorker` uses the
  one-argument `PeriodicWorkRequestBuilder(1, TimeUnit.DAYS)`, which sets flex equal to the
  interval, so it can fire at any hour. `NudgeScheduler` uses the two-argument form; the
  reminder was deliberately left alone.
- **Samsung One UI** will put a weekly worker to sleep regardless. Best effort; say so in
  the UI rather than pretending.

## Environment

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
```

- No Android Studio. Build tools live at
  `/opt/homebrew/share/android-commandlinetools/build-tools/36.0.0` — that is where
  `aapt2` and `apksigner` are; they are not on `PATH`.
- `local.properties` in the two Drive repos points at a **Windows** SDK path; do not edit
  it. Build those from a throwaway `git worktree` outside Drive with its own
  `local.properties`, and `chmod +x gradlew` there.
- `adb` is at `/opt/homebrew/bin/adb`. The phone is already paired: `adb kill-server`, then
  `adb mdns services` for the `_adb-tls-connect` port, then `adb connect`. Do **not** re-pair.
- Screenshots need an explicit display id on the foldable:
  `adb exec-out screencap -p -d 4630946872173396372`.
- zsh does not word-split unquoted variables; inline `-s SERIAL` rather than using a var.
- The owner asked to be told whenever their phone is in use, and when it is free again.
- `noamvb/local-llm-releases` is an empty leftover repository; deleting it needs a
  `delete_repo` token scope that this setup does not have.
