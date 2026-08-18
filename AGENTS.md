# LocalLLM contributor instructions

## Project overview

LocalLLM hosts an on-device language model and exposes it to the owner's other Android
apps over a signature-gated AIDL bound service. It is a single-module Kotlin application
with a Compose management UI. It has no chat surface: the UI exists to manage the model,
not to talk to it.

## Required reading

- `README.md` for the shape of the system;
- `docs/ARCHITECTURE.md` for boundaries and data flow;
- `docs/API_CONTRACT.md` for the cross-app contract and the rules that keep it safe.

Treat code as stronger evidence than documentation. If they disagree, verify the
behaviour and correct the document.

## Stack

Kotlin, Jetpack Compose/Material 3, coroutines/StateFlow, DataStore, OkHttp, and
LiteRT-LM for inference. AGP 9.1.1 with Kotlin 2.2.10; AGP 9 supplies the Kotlin plugin,
so `org.jetbrains.kotlin.android` must **not** be applied separately.

## Rules that are not negotiable

- **Clients send facts, never rows.** Any change that lets a client pass raw records, or
  that asks the model to compute a number, is wrong. The model narrates; it does not
  calculate. See `docs/API_CONTRACT.md`.
- **The safety defaults in `SafetyPolicy` stay strict by default.** Health claims,
  diagnosis, causal language and invented numbers are forbidden in the system
  instruction unless a caller deliberately opts out.
- **The inference permission stays `signature`.** Never lower it to `normal` or
  `dangerous`, and never export the service without it.
- **Model files are verified before use.** Never skip the SHA-256 check, and never keep
  a file that fails it.
- **Never commit keystores, credentials, or model binaries.**
- Keep dependency wiring manual, in `LocalLlmApplication`. Do not add a DI framework.

## Contract compatibility

`InsightContract.kt` and the two `.aidl` files are duplicated into every client app.
Changing them is a cross-repository change:

- Adding an optional field with a default is backward compatible; do that where possible.
- Removing or renaming a field, or changing a method signature, requires bumping
  `InsightContract.VERSION` and updating every client.
- The service must keep answering older clients. `getApiVersion()` exists for this.

## Verified checks

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew --no-daemon testDebugUnitTest assembleDebug
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew --no-daemon lintDebug
```

Report every check that was not run or did not pass. Do not describe an unexecuted check
as successful. Inference behaviour cannot be verified on an emulator; state plainly when
a change has only been verified by compilation.

## Change and release rules

- Propose work through a pull request targeting `main`, one coherent change per request.
- Do not change `versionCode`, `versionName`, tags, releases, or signing configuration
  unless the task explicitly requests release work.
- Publication is public and irreversible; confirm with the owner before pushing a tag.
