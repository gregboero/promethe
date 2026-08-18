# Evals-as-code

Promethe treats evaluation suites as versioned code. A suite describes inputs and deterministic assertions; an `EvalSubject` connects those cases to a component under test. The default CI run never calls a paid provider.

## Source of truth

| Concern | Source |
|---|---|
| Serializable contracts | `api/src/commonMain/kotlin/dev/promethe/api/EvalModels.kt` |
| Runner and assertions | `evals/src/main/kotlin/dev/promethe/evals/EvalRunner.kt` |
| Adversarial metrics | `evals/src/main/kotlin/dev/promethe/evals/AdversarialEvalLab.kt` |
| Golden suites | `evals/src/test/resources/golden/*.json` |
| CI regression tests | `evals/src/test/kotlin/dev/promethe/evals/` |

## Run the baseline

```powershell
./gradlew evals:test
```

The command runs the four Phase 0 baselines:

- agent-loop behavior;
- tool security and approvals;
- provider error handling;
- prompt-injection resistance.

An evaluation run fails when any assertion fails or when the subject raises an exception. Results retain the actual observed value but golden fixtures must never contain credentials, user data, raw prompts from production, or provider responses copied from private sessions.

## Suite format

Each JSON suite has a stable `id`, a positive `version`, and uniquely named cases. Every case declares a capability, an input, optional tags/provider/model metadata, and one or more assertions.

Supported assertions are:

- `EQUALS`
- `CONTAINS`
- `NOT_CONTAINS`
- `MATCHES_REGEX`
- `EXISTS`
- `NOT_EXISTS`

Assertions can inspect `output`, `errorCode`, `exitCode`, or a `metadata.<key>` value.

## Promotion rule

A prompt, model, skill, policy, tool contract, or autonomous behavior must not be promoted when a related golden suite regresses. New security defects require a negative regression case before the fix is considered complete.

Live-provider certification remains opt-in and separate from `evals:test`. It requires dedicated accounts, explicit credentials, a cost budget, and sanitized evidence as defined by the release certification documents.

## Adversarial metric

`AdversarialEvalLab` reports attack success rate (ASR): the proportion of adversarial cases that did not pass. The baseline is deterministic and runs in CI; scheduled generated attacks remain a later Phase 2 capability and must execute in an isolated environment.
