---
name: instructor
description: "Extract structured data from LLM responses into validated Pydantic models."
source: BUNDLED
requires_cli: python,uv
platforms: jvm
---

# Instructor — Promethe Guide

[Instructor](https://python.useinstructor.com/) patches any LLM client (OpenAI, Anthropic, Google, Mistral, Ollama) to return **validated Pydantic models** instead of raw text.

## When to Use

- You need **typed, validated JSON** from an LLM
- You want automatic **retries** when the model outputs invalid data
- You're building extraction pipelines (entities, classifications, structured data)
- You want **streaming** of partial structured objects

## Install

```
execute_command(command="uv", args=["pip", "install", "instructor"])
```

## Core Pattern

```python
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

## Multi-Provider Support

```python
# Anthropic
import anthropic
client = instructor.from_anthropic(anthropic.Anthropic())

# Google Gemini
import google.generativeai as genai
client = instructor.from_gemini(genai.GenerativeModel("gemini-3.5-flash"))

# Ollama (local)
from openai import OpenAI
client = instructor.from_openai(OpenAI(base_url="http://localhost:11434/v1"), mode=instructor.Mode.JSON)
```

## Advanced Patterns

### Streaming partial objects
```python
for partial in client.chat.completions.create_partial(
    model="gpt-4o",
    response_model=User,
    messages=[...],
):
    print(partial)  # User(name='John', age=None) → User(name='John', age=25)
```

### Retry with validation
```python
from pydantic import field_validator

class ValidatedUser(BaseModel):
    name: str
    age: int

    @field_validator("age")
    def validate_age(cls, v):
        if v < 0 or v > 150:
            raise ValueError("Age must be between 0 and 150")
        return v

# Instructor auto-retries up to max_retries when validation fails
user = client.chat.completions.create(
    model="gpt-4o",
    response_model=ValidatedUser,
    max_retries=3,
    messages=[...],
)
```

## Integration with Promethe

```
execute_command(command="uv", args=["run", "--with", "instructor,openai", "python", "extract.py"])
```
