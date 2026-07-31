---
name: research-paper-writing
description: "Write ML papers for NeurIPS/ICML/ICLR: design→submit."
source: BUNDLED
requires_cli: python,git
platforms: jvm
---

# Research Paper Writing Pipeline

End-to-end pipeline for producing publication-ready ML/AI research papers targeting **NeurIPS, ICML, ICLR, ACL, AAAI, and COLM**. Covers experiment design, execution, analysis, paper writing, review, and submission.

## When To Use

- Starting a new research paper from an existing codebase or idea
- Designing and running experiments to support paper claims
- Writing or revising any section of a research paper
- Preparing for submission to a specific conference
- Responding to reviews with additional experiments or revisions

## Core Philosophy

1. **Be proactive.** Deliver complete drafts, not questions.
2. **Never hallucinate citations.** Always fetch programmatically. Mark unverifiable citations as `[CITATION NEEDED]`.
3. **Paper is a story, not a collection of experiments.** Every paper needs one clear contribution in a single sentence.
4. **Experiments serve claims.** Every experiment must explicitly state which claim it supports.
5. **Commit early, commit often.** Every completed experiment batch gets committed with a descriptive message.

## Phase 0: Project Setup

### Explore the Repository

Use `execute_command` to understand the project structure:
```bash
find . -name "*.py" | head -30
grep -r "arxiv\|doi\|cite" --include="*.md" --include="*.bib" --include="*.py"
```

### Organize the Workspace

```
workspace/
  paper/               # LaTeX source, figures, compiled PDFs
  experiments/         # Experiment runner scripts
  code/                # Core method implementation
  results/             # Raw experiment results (auto-generated)
```

### Identify the Contribution

Before writing anything, articulate:
- **The What**: What is the single thing this paper contributes?
- **The Why**: What evidence supports it?
- **The So What**: Why should readers care?

## Phase 1: Literature Review

### Search for Related Work

Use `http_fetch` to query the arXiv API and Semantic Scholar:

```
http_fetch(url="https://export.arxiv.org/api/query?search_query=all:YOUR_TOPIC&max_results=10&sortBy=submittedDate&sortOrder=descending", method="GET")
```

Use iterative breadth-then-depth search:
1. **Round 1 (Breadth)**: 4-6 parallel queries covering different angles
2. **Round 2 (Depth)**: Follow-up queries from Round 1 learnings
3. **Round 3 (Targeted)**: Fill specific gaps — stop when >80% papers are already known

For parallel queries, use `delegate_task` to dispatch multiple search agents simultaneously.

### Verify Every Citation (MANDATORY)

```
1. SEARCH → Query Semantic Scholar or arXiv with specific keywords
2. VERIFY → Confirm paper exists in 2+ sources
3. RETRIEVE → Get BibTeX via DOI content negotiation (programmatically)
4. VALIDATE → Confirm the claim you're citing actually appears in the paper
5. ADD → Add verified BibTeX to bibliography
If ANY step fails → mark as [CITATION NEEDED]
```

## Phase 2: Experiment Design

### Map Claims to Experiments

| Claim | Experiment | Expected Evidence |
|-------|-----------|-------------------|
| "Our method outperforms baselines" | Main comparison (Table 1) | Win rate, statistical significance |
| "Effect scales with model size" | Model scaling study | Monotonic improvement curve |

**Rule**: If an experiment doesn't map to a claim, don't run it.

### Design Baselines

- **Naive baseline**: Simplest possible approach
- **Strong baseline**: Best known existing method
- **Ablation baselines**: Your method minus one component
- **Compute-matched baselines**: Same compute budget, different allocation

## Phase 3: Experiment Execution & Monitoring

Launch experiments via `execute_command`. Use incremental saving for crash recovery:

```python
result_path = f"results/{task}/{strategy}/result.json"
if os.path.exists(result_path):
    continue  # Skip already-completed work
# ... run experiment ...
with open(result_path, 'w') as f:
    json.dump(result, f, indent=2)
```

Commit results after each batch:
```bash
git add -A && git commit -m "Add <experiment name>: <key finding>" && git push
```

## Phase 4: Result Analysis

### Statistical Significance

Always compute:
- **Error bars**: Standard deviation or standard error (specify which)
- **Confidence intervals**: 95% CI for key results
- **Pairwise tests**: McNemar's test for comparing two methods
- **Effect sizes**: Cohen's d or h for practical significance

### Figures and Tables

- Use vector graphics (PDF) for all plots
- Colorblind-safe palettes (Okabe-Ito or Paul Tol)
- Self-contained captions — reader should understand without main text
- Use `booktabs` LaTeX package for tables, bold best values, include direction symbols

### Bridge to Writeup

Create `experiment_log.md` mapping each experiment to its claim, setup, key result, result files, and figures. This is the primary context bridge for drafting.

## Phase 5: Paper Drafting

### The Narrative Principle

Your paper is a story with one clear contribution supported by evidence.

**Three Pillars (crystal clear by end of introduction):**

| Pillar | Description | Test |
|--------|-------------|------|
| **The What** | 1-3 specific novel claims | Can you state them in one sentence? |
| **The Why** | Rigorous empirical evidence | Do experiments distinguish your hypothesis from alternatives? |
| **The So What** | Why readers should care | Does this connect to a recognized community problem? |

### Time Allocation

Spend approximately **equal time** on each of:
1. The abstract
2. The introduction
3. The figures
4. Everything else combined

### Writing Checklist

```
- [ ] Define the one-sentence contribution
- [ ] Draft Figure 1 (core idea or most compelling result)
- [ ] Draft abstract (5-sentence formula)
- [ ] Draft introduction (1-1.5 pages max)
- [ ] Draft methods
- [ ] Draft experiments & results
- [ ] Draft related work
- [ ] Draft conclusion & discussion
- [ ] Draft limitations (REQUIRED by all venues)
- [ ] Plan appendix
- [ ] Complete paper checklist
- [ ] Final review
```

## Phase 6: Self-Review

Simulate 3 reviewers with different perspectives:
1. **Skeptical expert** — attacks methodology and baselines
2. **Broad area chair** — checks clarity and contribution significance
3. **Detail-oriented reviewer** — checks reproducibility and statistical rigor

Address each simulated concern before submission.

## Tools Reference

| Promethe Tool | Usage in This Skill |
|---------------|-------------------|
| `execute_command` | Run experiments, git operations, LaTeX compilation |
| `file_write` | Write LaTeX files, experiment scripts, configs |
| `file_read` | Read results, existing drafts, configs |
| `http_fetch` | Query arXiv API, Semantic Scholar, fetch DOI BibTeX |
| `delegate_task` | Parallel literature search, parallel experiment runs |
