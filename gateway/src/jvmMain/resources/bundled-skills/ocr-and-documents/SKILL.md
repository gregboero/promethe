---
name: ocr-and-documents
description: "Extract text from PDFs/scans (pymupdf, marker-pdf)."
source: BUNDLED
requires_cli: python3
platforms: jvm
---

# PDF & Document Extraction

For DOCX: use `python-docx` (parses actual document structure, far better than OCR).
For PPTX: see the `powerpoint` skill (uses `python-pptx` with full slide/notes support).
This skill covers **PDFs and scanned documents**.

## Step 1: Remote URL Available?

If the document has a URL, **always try `http_fetch` first**:

```
http_fetch(url="https://arxiv.org/pdf/2402.03300", method="GET")
http_fetch(url="https://example.com/report.pdf", method="GET")
```

This handles PDF content retrieval with no local dependencies. Only use local extraction when: the file is local, http_fetch fails, or you need batch processing.

## Step 2: Choose Local Extractor

| Feature | pymupdf (~25MB) | marker-pdf (~3-5GB) |
|---------|-----------------|---------------------|
| **Text-based PDF** | ✅ | ✅ |
| **Scanned PDF (OCR)** | ❌ | ✅ (90+ languages) |
| **Tables** | ✅ (basic) | ✅ (high accuracy) |
| **Equations / LaTeX** | ❌ | ✅ |
| **Code blocks** | ❌ | ✅ |
| **Forms** | ❌ | ✅ |
| **Headers/footers removal** | ❌ | ✅ |
| **Reading order detection** | ❌ | ✅ |
| **Images extraction** | ✅ (embedded) | ✅ (with context) |
| **Images → text (OCR)** | ❌ | ✅ |
| **EPUB** | ✅ | ✅ |
| **Markdown output** | ✅ (via pymupdf4llm) | ✅ (native, higher quality) |
| **Install size** | ~25MB | ~3-5GB (PyTorch + models) |
| **Speed** | Instant | ~1-14s/page (CPU), ~0.2s/page (GPU) |

**Decision**: Use pymupdf unless you need OCR, equations, forms, or complex layout analysis.

## Step 3: Install & Run

### pymupdf (lightweight, text-based PDFs)

```
execute_command(command="pip", args=["install", "pymupdf", "pymupdf4llm"])
```

```python
# Extract to markdown
execute_command(command="python3", args=["-c", "import pymupdf4llm; print(pymupdf4llm.to_markdown('document.pdf'))"])
```

### marker-pdf (heavy, OCR + complex layouts)

```
execute_command(command="pip", args=["install", "marker-pdf"])
```

```
execute_command(command="marker_single", args=["document.pdf", "--output_dir", "./output"])
```

If the user needs marker capabilities but the system lacks ~5GB free disk:
> "This document needs OCR/advanced extraction (marker-pdf), which requires ~5GB for PyTorch and models. Options: free up space, provide a URL so I can use http_fetch, or I can try pymupdf which works for text-based PDFs but not scanned documents or equations."
