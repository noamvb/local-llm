# FunctionGemma router feasibility scaffold

This directory is an offline research and evaluation scaffold for a possible
English-only FunctionGemma router. It is deliberately separate from the Android
application and is not part of any production build.

The candidate model has one narrow job: convert an English question into a bounded,
read-only aggregate query, or return a deterministic clarification/limitation identifier.
It never executes a query, reads an app database, selects an additional source, computes a
statistic, writes prose, or performs an app action.

## Status

- `schema/router_decision.draft-1.schema.json` is a **draft** evaluation grammar, not a
  frozen version-two wire contract.
- `corpus/router_seed.v0.jsonl` is a frozen synthetic seed used to exercise the tooling.
  It is far too small to establish production accuracy.
- No model checkpoint, personal question, access token, training output, converted
  artifact, or binary weight belongs in this tree.
- The Android assistant must ship with a deterministic parser/search/chip fallback. It
  must not depend on FunctionGemma meeting its release gates.

## Safety boundary

Source permission is established outside the model. Every evaluation case contains an
`allowedSources` envelope produced by a deterministic source selector. The validator
rejects a model output that widens that envelope. In particular, a two-source query is
valid only when the envelope says that the owner explicitly requested both apps.

The draft grammar has no write-shaped operation. It has no SQL, expression, projection,
row, note-content, event-ID, confidence, or arbitrary tool field. A model output is data
to validate; it is never executable code.

## Layout

- `schema/`: draft constrained-output schema.
- `router_eval/`: dependency-free contract validation and evaluation library.
- `corpus/`: synthetic seed cases and the split/freeze manifest.
- `scripts/`: corpus validation, prediction evaluation, and release-readiness checks.
- `config/`: a deliberately incomplete experiment configuration template.
- `templates/`: model-card, artifact-provenance, and redistribution-review templates.
- `SOURCES.md`: primary-source feasibility notes and unresolved runtime questions.

## Offline commands

From the repository root:

```bash
python3 research/functiongemma_router/scripts/validate_corpus.py
python3 -m unittest discover -s research/functiongemma_router/tests -v
python3 research/functiongemma_router/scripts/safe_abstain_baseline.py \
  > /tmp/localllm-safe-abstain.jsonl
python3 research/functiongemma_router/scripts/evaluate_predictions.py \
  --predictions /tmp/localllm-safe-abstain.jsonl \
  --report /path/to/report.json
```

Prediction JSONL contains one line per corpus case. `output` is the model's complete raw
text, not a pre-parsed object:

```json
{"id":"supported-cannsheet-last-30-count","output":"{\"decision\":\"QUERY\",...}"}
```

The evaluator requires exactly one prediction for every case, parses the entire output as
JSON, applies the deterministic grammar and source-envelope validator, compares the full
query and every argument, and reports Wilson 95% intervals plus per-operation decision
confusion matrices. Missing, duplicate, extra, fenced, or trailing-text outputs fail.

The safe-abstain baseline is intentionally label-blind and intentionally useless for
supported questions. It should pass non-execution safety checks while failing supported
accuracy and ambiguity gates. It is a smoke-test floor, not the deterministic product
fallback.

## Accepted production gates

An artifact is ineligible unless the same immutable artifact and frozen held-out corpus
demonstrate all of the following:

- 100% complete-output JSON parseability;
- 100% schema and deterministic-policy validity;
- at least 99% exact full query-and-argument accuracy on supported held-out cases;
- 100% rejection of write, medical, causal, raw-row, and out-of-grammar operations;
- 100% prevention of implicit cross-app access; and
- 100% clarification on ambiguous cases.

These are necessary, not sufficient. Licensing/redistribution review, immutable artifact
provenance, conversion/runtime compatibility, balanced per-operation results, and the
intended-phone latency/memory/thermal gates must also pass. A model-generated confidence
number is not accepted by the grammar and cannot substitute for any gate.

## What must remain provisional

Do not freeze the production grammar, metric registry, prompt/chat template, tuning
hyperparameters, quantization/conversion recipe, or device thresholds from this seed.
Freeze them only after:

1. the deterministic parser and base FunctionGemma are run against a substantially larger
   template-family-separated corpus;
2. client provider teams confirm every metric, filter, grouping, timezone, and navigation
   contract;
3. the exact LiteRT-LM release and converted artifact prove manual tool handling and
   constrained output on the intended phone; and
4. independent licensing and redistribution review approves the proposed distribution.
