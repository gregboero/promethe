---
name: dspy
description: "Declarative prompt optimization with DSPy — auto-compile and optimize LLM pipelines."
source: BUNDLED
requires_cli: python,uv
platforms: jvm
---

# DSPy — Promethe Guide

[DSPy](https://dspy.ai/) is a framework for programming LLM pipelines declaratively. Instead of hand-writing prompts, you define **signatures** (input/output specs) and let DSPy optimize the prompts automatically.

## When to Use

- You have a repeatable LLM task (classification, extraction, QA, summarization)
- You have examples of good input→output pairs
- You want to **optimize prompts automatically** instead of tweaking them manually
- You want to swap models without rewriting prompts

## Install

```
execute_command(command="uv", args=["pip", "install", "dspy"])
```

## Core Concepts

### 1. Signatures — Define what, not how
```python
class Sentiment(dspy.Signature):
    """Classify the sentiment of a text."""
    text: str = dspy.InputField()
    sentiment: str = dspy.OutputField(desc="positive, negative, or neutral")
```

### 2. Modules — Compose reasoning strategies
```python
classifier = dspy.ChainOfThought(Sentiment)
result = classifier(text="This product is amazing!")
# result.sentiment = "positive"
```

### 3. Optimizers — Auto-tune prompts from examples
```python
optimizer = dspy.MIPROv2(metric=accuracy_metric, auto="medium")
optimized = optimizer.compile(classifier, trainset=examples)
```

## Key Modules

| Module | Strategy |
|--------|----------|
| `dspy.Predict` | Direct prediction |
| `dspy.ChainOfThought` | Think step-by-step before answering |
| `dspy.ProgramOfThought` | Generate code to compute the answer |
| `dspy.ReAct` | Reason + Act with tool use |
| `dspy.MultiChainComparison` | Generate multiple chains, pick best |

## Integration with Promethe

```
execute_command(command="uv", args=["run", "--with", "dspy", "python", "optimize_pipeline.py"])
```

Or inline:
```
execute_command(command="python", args=["-c", "import dspy; lm = dspy.LM('openai/gpt-4o'); dspy.configure(lm=lm); ..."])
```
