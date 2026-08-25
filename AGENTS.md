# LocalLLM contributor instructions

## Project overview

LocalLLM hosts an on-device language model (LiteRT-LM) and exposes it to client Android apps over a signature-gated AIDL bound service. It is a single-module Kotlin application with a Jetpack Compose management UI dedicated to model transfer, verification, and lifecycle management (inference occurs exclusively over AIDL).

## Required reading

Load these context pointers when working across system boundaries:

- `README.md`: System topology, setup, and component layout.
- `docs/ARCHITECTURE.md`: Process isolation, AIDL callback streaming, and UID/certificate security model.
- `docs/API_CONTRACT.md`: Wire JSON schema, task specifications, input bounds, and `SafetyPolicy` definitions.
- `docs/DECISIONS.md`: Architectural decision records and durable trade-offs.
- `docs/HANDOFF.md`: Active feature status, unreleased branch context, and validation evidence.

Treat code as the primary source of truth over documentation; verify behavior and update documentation when they diverge.

## Stack and build constraints

- **Core**: Kotlin, Jetpack Compose / Material 3, Coroutines / StateFlow, DataStore, OkHttp, LiteRT-LM.
- **Build**: AGP 9 supplies the Kotlin plugin; avoid applying `org.jetbrains.kotlin.android` separately.
- **DI**: Wire dependencies manually in `LocalLlmApplication` without external DI frameworks.

## Invariant rules

- **Fact-based narration**: Clients provide pre-computed, pre-formatted facts; the model strictly generates prose narration. Keep raw data rows and numerical computation in client domain logic (see `docs/API_CONTRACT.md`).
- **Strict safety defaults**: Enforce baseline `SafetyPolicy` in system instructions (factual prose only; omit health claims, diagnoses, causal language, and invented numbers) unless a client explicitly opts out. Require `lockScreenSafe` for `NUDGE` tasks.
- **Signature-gated access**: Restrict `InferenceService` using `android:protectionLevel="signature|knownSigner"`. Populate `app/src/main/res/values/known_signers.xml` solely with SHA-256 certificate digests verified directly from published client APKs.
- **Download integrity**: Require SHA-256 verification on all model artifacts before promotion; immediately discard failed or corrupted downloads.
- **Secret and asset hygiene**: Keep keystores, credentials, and model binaries out of version control.

## Contract compatibility

`InsightContract.kt` and the two `.aidl` interfaces define the cross-app wire ABI duplicated in each client app:

- **Non-breaking additions**: Add optional fields with default values to preserve backward compatibility.
- **Breaking changes**: Removing/renaming fields or altering method signatures requires incrementing `InsightContract.VERSION` and updating all client applications.
- **Legacy client support**: Maintain compatibility for prior contract versions via `getApiVersion()`.

## Verified checks

Execute local verification with JDK 17+:

```bash
./gradlew --no-daemon testDebugUnitTest assembleDebug
./gradlew --no-daemon lintDebug
```

- **Outcome reporting**: Record exact commands executed and their outcomes. Explicitly disclose any unexecuted, skipped, or failing checks.
- **Inference validation**: Emulators cannot execute on-device inference; state explicitly when changes are verified via compilation and unit tests alone.

## Change and release rules

- **Pull requests**: Target `main` with one coherent change per pull request.
- **Release isolation**: Preserve `versionCode`, `versionName`, signing configuration, and tags unless the task explicitly specifies release operations.
- **Publication approval**: Confirm with the repository owner prior to pushing release tags or publishing artifacts.

## Agent skills and routing

- **Issue tracking** (`docs/agents/issue-tracker.md`): Trigger when reading, creating, commenting on, or managing GitHub issues via `gh`.
- **Triage labels** (`docs/agents/triage-labels.md`): Trigger when applying canonical triage roles (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`).
- **Domain vocabulary** (`docs/agents/domain.md`): Trigger when defining domain concepts or checking ADR consistency in `docs/DECISIONS.md`.
