# LocalLLM router artifact model card

Status: `DRAFT / BLOCKED / RELEASED`

This card describes one immutable tuned FunctionGemma router artifact. Replace every
`REQUIRED` field with recorded evidence; never copy benchmark values from Google's model
card or a different artifact/device.

## Identity and intended use

- Artifact ID/version: REQUIRED
- SHA-256 and byte size: REQUIRED
- Base checkpoint and immutable revision: REQUIRED
- Base-checkpoint SHA-256: REQUIRED
- Grammar version: REQUIRED
- Language: English only
- Runtime/conversion version: REQUIRED
- Intended device class: REQUIRED

The artifact may propose a bounded, read-only LocalLLM `RouterDecision`. Deterministic
code supplies the allowed source envelope, validates every output, and performs any
provider call. The model cannot execute tools and cannot widen source access.

It is not a dialogue model, writer, calculator, medical model, correlation engine, source
selector, authorization mechanism, database interface, or app-action agent.

## Training data and method

- Synthetic dataset manifest(s) and SHA-256: REQUIRED
- Semantic-template-family split logic: REQUIRED
- Human review method and reviewers: REQUIRED
- Training seed and hyperparameter/config SHA-256: REQUIRED
- Training code revision and package/container lock: REQUIRED
- Personal owner questions used for training: **No**

Describe generation families, deduplication, contamination checks, and how paraphrases
were kept in one split. State dataset limitations and underrepresented operations.

## Evaluation

Name the immutable artifact and frozen corpus in every row.

| Gate | Required | Result | Wilson 95% interval | Evidence |
| --- | ---: | ---: | --- | --- |
| Complete-output parseability | 100% | REQUIRED | REQUIRED | REQUIRED |
| Schema and policy validity | 100% | REQUIRED | REQUIRED | REQUIRED |
| Exact full query and arguments | >=99% | REQUIRED | REQUIRED | REQUIRED |
| Forbidden-operation rejection | 100% | REQUIRED | REQUIRED | REQUIRED |
| Implicit cross-app prevention | 100% | REQUIRED | REQUIRED | REQUIRED |
| Ambiguous-case clarification | 100% | REQUIRED | REQUIRED | REQUIRED |

Attach per-operation confusion matrices. Discuss every failure; a high aggregate score may
not conceal a weak metric, period, grouping, filter, source, or refusal family. Generated
confidence values are ignored and are not part of the grammar.

## Intended-phone evidence

- Warm router p50/p95 and sample count: REQUIRED
- Peak RSS and measurement method: REQUIRED
- Router/writer alternation: REQUIRED
- One-role residency and idle unload: REQUIRED
- OOM/service-death/thermal observations: REQUIRED
- Exact APK/runtime/artifact identifiers: REQUIRED

Google's published FunctionGemma results use different tasks and hardware and must not be
reported as evidence for this artifact.

## Safety and privacy

Document tests for write/delete/edit/archive/restore/timer/sync/purchase/log operations;
medical, causal, treatment and behavioral-advice requests; raw rows, notes, identifiers,
SQL and model calculations; prompt injection; Unicode controls; extreme/malformed inputs;
source ambiguity; and implicit cross-app access.

Owner questions remain on-device evaluation only. No personal facts, questions, prompts,
generated answers, or normal-turn telemetry were used for training or uploaded.

## Limitations and fallback

List known linguistic, entity-resolution, period, negation, multi-request, and device
limitations. If any required gate fails, the artifact is not enabled; the product keeps
the deterministic parser/search/intent-chip/clarification path.

## Terms and distribution

- Gemma terms version/date reviewed: REQUIRED
- Terms accepted by/date: REQUIRED
- Redistribution decision, approver, and evidence: REQUIRED
- Agreement copy, Notice file, modification notices, downstream restrictions: REQUIRED
- Prohibited Use Policy review: REQUIRED
- Signed manifest and independent redownload verification: REQUIRED

This card records a review; it is not legal advice and does not itself grant permission to
redistribute weights or derivatives.
