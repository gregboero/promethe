---
name: architecture-diagram
description: "Dark-themed SVG architecture/cloud/infra diagrams as HTML."
source: BUNDLED
platforms: jvm
---

# Architecture Diagram Skill

Generate professional, dark-themed technical architecture diagrams as standalone HTML files with inline SVG graphics. No external tools, no API keys, no rendering libraries — just write the HTML file and open it in a browser.

## Scope

**Best suited for:**
- Software system architecture (frontend / backend / database layers)
- Cloud infrastructure (VPC, regions, subnets, managed services)
- Microservice / service-mesh topology
- Database + API map, deployment diagrams
- Anything with a tech-infra subject that fits a dark, grid-backed aesthetic

**Look elsewhere first for:**
- Physics, chemistry, math, biology, or other scientific subjects
- Physical objects (vehicles, hardware, anatomy, cross-sections)
- Floor plans, narrative journeys, educational / textbook-style visuals
- Animated explainers (consider an animation skill)

If a more specialized skill is available for the subject, prefer that. If none fits, this skill can also serve as a general SVG diagram fallback — the output will just carry the dark tech aesthetic described below.

## Workflow

1. User describes their system architecture (components, connections, technologies)
2. Generate the HTML file following the design system below
3. Save with `file_write(path=..., content=...)` to a `.html` file (e.g. `./architecture-diagram.html`)
4. User opens in any browser — works offline, no dependencies

### Output Location

Save diagrams to a user-specified path, or default to the current working directory:
```
./[project-name]-architecture.html
```

### Preview

After saving, suggest the user open it:
```
execute_command(command="open", args=["./my-architecture.html"])
```

## Design System & Visual Language

### Color Palette (Semantic Mapping)

Use specific `rgba` fills and hex strokes to categorize components:

| Component Type | Fill (rgba) | Stroke (Hex) |
| :--- | :--- | :--- |
| **Frontend** | `rgba(8, 51, 68, 0.4)` | `#22d3ee` (cyan-400) |
| **Backend** | `rgba(6, 78, 59, 0.4)` | `#34d399` (emerald-400) |
| **Database** | `rgba(76, 29, 149, 0.4)` | `#a78bfa` (violet-400) |
| **AWS/Cloud** | `rgba(120, 53, 15, 0.3)` | `#fbbf24` (amber-400) |
| **Security** | `rgba(136, 19, 55, 0.4)` | `#fb7185` (rose-400) |
| **Message Bus** | `rgba(251, 146, 60, 0.3)` | `#fb923c` (orange-400) |
| **External** | `rgba(30, 41, 59, 0.4)` | `#94a3b8` (slate-400) |

### Layout Rules

- Dark background: `#0f172a` (slate-900) with subtle dot grid
- All text: white or light gray for contrast
- Connection lines: use matching stroke colors with arrowheads
- Group related components in labeled dashed-border regions
- Maintain visual hierarchy: primary flow left-to-right or top-to-bottom

### SVG Best Practices

- Use `<defs>` for reusable markers (arrowheads) and filters (glow/shadow)
- Prefer `<g>` groups with transforms for component positioning
- Add subtle `filter: drop-shadow()` for depth
- Include `<title>` elements inside components for accessibility
- Use responsive `viewBox` — no fixed pixel dimensions on the root `<svg>`
