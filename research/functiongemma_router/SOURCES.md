# Primary-source feasibility notes

Checked 2026-08-23. Awesome Gemma was used only to discover projects; it is not evidence
for a production decision. The links below are official Google documentation or the
official Google LiteRT-LM repository.

## What the sources establish

1. [FunctionGemma's official model card](https://ai.google.dev/gemma/docs/functiongemma/model_card)
   says the 270M model is a specialized function-calling foundation, is not intended as a
   direct dialogue model, and should be fine-tuned for the specific function vocabulary.
   Its published general and Mobile Actions results therefore do not establish accuracy
   for LocalLLM's aggregate-query grammar.
2. Google's [FunctionGemma fine-tuning guide](https://ai.google.dev/gemma/docs/functiongemma/finetuning-with-functiongemma)
   separates tool syntax from intent selection and demonstrates that task-specific tuning
   can materially change routing accuracy. Its small illustrative dataset and reported
   example scores are not a production acceptance standard.
3. The official [LiteRT-LM repository](https://github.com/google-ai-edge/LiteRT-LM)
   currently lists FunctionGemma 270M as a base model for which fine-tuning is required
   and publishes reference CPU measurements on a different Samsung device. That supports
   an executable feasibility spike, but neither those measurements nor the moving `main`
   branch prove the exact `v0.16.1`/intended-phone configuration.
4. LiteRT-LM `v0.16.1` exposes
   [`automaticToolCalling = false`](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.16.1/docs/api/kotlin/getting_started.md#manual-tool-calling),
   so the application can receive and validate a proposed call before executing any
   provider request. Automatic execution must remain disabled for this feature.
5. LiteRT-LM `v0.16.1` exposes a positive
   [`maxOutputToken`](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.16.1/kotlin/java/com/google/ai/edge/litertlm/Config.kt)
   bound and an `enableResponseFormat` switch in its Kotlin configuration. Its official
   [constrained-decoding documentation](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.16.1/docs/api/cpp/constrained-decoding.md)
   describes JSON Schema, regex, and Lark constraints. The exact Kotlin response-format
   path, FunctionGemma conversion metadata, and schema feature subset still require an
   executable spike against the exact artifact; documentation alone is not device proof.
6. Google's [Gemma Terms of Use](https://ai.google.dev/gemma/terms) apply to Gemma and
   model derivatives. The current distribution section requires downstream use
   restrictions, a copy of the agreement, modification notices, and a Notice file for
   non-hosted distribution. The incorporated
   [Gemma Prohibited Use Policy](https://ai.google.dev/gemma/prohibited_use_policy) must
   also be reviewed. This repository does not interpret those terms as legal approval.

## Feasibility conclusion

FunctionGemma is technically plausible as an optional router because it is small,
function-call specialized, fine-tunable, and supported by a runtime with manual tool
handling and constrained-output primitives. It is not a drop-in router. Google's own
model card and tuning guidance make domain-specific evaluation mandatory, and published
performance on other vocabularies cannot be transferred to this one.

The safe architecture is consequently:

1. deterministic code decides which source or sources the owner allowed;
2. FunctionGemma proposes only a `RouterDecision` inside that envelope;
3. schema, argument, source, ambiguity, and forbidden-operation validators accept or reject
   the proposal;
4. authoritative client providers compute and return bounded facts; and
5. the separate writer model phrases only validated facts.

No model-generated tool call is executed automatically.

## Unresolved evidence required before activation

- Pin the exact base checkpoint revision and independently record its hash/terms receipt.
- Establish whether the intended tuned/converted artifact may be redistributed in the
  proposed release channel and prepare all required notices; obtain an accountable human
  approval rather than relying on this note.
- Pin training, tokenizer, conversion, and LiteRT-LM revisions and prove that conversion
  preserves FunctionGemma's required chat/tool format.
- Determine the constrained-output feature subset that works through the exact Kotlin
  runtime and converted artifact.
- Measure exact-route accuracy, operation-level confusion, latency, peak memory, model-role
  switching, and thermal behavior on the intended phone.
- Expand the synthetic corpus and have humans review every supported, ambiguous,
  unsupported, and adversarial family before freezing a production split.
