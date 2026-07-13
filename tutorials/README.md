# Tutorials — Jakarta Agentic AI

English study material for the Payara Conference talk (August 2026), generated
from the Portuguese source tutorial in `.claude/tutorial/`.

| Folder | What it is | How to open |
| --- | --- | --- |
| `md/` | The full tutorial translated to English — 9 chapters + index, each ending with a quiz (answers behind `▸ Show answer`). | Any Markdown viewer / GitHub |
| `html/` | **Interactive single-page tutorial**: sidebar navigation, Payara branding, syntax-highlighted code, and self-scored quizzes with progress saved in `localStorage`. | Open `html/index.html` in a browser |
| `presentation/` | **Slide deck** (16:9) covering the talk narrative: problem → spec → demos → implementation internals → TCK → roadmap. | Open `presentation/index.html` in a browser |

## Interactive tutorial (`html/`)

- Left sidebar lists the 9 chapters; your quiz score per chapter appears next to
  each entry (`n/total`, green when complete).
- Each quiz question has **Reveal answer**, then **✓ I got it / ✗ Missed it**
  self-scoring. Scores persist across sessions (localStorage); use
  **↺ Retake quiz** in the end-of-chapter summary to reset a chapter.
- Keyboard: `←`/`→` switch chapters.

### Rebuilding after editing the Markdown

The page is generated from `md/*.md`:

```bash
cd tutorials/html
npm install marked   # first time only
node build.mjs
```

## Presentation (`presentation/`)

Self-contained HTML deck (no framework, no CDN dependency besides Google Fonts —
it degrades gracefully offline to system fonts).

Controls:

| Key | Action |
| --- | --- |
| `→` / `Space` / `PageDown` | next slide |
| `←` / `PageUp` | previous slide |
| `O` | overview grid (click a slide to jump) |
| `F` | fullscreen |
| `Home` / `End` | first / last slide |

Slides are addressable by URL hash (`index.html#12`), so you can bookmark demo
checkpoints.

## Branding

Colors are taken from the payara.fish theme CSS: primary orange `#ee992f`,
brand navy `#001b27` / `#00131b`, light surfaces `#f9fafb` / `#f4f4f4`, with
IBM Plex Sans / IBM Plex Mono typography.
