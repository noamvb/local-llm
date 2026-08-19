# Handoff

Last updated: 2026-08-19. Updated after 0.1.4 was cut with engine prewarming on bind,
diagnostic timing telemetry, and manager screen load/TTFT displays.

## Where everything stands

| Repository | Released | State |
| --- | --- | --- |
| `noamvb/local-llm` | **v0.1.5** | Public. Releases publish in-repo. |
| `noamvb/poop-schedule` | **v1.2.3** | Private, releases to `poop-schedule-releases`. Insight card, nudge, loading indicator, scroll fix, and warmup binding shipped. |
| `noamvb/cannsheet-mobile` | **v1.4.3** | Public, releases to `cannsheet-mobile-releases`. Insight summary, loading indicator, scroll fix, and warmup binding shipped. |

All three publications were verified by downloading the asset and checking it, not by
trusting the pipeline:

| App | versionCode | Signer SHA-256 | Continuity |
| --- | --- | --- | --- |
| Poop Schedule 1.2.2 | 21 | `98198cd1…a55cde` | identical to 1.1.0/1.2.0/1.2.1 |
| Poop Schedule 1.2.3 | 22 | `98198cd1…a55cde` | identical to 1.2.2 |
| Cannsheet Mobile 1.4.2 | 38 | `a9787249…08665e` | identical to 1.3.4/1.4.0/1.4.1 |
| Cannsheet Mobile 1.4.3 | 39 | `a9787249…08665e` | identical to 1.4.2 |
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

## Do this first

1. Watch for the first real nudge and check the lock screen.
2. **Back up `/Users/sophiaparis/LocalLLM-signing/`.** Still outstanding. It exists nowhere
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
