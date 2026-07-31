---
name: structured-extraction
description: "Extract structured data from LLM responses using Instructor, Outlines, or Guidance libraries."
source: BUNDLED
requires_cli: python,uv
platforms: jvm
---

# Structured LLM Extraction — Promethe Guide

Use Python libraries to force LLMs to output structured, validated data (JSON, Pydantic models, constrained formats).

## Available Tools

### 1. Instructor — Pydantic-based extraction
Best for: Extracting typed data from any LLM into Pydantic models.

```python
# Install: pip install instructor
import instructor
from pydantic import BaseModel
from openai import OpenAI

class User(BaseModel):
    name: str
    age: int

client = instructor.from_openai(OpenAI())
user = client.chat.completions.create(
    model="gpt-4o",
    response_model=User,
    messages=[{"role": "user", "content": "Extract: John is 25 years old"}]
)
print(user)  # User(name='John', age=25)
```

Via Promethe:
```
execute_command(command="python", args=["-c", "import instructor; ..."])
```

### 2. Outlines — Constrained generation
Best for: Forcing exact output format (regex, JSON schema, choice from list).

```python
# Install: pip install outlines
import outlines

model = outlines.models.transformers("mistralai/Mistral-7B-v0.1")
generator = outlines.generate.choice(model, ["positive", "negative"])
result = generator("This movie was great!")  # "positive"
```

### 3. Guidance — Grammar-controlled generation
Best for: Template-based generation with interleaved computation.

```python
# Install: pip install guidance
from guidance import models, gen, select

model = models.OpenAI("gpt-4o")
result = model + "Pick a color: " + select(["red", "blue", "green"])
```

### 4. DSPy — Declarative prompt optimization
Best for: Optimizing prompts automatically through examples.

```python
# Install: pip install dspy
import dspy

lm = dspy.LM("openai/gpt-4o")
dspy.configure(lm=lm)

class QA(dspy.Signature):
    question: str = dspy.InputField()
    answer: str = dspy.OutputField()

qa = dspy.ChainOfThought(QA)
result = qa(question="What is the capital of France?")
```

## When to Use What

| Library | Best For | Speed | Validation |
|---------|----------|-------|------------|
| **Instructor** | API-based extraction | Fast (API) | Strong (Pydantic) |
| **Outlines** | Local model constraints | Medium | Regex/Schema |
| **Guidance** | Template generation | Medium | Grammar |
| **DSPy** | Prompt optimization | Slow (iterative) | Example-based |

## Integration Pattern

All these tools are Python libraries. Use via Promethe's `execute_command`:

```
execute_command(command="uv", args=["run", "--with", "instructor", "python", "script.py"])
```

Or via MCP server if configured:
```
mcp_call(server="python-tools", tool="instructor_extract", args={...})
```
