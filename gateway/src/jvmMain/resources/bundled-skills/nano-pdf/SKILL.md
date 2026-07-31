---
name: nano-pdf
description: "Edit PDF text/typos/titles via nano-pdf CLI (NL prompts)."
source: BUNDLED
requires_cli: nano-pdf
platforms: jvm
---

# nano-pdf

Edit PDFs using natural-language instructions. Point it at a page and describe what to change.

## Prerequisites

```
execute_command(command="pip", args=["install", "nano-pdf"])
```

Or with uv:
```
execute_command(command="uv", args=["pip", "install", "nano-pdf"])
```

## Usage

```
execute_command(command="nano-pdf", args=["edit", "<file.pdf>", "<page_number>", "<instruction>"])
```

## Examples

```
# Change a title on page 1
execute_command(command="nano-pdf", args=["edit", "deck.pdf", "1", "Change the title to 'Q3 Results' and fix the typo in the subtitle"])

# Update a date on a specific page
execute_command(command="nano-pdf", args=["edit", "report.pdf", "3", "Change the date from 2025 to 2026"])

# Fix a typo
execute_command(command="nano-pdf", args=["edit", "contract.pdf", "2", "Fix the spelling of 'recieve' to 'receive'"])
```

## Notes

- Edits are applied in-place by default.
- Works best with text-based PDFs (not scanned images).
- For OCR-based documents, use the `ocr-and-documents` skill first.
