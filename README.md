# LocalLLM

LocalLLM is a personal Android app that holds an on-device language model and lends it
to the owner's other apps. Cannsheet Mobile and Poop Schedule send it statistics they
have already calculated, and it sends back a short written summary. No data leaves the
phone, and the model runs with no network connection once it has been downloaded.

- Application ID: `com.noamv.localllm`
- Minimum Android version: Android 12 (API 31)
- Target SDK: Android 16 / API 36
- Runtime: [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) 0.16.1
- Model: Gemma 4 E2B instruction-tuned, Apache 2.0

## Why this exists as a separate app

A 2 GB model cannot be bundled into each tracker app. Hosting it once and exposing it
over a bound service means one download, one copy in memory, and one place to change the
model. It also keeps the tracker apps free of any inference dependency.

## How the pieces fit

```
Poop Schedule  ──┐
                 ├── AIDL bound service ──▶ LocalLLM ──▶ LiteRT-LM ──▶ Gemma 4 E2B
Cannsheet Mobile ┘   (signature-gated)                                (on-device)
```

Clients send **facts**, never rows. Every number is computed by the client before the
request is made; the model's only job is to turn those facts into sentences. See
`docs/API_CONTRACT.md` for the reasoning and the full request shape.

## Model storage

The model is downloaded on first use from the public, ungated
[litert-community](https://huggingface.co/litert-community) HuggingFace repositories and
verified against a known SHA-256 before it is used. It is stored in internal app storage
under `files/models`, is excluded from backup, and is removed when the app is uninstalled.

| Build | Size | Backend |
| --- | --- | --- |
| `gemma-4-E2B-it-gpu` | 2.01 GB | GPU, portable |
| `gemma-4-E2B-it-npu-sm8750` | 3.02 GB | NPU, Snapdragon 8 Elite only |
| `gemma-4-E2B-it` | 2.59 GB | CPU |
| `gemma-4-E4B-it-gpu` | 2.97 GB | GPU, larger and slower |

The app picks the NPU build automatically when it detects a matching chipset, and the
portable GPU build otherwise.

## Releases

Signed APKs are published as releases on this repository for installation through
Obtainium. Publishing in-repo means the built-in `GITHUB_TOKEN` is sufficient and no
personal access token is involved; the repository is public so Obtainium can read them.
This differs from Poop Schedule and Cannsheet, which keep private source and publish to a
separate public releases repository.

Publication is triggered only by pushing a `v*` tag, and `release-apk.yml` refuses to
publish unless the exact tagged commit already has a completed, successful **push-to-main**
run of "LocalLLM PR checks" with both jobs green. A green pull-request run does not
qualify. The tag must also be the current tip of `main`, `versionName` must equal the tag
with `v` stripped, and `versionCode` must exceed the published release's.

The signing keystore lives only in the `RELEASE_KEYSTORE_BASE64` repository secret and on
the owner's machine. It is never committed. Losing it means LocalLLM can never be updated
in place again.

## Build

Requires JDK 17 and Android SDK Platform 36.1.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
```

## Integrating a client app

Copy the `client/src/main` tree into the client module, add `aidl = true` to its
`buildFeatures`, and declare the permission and `<queries>` entry described at the top of
`client/src/main/java/com/noamv/localllm/client/LocalLlmClient.kt`.

The inference permission uses `signature|knownSigner`, so a client is granted access if
it is signed either by LocalLLM's own key or by a certificate listed in
`app/src/main/res/values/known_signers.xml`. The three apps in this family are signed by
three different keys, so that list is what makes the permission work at all — adding a new
client means adding its certificate digest and shipping a new LocalLLM.

**LocalLLM must be installed before the clients**, because Android only grants a
signature-level permission if the app defining it is already present.
