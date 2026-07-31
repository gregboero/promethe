---
name: arxiv
description: "Search arXiv papers by keyword, author, category, or ID."
source: BUNDLED
platforms: jvm
---

# arXiv Research

Search and retrieve academic papers from arXiv via their free REST API. No API key, no dependencies — just HTTP requests.

## Quick Reference

| Action | Method |
|--------|--------|
| Search papers | `http_fetch(url="https://export.arxiv.org/api/query?search_query=all:QUERY&max_results=5", method="GET")` |
| Get specific paper | `http_fetch(url="https://export.arxiv.org/api/query?id_list=2402.03300", method="GET")` |
| Read abstract | `http_fetch(url="https://arxiv.org/abs/2402.03300", method="GET")` |
| Read full paper (HTML) | `http_fetch(url="https://arxiv.org/html/2402.03300", method="GET")` |

## Searching Papers

The API returns Atom XML. Parse with a script or extract key fields manually.

### Basic search

```
http_fetch(url="https://export.arxiv.org/api/query?search_query=all:GRPO+reinforcement+learning&max_results=5", method="GET")
```

### Clean output (parse XML to readable format)

Use `execute_command` to run a Python one-liner that parses the XML:

```bash
python -c "
import urllib.request, xml.etree.ElementTree as ET
data = urllib.request.urlopen('https://export.arxiv.org/api/query?search_query=all:GRPO+reinforcement+learning&max_results=5&sortBy=submittedDate&sortOrder=descending').read()
ns = {'a': 'http://www.w3.org/2005/Atom'}
root = ET.fromstring(data)
for i, entry in enumerate(root.findall('a:entry', ns)):
    title = entry.find('a:title', ns).text.strip().replace('\n', ' ')
    arxiv_id = entry.find('a:id', ns).text.strip().split('/abs/')[-1]
    published = entry.find('a:published', ns).text[:10]
    authors = ', '.join(a.find('a:name', ns).text for a in entry.findall('a:author', ns))
    summary = entry.find('a:summary', ns).text.strip()[:200]
    cats = ', '.join(c.get('term') for c in entry.findall('a:category', ns))
    print(f'{i+1}. [{arxiv_id}] {title}')
    print(f'   Authors: {authors}')
    print(f'   Published: {published} | Categories: {cats}')
    print(f'   Abstract: {summary}...')
    print(f'   PDF: https://arxiv.org/pdf/{arxiv_id}')
    print()
"
```

## Search Query Syntax

| Prefix | Searches | Example |
|--------|----------|---------|
| `all:` | All fields | `all:transformer+attention` |
| `ti:` | Title | `ti:large+language+models` |
| `au:` | Author | `au:vaswani` |
| `abs:` | Abstract | `abs:reinforcement+learning` |
| `cat:` | Category | `cat:cs.AI` |
| `co:` | Comment | `co:accepted+NeurIPS` |

### Boolean operators

```
# AND (default when using +)
search_query=all:transformer+attention

# OR
search_query=all:GPT+OR+all:BERT

# AND NOT
search_query=all:language+model+ANDNOT+all:vision

# Exact phrase
search_query=ti:"chain+of+thought"

# Combined
search_query=au:hinton+AND+cat:cs.LG
```

## Sort and Pagination

| Parameter | Options |
|-----------|---------|
| `sortBy` | `relevance`, `lastUpdatedDate`, `submittedDate` |
| `sortOrder` | `ascending`, `descending` |
| `start` | Result offset (0-based) |
| `max_results` | Number of results (default 10, max 30000) |

```
# Latest 10 papers in cs.AI
http_fetch(url="https://export.arxiv.org/api/query?search_query=cat:cs.AI&sortBy=submittedDate&sortOrder=descending&max_results=10", method="GET")
```

## Fetching Specific Papers

```
# By arXiv ID
http_fetch(url="https://export.arxiv.org/api/query?id_list=2402.03300", method="GET")

# Multiple papers
http_fetch(url="https://export.arxiv.org/api/query?id_list=2402.03300,2401.12345,2403.00001", method="GET")
```

## BibTeX Generation

After fetching metadata for a paper, generate a BibTeX entry from the XML fields:

```
@article{author2024title,
  title={Paper Title},
  author={Author Names},
  journal={arXiv preprint arXiv:XXXX.XXXXX},
  year={2024}
}
```

Use `http_fetch` to retrieve the paper metadata, then extract author, title, year, and ID to construct the BibTeX programmatically. **Never generate BibTeX from memory.**
