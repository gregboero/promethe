---
name: excalidraw
description: "Hand-drawn Excalidraw JSON diagrams (architecture, flow, sequence)."
source: BUNDLED
platforms: jvm
---

# Excalidraw Diagram Skill

Create diagrams by writing standard Excalidraw element JSON and saving as `.excalidraw` files. These files can be drag-and-dropped onto [excalidraw.com](https://excalidraw.com) for viewing and editing. No accounts, no API keys, no rendering libraries — just JSON.

## When to use

Generate `.excalidraw` files for architecture diagrams, flowcharts, sequence diagrams, concept maps, and more. Files can be opened at excalidraw.com or uploaded for shareable links.

## Workflow

1. **Write the elements JSON** — an array of Excalidraw element objects
2. **Save the file** using `file_write(path=..., content=...)` to create a `.excalidraw` file
3. **Optionally upload** for a shareable link using the upload script via `execute_command`

### Saving a Diagram

Wrap your elements array in the standard `.excalidraw` envelope and save with `file_write`:

```json
{
  "type": "excalidraw",
  "version": 2,
  "source": "promethe-agent",
  "elements": [ ...your elements array here... ],
  "appState": {
    "viewBackgroundColor": "#ffffff"
  }
}
```

Save to any path, e.g. `~/diagrams/my_diagram.excalidraw`.

### Uploading for a Shareable Link

Run the upload script via `execute_command`:

```
execute_command(command="python3", args=["scripts/upload.py", "~/diagrams/my_diagram.excalidraw"])
```

This uploads to excalidraw.com (no account needed) and prints a shareable URL. Requires the `cryptography` pip package (`pip install cryptography`).

---

## Element Format Reference

### Required Fields (all elements)
`type`, `id` (unique string), `x`, `y`, `width`, `height`

### Defaults (skip these — they're applied automatically)
- `strokeColor`: `"#1e1e1e"`
- `backgroundColor`: `"transparent"`
- `fillStyle`: `"solid"`
- `strokeWidth`: `2`
- `roughness`: `1` (hand-drawn look)
- `opacity`: `100`

Canvas background is white.

### Element Types

**Rectangle**:
```json
{ "type": "rectangle", "id": "r1", "x": 100, "y": 100, "width": 200, "height": 100 }
```
- `roundness: { "type": 3 }` for rounded corners
- `backgroundColor: "#a5d8ff"`, `fillStyle: "solid"` for filled

**Ellipse**:
```json
{ "type": "ellipse", "id": "e1", "x": 100, "y": 100, "width": 150, "height": 150 }
```

**Diamond**:
```json
{ "type": "diamond", "id": "d1", "x": 100, "y": 100, "width": 150, "height": 150 }
```

**Labeled shape (container binding)** — create a text element bound to the shape:

> **WARNING:** Do NOT use `"label": { "text": "..." }` on shapes. This is NOT a valid
> Excalidraw property and will be silently ignored, producing blank shapes. You MUST
> use the container binding approach: the shape needs `boundElements` referencing a text
> element, and the text element needs `containerId` pointing back to the shape.

### Text Element

```json
{
  "type": "text", "id": "t1", "x": 120, "y": 130, "width": 160, "height": 40,
  "text": "My Label", "fontSize": 20, "fontFamily": 1, "textAlign": "center",
  "verticalAlign": "middle", "containerId": "r1"
}
```

The parent shape (`r1`) must include: `"boundElements": [{ "id": "t1", "type": "text" }]`

### Arrow / Line

```json
{
  "type": "arrow", "id": "a1", "x": 300, "y": 150, "width": 200, "height": 0,
  "points": [[0, 0], [200, 0]],
  "startBinding": { "elementId": "r1", "focus": 0, "gap": 1 },
  "endBinding": { "elementId": "r2", "focus": 0, "gap": 1 }
}
```

Connected shapes must include the arrow in their `boundElements` array.
