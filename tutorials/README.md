# Tutorials — Jakarta Agentic AI

Study material for the Payara Conference talk (August 2026), in **three
languages** — English, Español and Português. Everything is generated from the
Markdown sources listed below; the Portuguese source in `.claude/tutorial/` is
also what the `/tutorial` Claude Code skill reads, so it stays the single source
of truth for that language.

| Folder | What it is | How to open |
| --- | --- | --- |
| `md/` | The tutorial in **English** — 9 chapters + index, each ending with a quiz (answers behind `▸ Show answer`). | Any Markdown viewer / GitHub |
| `md-es/` | The same tutorial in **Español**. | Any Markdown viewer / GitHub |
| `../.claude/tutorial/` | The same tutorial in **Português** (the source the `/tutorial` skill reads). | Any Markdown viewer / GitHub |
| `html/` | **Interactive single-page tutorial**, trilingual: sidebar navigation, Payara branding, syntax-highlighted code, and self-scored quizzes with progress saved in `localStorage`. | Open `html/index.html` in a browser |
| `presentation/` | **Slide deck** (16:9), trilingual, covering the talk narrative: problem → spec → demos → implementation internals → TCK → roadmap. | Open `presentation/index.html` in a browser |
| `samples-presentation/` | **Slide deck** (16:9), trilingual, for the samples-focused session. | Open `samples-presentation/index.html` in a browser |

## Languages

Every HTML artifact carries all three languages in one file and switches
client-side — no page reload, no separate URLs to keep in sync:

| Flag | Language | Chapter source |
| --- | --- | --- |
| 🇺🇸 | English | `md/` |
| 🇪🇸 | Español | `md-es/` |
| 🇧🇷 | Português | `../.claude/tutorial/` |

- **Tutorial**: the flag buttons sit at the top of the sidebar. Switching keeps
  the current chapter, and quiz scores are shared across languages (question *n*
  of chapter *k* is the same question in every language).
- **Decks**: the flags sit at the top-left corner and survive overview mode.
  Switching keeps the current slide, and the presenter window (`P`) reopens with
  the speaker notes in the new language.
- The choice is remembered in `localStorage` and reflected in the URL
  (`?lang=es`), so a link can pin a language. With nothing set, the page falls
  back to the browser's language, then to English.

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

`build.mjs` reads all three languages and emits a single `index.html`. To add a
chapter or change the UI strings of a language, edit the `LANGS` array at the top
of the script.

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
