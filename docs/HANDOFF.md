# Handoff

Last updated: 2026-08-18. Written because a session ended mid-feature.

## Where everything stands

| Repository | State |
| --- | --- |
| `noamvb/local-llm` | **v0.1.0 released** and public. Releases publish in-repo; no PAT needed. Open: **PR #1** (foreground-service fix, described below). |
| `noamvb/poop-schedule` | **v1.1.0 released**, insight card verified end to end on real records. Open: branch `feature/localllm-nudge` (WIP, not functional). |
| `noamvb/cannsheet-mobile` | Open: **PR #94**, insight card. All checks green. Not device-verified. ADR-024 merged. |

On the phone (Galaxy Z Fold 7, `SM8750`, Android 16): release-signed LocalLLM 0.1.0 with
the Gemma 4 E2B GPU model downloaded, and release-signed Poop Schedule 1.1.0. The card
works.

## Do this first

1. **Merge PR #1 on `local-llm` and release v0.1.1.** It removes a latent crash. Follow
   the release gate: land it, wait for the push-to-`main` run on the exact SHA to go green
   on both jobs, bump `versionCode`/`versionName`, then tag. The tag must be the tip of
   `main`.
2. Decide on **PR #94** (Cannsheet). It is green but has never run on a device.
3. Continue the nudge, below.

## The nudge: what is done and what blocks it

Branch `feature/localllm-nudge` on `poop-schedule`, commit `b213b0c`. It compiles nothing
useful yet and delivers no notification. `NudgeWorker.doWork` returns early with a TODO.

Done and unit-tested: `NudgeSchedule` (due check), `NudgeText` (output sanitising), the
notification channel, the settings, and the WorkManager trigger.

**The blocker, and it is a real one.** The worker cannot simply bind LocalLLM and wait.

An app targeting API 31+ cannot start a foreground service from the background. For a
*bound* service the system does not evaluate the service's own state — it walks the
binding clients and re-runs the check against each
(`ActiveServices.canBindingClientStartFgsLocked`, yielding `REASON_FGS_BINDING`). Binding
**propagates** a client's existing eligibility; it never creates any. A client running an
ordinary WorkManager job has none.

So the original plan — worker binds, LocalLLM promotes itself for the generation — fails,
and fails badly: `ForegroundServiceStartNotAllowedException` is thrown into *LocalLLM's*
process, gated on *LocalLLM's* `targetSdk`, where the client cannot catch it. LocalLLM
would die mid-generation and the client would see only `DeadObjectException`. PR #1 removes
that promotion entirely, which stops the crash but leaves long background generations
unprotected.

Dead ends, already checked so nobody re-checks them:

- `setExpedited` does not help. On API 31+ it is a JobScheduler expedited job, not a
  foreground service, and confers no ability to start one.
- There is no usable bind flag. `BIND_ALLOW_FOREGROUND_SERVICE_STARTS_FROM_BACKGROUND` is
  `@hide`, `@SystemApi`, deprecated and permission-gated.
- Exact alarms give a foreground-service allowlist window of about **ten seconds**, nowhere
  near enough for a cold Gemma load.

**The remaining work**, in order:

1. Add a `specialUse` foreground service **owned by Poop Schedule**. Not `shortService` —
   AOSP explicitly withholds `PROCESS_CAPABILITY_BFSL` from short services. Needs
   `FOREGROUND_SERVICE_SPECIAL_USE` and the subtype `<property>`. Its own notification must
   be neutral ("Preparing weekly summary"), carry no statistic, and be `VISIBILITY_PRIVATE`
   — it is on the lock screen too while generation runs.
2. Make the client foreground-service eligible. The only exemption a headless weekly worker
   can hold is the battery-optimisation allowlist: prompt once with
   `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, or the owner sets Battery → Unrestricted.
3. Add `Repository.entriesSince(Instant): Int`. It does not exist.
4. Have the worker start that service, bind, request `InsightTask.NUDGE`, validate, post,
   then `setLastNudgeAt`.

## Decisions already made with the owner

- **Lock screen: full detail, but not while screen sharing.** The owner asked for no
  content restriction and was then told that `VISIBILITY_PUBLIC` also exposes content
  during casting, DeX and screen sharing. They chose full detail on the lock screen with a
  neutral `setPublicVersion` for the shared-screen case. **Not yet implemented.**
- **The model writes freely** from the facts rather than phrasing an app-selected fact.
  The owner chose this against the recommendation, which makes validation heuristic rather
  than complete, so `NudgeText` must carry more weight.
- **Numeric grounding is therefore required and not yet written.** Every number in the
  generated sentence must appear in the fact set the client computed. The trap: the model
  writes both `"33 entries"` and `"twelve entries"` — observed, both real outputs — so
  number words must be normalised to digits first or the check passes trivially by finding
  no digits at all.
- Still to add to `NudgeText`: a clinical-language denylist (`diagnos`, `symptom`, `IBS`,
  `see a doctor`, healthy/unhealthy as a verdict) and a single-sentence shape check.

## Things that will bite

- **Never narrate an absence.** A gap in logging is itself sensitive — people stop logging
  when unwell, travelling, or low. "You haven't logged in twelve days" on a lock screen is
  the regrettable outcome, and it is exactly what a naive summariser produces. The
  four-entry floor exists for this, not for tidiness. Keep the nudge and the existing
  inactivity reminder semantically separate.
- **Every failure in this feature is silent by construction.** Notifications are off by
  default on Android 13+ and `notify()` is dropped with no exception and no log. The worker
  already gates on this, but the feature still needs a visible in-app row — "Last nudge:
  Aug 11, posted" / "skipped: notifications disabled" / "skipped: only 2 entries" — or it
  is undebuggable.
- **Never put generated text in logcat.** `adb logcat` is readable by anyone with the
  phone. Log an identifier; keep bodies in app storage.
- **Pre-existing defect in `poop-schedule`:** `PeriodicWorkRequestBuilder(1, TimeUnit.DAYS)`
  for `InactivityReminderWorker` uses the one-argument form, which sets flex equal to the
  interval, so the reminder can fire at any hour. `NudgeScheduler` uses the two-argument
  form; the reminder was left alone.
- **Samsung One UI** will put a weekly worker to sleep regardless. Best effort; say so in
  the UI rather than pretending.

## Environment

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
```

- No Android Studio. `local.properties` in the two Drive repos points at a **Windows** SDK
  path; do not edit it. Build those from a throwaway `git worktree` outside Drive with its
  own `local.properties`, and `chmod +x gradlew` there.
- `adb` is at `/opt/homebrew/bin/adb`. The phone is already paired: `adb kill-server`, then
  `adb mdns services` for the `_adb-tls-connect` port, then `adb connect`. Do **not** re-pair.
- Screenshots need an explicit display id on the foldable:
  `adb exec-out screencap -p -d 4630946872173396372`.
- zsh does not word-split unquoted variables; inline `-s SERIAL` rather than using a var.
- The signing keystore is at `/Users/sophiaparis/LocalLLM-signing/`. **It exists nowhere
  else except the repository secret.** Losing it makes LocalLLM permanently un-updatable.
- `noamvb/local-llm-releases` is an empty leftover repository; deleting it needs a
  `delete_repo` token scope.
