// Builds tutorials/html/index.html from the Markdown chapters, in three languages.
//
//   English    tutorials/md/*.md
//   Português  .claude/tutorial/*.md   (single source of truth, also used by /tutorial)
//   Español    tutorials/md-es/*.md
//
// All three are embedded in one self-contained page; the flag buttons switch
// language client-side, preserving the current chapter and the quiz progress.
//
// Payara palette (extracted from payara.fish frontend.css):
//   primary orange #ee992f | brand navy #001b27 | inversed #00131b
//   light #f9fafb | subtle #f4f4f4 | body #121212 | orange-subtle #fcebd5
import { marked } from 'marked';
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = join(HERE, 'index.html');

const LANGS = [
  {
    code: 'en',
    flag: '<svg viewBox="0 0 24 16" width="23" height="15" aria-hidden="true"><rect width="24" height="16" fill="#fff"/><g fill="#b22234"><rect width="24" height="1.23"/><rect y="2.46" width="24" height="1.23"/><rect y="4.92" width="24" height="1.23"/><rect y="7.38" width="24" height="1.23"/><rect y="9.85" width="24" height="1.23"/><rect y="12.31" width="24" height="1.23"/><rect y="14.77" width="24" height="1.23"/></g><rect width="9.6" height="8.62" fill="#3c3b6e"/><g fill="#fff"><circle cx="1.0" cy="1.1" r=".42"/><circle cx="2.9" cy="1.1" r=".42"/><circle cx="4.8" cy="1.1" r=".42"/><circle cx="6.7" cy="1.1" r=".42"/><circle cx="8.6" cy="1.1" r=".42"/><circle cx="1.0" cy="3.2" r=".42"/><circle cx="2.9" cy="3.2" r=".42"/><circle cx="4.8" cy="3.2" r=".42"/><circle cx="6.7" cy="3.2" r=".42"/><circle cx="8.6" cy="3.2" r=".42"/><circle cx="1.0" cy="5.3" r=".42"/><circle cx="2.9" cy="5.3" r=".42"/><circle cx="4.8" cy="5.3" r=".42"/><circle cx="6.7" cy="5.3" r=".42"/><circle cx="8.6" cy="5.3" r=".42"/><circle cx="1.0" cy="7.4" r=".42"/><circle cx="2.9" cy="7.4" r=".42"/><circle cx="4.8" cy="7.4" r=".42"/><circle cx="6.7" cy="7.4" r=".42"/><circle cx="8.6" cy="7.4" r=".42"/></g></svg>',
    name: 'English',
    htmlLang: 'en',
    dir: join(HERE, '..', 'md'),
    title: 'Jakarta Agentic AI — Interactive Tutorial',
    brand: 'Tutorial',
    tagline: 'Spec · Payara implementation · Samples — with a quiz after every chapter.',
    quizHint: 'Answer in your head, then reveal and score yourself.',
    reveal: '💡 Reveal answer',
    got: '✓ I got it',
    missed: '✗ Missed it',
    scoredOk: 'Scored: correct ✓',
    scoredBad: 'Scored: to review ✗ — come back to this one before the talk',
    retake: '↺ Retake quiz',
    perfect: 'Perfect score — you have this chapter down. ✅',
    complete: 'Quiz complete. Revisit the ✗ questions above before moving on.',
    prev: '← Previous',
    next: 'Next →',
    foot: 'Colors from',
    chapters: [
      { file: '01-overview.md',                  short: 'Overview & Motivation' },
      { file: '02-programming-model.md',         short: 'The Programming Model' },
      { file: '03-largelanguagemodel.md',        short: 'LargeLanguageModel & Errors' },
      { file: '04-tck.md',                       short: 'The TCK' },
      { file: '05-payara-impl-cdi-extension.md', short: 'Payara Impl: CDI Extension' },
      { file: '06-payara-impl-engine.md',        short: 'Payara Impl: WorkflowEngine' },
      { file: '07-llm-backends.md',              short: 'LLM Backends & Config' },
      { file: '08-samples.md',                   short: 'The Samples' },
      { file: '09-presentation-guide.md',        short: 'Wrap-up & FAQ' },
    ],
  },
  {
    code: 'es',
    flag: '<svg viewBox="0 0 24 16" width="23" height="15" aria-hidden="true"><rect width="24" height="16" fill="#c60b1e"/><rect y="4" width="24" height="8" fill="#ffc400"/></svg>',
    name: 'Español',
    htmlLang: 'es',
    dir: join(HERE, '..', 'md-es'),
    title: 'Jakarta Agentic AI — Tutorial interactivo',
    brand: 'Tutorial',
    tagline: 'Spec · Implementación Payara · Samples — con un test al final de cada capítulo.',
    quizHint: 'Responde mentalmente, luego revela la respuesta y puntúate.',
    reveal: '💡 Revelar respuesta',
    got: '✓ Acerté',
    missed: '✗ Fallé',
    scoredOk: 'Puntuación: correcta ✓',
    scoredBad: 'Puntuación: a repasar ✗ — vuelve a esta antes de la charla',
    retake: '↺ Repetir test',
    perfect: 'Puntuación perfecta — este capítulo lo dominas. ✅',
    complete: 'Test completado. Repasa las preguntas ✗ de arriba antes de seguir.',
    prev: '← Anterior',
    next: 'Siguiente →',
    foot: 'Colores de',
    chapters: [
      { file: '01-vision-general.md',              short: 'Visión general y motivación' },
      { file: '02-modelo-de-programacion.md',      short: 'El modelo de programación' },
      { file: '03-largelanguagemodel.md',          short: 'LargeLanguageModel y errores' },
      { file: '04-tck.md',                         short: 'El TCK' },
      { file: '05-implementacion-extension-cdi.md',short: 'Impl. Payara: extensión CDI' },
      { file: '06-implementacion-engine.md',       short: 'Impl. Payara: WorkflowEngine' },
      { file: '07-backends-llm.md',                short: 'Backends LLM y configuración' },
      { file: '08-samples.md',                     short: 'Los samples' },
      { file: '09-guia-presentacion.md',           short: 'Cierre y FAQ' },
    ],
  },
  {
    code: 'pt',
    flag: '<svg viewBox="0 0 24 16" width="23" height="15" aria-hidden="true"><rect width="24" height="16" fill="#009b3a"/><path d="M12 2.2 21.4 8 12 13.8 2.6 8Z" fill="#fedf00"/><circle cx="12" cy="8" r="3.4" fill="#002776"/><path d="M8.7 7.2c2.3-1.1 4.9-.6 6.7 1.2" stroke="#fff" stroke-width=".9" fill="none"/></svg>',
    name: 'Português',
    htmlLang: 'pt-BR',
    dir: join(HERE, '..', '..', '.claude', 'tutorial'),
    title: 'Jakarta Agentic AI — Tutorial interativo',
    brand: 'Tutorial',
    tagline: 'Spec · Implementação Payara · Samples — com quiz ao final de cada capítulo.',
    quizHint: 'Responda de cabeça, depois revele a resposta e se pontue.',
    reveal: '💡 Revelar resposta',
    got: '✓ Acertei',
    missed: '✗ Errei',
    scoredOk: 'Pontuação: correta ✓',
    scoredBad: 'Pontuação: revisar ✗ — volte nesta antes da palestra',
    retake: '↺ Refazer quiz',
    perfect: 'Pontuação perfeita — este capítulo está dominado. ✅',
    complete: 'Quiz concluído. Revise as questões ✗ acima antes de seguir.',
    prev: '← Anterior',
    next: 'Próximo →',
    foot: 'Cores de',
    chapters: [
      { file: '01-visao-geral.md',                short: 'Visão geral e motivação' },
      { file: '02-modelo-de-programacao.md',      short: 'O modelo de programação' },
      { file: '03-largelanguagemodel.md',         short: 'LargeLanguageModel e erros' },
      { file: '04-tck.md',                        short: 'O TCK' },
      { file: '05-implementacao-extensao-cdi.md', short: 'Impl. Payara: extensão CDI' },
      { file: '06-implementacao-engine.md',       short: 'Impl. Payara: WorkflowEngine' },
      { file: '07-backends-llm.md',               short: 'Backends LLM e configuração' },
      { file: '08-samples.md',                    short: 'Os samples' },
      { file: '09-roteiro-apresentacao.md',       short: 'Roteiro & FAQ' },
    ],
  },
];

const DEFAULT_LANG = 'en';

marked.setOptions({ gfm: true, breaks: false });

function render(md) {
  // strip the trailing "➡️ Next / Próximo / Siguiente:" nav line (the app has its own nav)
  return marked.parse(md.replace(/^➡️.*$/m, ''));
}

const sections = LANGS.flatMap(lang =>
  lang.chapters.map((ch, i) => {
    const html = render(readFileSync(join(lang.dir, ch.file), 'utf8'));
    return `<section class="chapter" id="ch-${lang.code}-${i + 1}" data-lang="${lang.code}" data-num="${i + 1}">\n${html}\n</section>`;
  })
).join('\n');

// Everything the runtime needs, per language — no file paths leak into the page.
const I18N = Object.fromEntries(LANGS.map(l => [l.code, {
  flag: l.flag, name: l.name, htmlLang: l.htmlLang, title: l.title, brand: l.brand,
  tagline: l.tagline, quizHint: l.quizHint, reveal: l.reveal, got: l.got,
  missed: l.missed, scoredOk: l.scoredOk, scoredBad: l.scoredBad, retake: l.retake,
  perfect: l.perfect, complete: l.complete, prev: l.prev, next: l.next, foot: l.foot,
  chapters: l.chapters.map((c, i) => ({ n: i + 1, t: c.short })),
}]));

const page = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Jakarta Agentic AI — Interactive Tutorial</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link href="https://fonts.googleapis.com/css2?family=IBM+Plex+Sans:ital,wght@0,400;0,500;0,600;0,700;1,400&family=IBM+Plex+Mono:wght@400;500;600&display=swap" rel="stylesheet">
<style>
:root{
  --orange:#ee992f; --orange-dark:#c97c15; --orange-subtle:#fcebd5; --orange-border:#f8d6ac;
  --navy:#001b27; --navy-2:#00131b; --navy-3:#0a2a3a; --navy-soft:#12384c;
  --light:#f9fafb; --subtle:#f4f4f4; --line:#e3e6e8; --body:#121212; --muted:#5b6770;
  --ok:#198754; --bad:#dc3545;
  --sidebar-w:300px; --content-w:880px;
  --sans:"IBM Plex Sans",system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;
  --mono:"IBM Plex Mono",SFMono-Regular,Menlo,Consolas,monospace;
}
*{box-sizing:border-box}
html{scroll-behavior:smooth}
body{margin:0;font-family:var(--sans);color:var(--body);background:var(--light);font-size:16.5px;line-height:1.65}

/* ── Top progress bar ─────────────────────────────── */
#progressbar{position:fixed;top:0;left:0;height:4px;background:linear-gradient(90deg,var(--orange),#ffbf69);width:0;z-index:60;transition:width .15s ease}

/* ── Sidebar ──────────────────────────────────────── */
#sidebar{position:fixed;top:0;left:0;bottom:0;width:var(--sidebar-w);background:linear-gradient(180deg,var(--navy) 0%,var(--navy-2) 100%);color:#e8edf0;overflow-y:auto;z-index:50;display:flex;flex-direction:column}
#sidebar .brand{padding:26px 22px 20px;border-bottom:1px solid rgba(255,255,255,.08)}
#sidebar .brand .fish{display:flex;align-items:center;gap:10px}
#sidebar .brand .fish svg{flex:none}
#sidebar .brand h1{font-size:17px;font-weight:700;margin:0;line-height:1.3;letter-spacing:.2px}
#sidebar .brand h1 em{color:var(--orange);font-style:normal}
#sidebar .brand p{margin:8px 0 0;font-size:12.5px;color:#9fb2bc;line-height:1.45}
#sidebar nav{padding:14px 12px;flex:1}
#sidebar nav a{display:flex;align-items:center;gap:11px;padding:9px 11px;margin:2px 0;border-radius:9px;color:#c3d0d8;text-decoration:none;font-size:13.8px;font-weight:500;line-height:1.35;transition:background .15s,color .15s;border:1px solid transparent}
#sidebar nav a:hover{background:rgba(255,255,255,.06);color:#fff}
#sidebar nav a.active{background:rgba(238,153,47,.14);color:#fff;border-color:rgba(238,153,47,.35)}
#sidebar nav a .num{flex:none;width:26px;height:26px;border-radius:8px;background:rgba(255,255,255,.08);display:flex;align-items:center;justify-content:center;font-size:12px;font-weight:600;font-family:var(--mono)}
#sidebar nav a.active .num{background:var(--orange);color:var(--navy)}
#sidebar nav a .score{margin-left:auto;font-size:11px;font-family:var(--mono);color:#8fa5b0;flex:none}
#sidebar nav a .score.done{color:#5ad18a}
#sidebar .foot{padding:16px 22px;border-top:1px solid rgba(255,255,255,.08);font-size:11.5px;color:#7e929d;line-height:1.5}
#sidebar .foot a{color:var(--orange);text-decoration:none}

/* ── Language switcher (fixed, top-right) ─────────── */
#langbar{position:fixed;top:14px;right:20px;z-index:65;display:flex;gap:6px;background:rgba(255,255,255,.92);backdrop-filter:blur(6px);border:1px solid var(--line);border-radius:11px;padding:5px;box-shadow:0 2px 10px rgba(0,19,27,.10)}
#langbar button{display:flex;align-items:center;justify-content:center;background:none;border:1.5px solid transparent;border-radius:7px;padding:4px 6px;cursor:pointer;line-height:0;opacity:.45;transition:opacity .15s,border-color .15s,background .15s}
#langbar button svg{display:block;border-radius:2px}
#langbar button:hover{opacity:.85;background:var(--subtle)}
#langbar button.active{opacity:1;border-color:var(--orange);background:var(--orange-subtle)}

/* ── Content ──────────────────────────────────────── */
#main{margin-left:var(--sidebar-w);min-height:100vh}
#content{max-width:var(--content-w);margin:0 auto;padding:52px 48px 40px}
.chapter{display:none;animation:fadein .25s ease}
.chapter.visible{display:block}
@keyframes fadein{from{opacity:0;transform:translateY(6px)}to{opacity:1;transform:none}}

/* typography */
.chapter h1{font-size:31px;line-height:1.25;margin:0 0 22px;color:var(--navy);font-weight:700;letter-spacing:-.3px;padding-bottom:16px;border-bottom:3px solid var(--orange)}
.chapter h2{font-size:22.5px;margin:44px 0 14px;color:var(--navy);font-weight:700;letter-spacing:-.2px}
.chapter h3{font-size:18px;margin:34px 0 12px;color:var(--navy);font-weight:600}
.chapter h4{font-size:16px;margin:28px 0 10px;color:var(--navy);font-weight:600}
.chapter p{margin:0 0 15px}
.chapter a{color:var(--orange-dark);font-weight:500}
.chapter strong{color:#000;font-weight:600}
.chapter ul,.chapter ol{padding-left:26px;margin:0 0 16px}
.chapter li{margin:6px 0}
.chapter li::marker{color:var(--orange);font-weight:700}
.chapter blockquote{margin:18px 0;padding:14px 20px;border-left:4px solid var(--orange);background:var(--orange-subtle);border-radius:0 10px 10px 0;color:#5f3d13}
.chapter blockquote p{margin:0}
.chapter hr{border:none;border-top:1px solid var(--line);margin:38px 0}

/* code */
.chapter code{font-family:var(--mono);font-size:.86em;background:#eceff1;color:#0a3a52;padding:2px 6px;border-radius:5px}
.chapter pre{background:var(--navy-2);border-radius:12px;padding:20px 22px;overflow-x:auto;margin:18px 0;box-shadow:0 4px 18px rgba(0,19,27,.18);border:1px solid var(--navy-soft)}
.chapter pre code{background:none;color:#dce7ec;padding:0;font-size:13.6px;line-height:1.62;display:block}
.tok-kw{color:#7fc8ef;font-weight:500}
.tok-ann{color:var(--orange);font-weight:600}
.tok-str{color:#a8d977}
.tok-cmt{color:#6d8794;font-style:italic}
.tok-num{color:#f2b8d0}
.tok-key{color:#7fc8ef}
.tok-val{color:#a8d977}

/* tables */
.chapter table{width:100%;border-collapse:separate;border-spacing:0;margin:20px 0;font-size:14.6px;border:1px solid var(--line);border-radius:12px;overflow:hidden}
.chapter th{background:var(--navy);color:#fff;text-align:left;padding:11px 15px;font-weight:600;font-size:13.4px;letter-spacing:.2px}
.chapter td{padding:11px 15px;border-top:1px solid var(--line);vertical-align:top}
.chapter tbody tr:nth-child(even){background:#fbfbfc}
.chapter td code,.chapter th code{white-space:nowrap}

/* ── Quiz cards ───────────────────────────────────── */
.quiz-banner{display:flex;align-items:center;gap:12px;background:var(--navy);color:#fff;border-radius:14px;padding:18px 22px;margin:44px 0 20px}
.quiz-banner .qb-ico{width:38px;height:38px;border-radius:10px;background:var(--orange);display:flex;align-items:center;justify-content:center;font-size:19px;flex:none}
.quiz-banner h2{margin:0!important;color:#fff!important;font-size:19px!important}
.quiz-banner p{margin:2px 0 0;font-size:12.8px;color:#a9bcc6}
.quiz-card{border:1px solid var(--line);border-radius:14px;background:#fff;margin:0 0 18px;box-shadow:0 2px 8px rgba(0,19,27,.05);overflow:hidden}
.quiz-card .q{padding:18px 22px 4px}
.quiz-card .q p{margin-bottom:12px}
.quiz-card .q>p:first-child strong:first-child{display:inline-flex;align-items:center;justify-content:center;min-width:30px;height:30px;padding:0 8px;background:var(--orange);color:var(--navy);border-radius:9px;margin-right:9px;font-family:var(--mono);font-size:14px}
.quiz-actions{padding:0 22px 16px;display:flex;gap:10px;flex-wrap:wrap}
.btn{font-family:var(--sans);font-size:13.6px;font-weight:600;border:none;border-radius:9px;padding:9px 18px;cursor:pointer;transition:transform .1s,box-shadow .15s,background .15s}
.btn:active{transform:scale(.97)}
.btn-reveal{background:var(--orange);color:var(--navy)}
.btn-reveal:hover{background:#f6a844}
.btn-ok{background:#e7f5ec;color:var(--ok);border:1.5px solid #b7dfc6}
.btn-ok:hover{background:#d4eddd}
.btn-bad{background:#fdecee;color:var(--bad);border:1.5px solid #f3c2c8}
.btn-bad:hover{background:#fadde1}
.quiz-answer{display:none;margin:0 22px 18px;padding:16px 20px;background:var(--orange-subtle);border:1px solid var(--orange-border);border-radius:11px;font-size:15px}
.quiz-answer.open{display:block;animation:fadein .2s ease}
.quiz-answer p:last-child{margin-bottom:0}
.quiz-card.scored-ok{border-color:#9ed4b2}
.quiz-card.scored-ok .q>p:first-child strong:first-child{background:var(--ok);color:#fff}
.quiz-card.scored-bad{border-color:#eaa7ae}
.quiz-card.scored-bad .q>p:first-child strong:first-child{background:var(--bad);color:#fff}
.quiz-verdict{margin:0 22px 18px;font-size:13px;font-weight:600;display:none}
.quiz-verdict.v-ok{display:block;color:var(--ok)}
.quiz-verdict.v-bad{display:block;color:var(--bad)}
.quiz-summary{border-radius:14px;padding:20px 24px;background:var(--navy);color:#fff;margin:8px 0 30px;display:none;align-items:center;gap:18px}
.quiz-summary.show{display:flex}
.quiz-summary .big{font-size:30px;font-weight:700;font-family:var(--mono);color:var(--orange)}
.quiz-summary p{margin:0;font-size:14px;color:#c3d0d8}
.quiz-summary button{margin-left:auto;background:rgba(255,255,255,.1);color:#fff;border:1px solid rgba(255,255,255,.25)}
.quiz-summary button:hover{background:rgba(255,255,255,.18)}

/* ── chapter nav footer ───────────────────────────── */
.chnav{display:flex;justify-content:space-between;gap:14px;margin:46px 0 10px;padding-top:26px;border-top:1px solid var(--line)}
.chnav a{flex:1;max-width:48%;text-decoration:none;border:1.5px solid var(--line);border-radius:13px;padding:14px 18px;color:var(--body);transition:border-color .15s,box-shadow .15s;background:#fff}
.chnav a:hover{border-color:var(--orange);box-shadow:0 3px 12px rgba(238,153,47,.18)}
.chnav a .dir{font-size:11.5px;font-weight:600;letter-spacing:.8px;color:var(--muted);text-transform:uppercase}
.chnav a .ttl{display:block;font-weight:600;color:var(--navy);margin-top:3px;font-size:14.5px}
.chnav a.next{text-align:right;margin-left:auto}
.chnav a.hidden{visibility:hidden}

/* mobile */
#menu-btn{display:none;position:fixed;top:14px;left:14px;z-index:70;background:var(--navy);color:#fff;border:none;border-radius:10px;width:42px;height:42px;font-size:19px;cursor:pointer}
@media(max-width:1024px){
  #sidebar{transform:translateX(-100%);transition:transform .25s ease}
  #sidebar.open{transform:none;box-shadow:0 0 60px rgba(0,0,0,.4)}
  #main{margin-left:0}
  #content{padding:70px 22px 30px}
  #menu-btn{display:block}
  .chnav{flex-direction:column}.chnav a{max-width:100%}
}
::selection{background:var(--orange-subtle)}
</style>
</head>
<body>
<div id="progressbar"></div>
<button id="menu-btn" aria-label="Menu">☰</button>
<div id="langbar"></div>

<aside id="sidebar">
  <div class="brand">
    <div class="fish">
      <svg width="34" height="34" viewBox="0 0 34 34" fill="none" xmlns="http://www.w3.org/2000/svg">
        <rect width="34" height="34" rx="8" fill="#ee992f"/>
        <path d="M6 17c3.2-4.6 7.6-7 12.2-7 3.4 0 6.6 1.3 9.3 3.8l-2.3 3.2 2.3 3.2c-2.7 2.5-5.9 3.8-9.3 3.8-4.6 0-9-2.4-12.2-7z" fill="#001b27"/>
        <circle cx="22.6" cy="15.4" r="1.5" fill="#ee992f"/>
      </svg>
      <h1>Jakarta <em>Agentic AI</em><br><span id="brand-word">Tutorial</span></h1>
    </div>
    <p id="tagline"></p>
  </div>
  <nav id="chnav-list"></nav>
  <div class="foot"><span id="foot-text"></span> <a href="https://payara.fish" target="_blank" rel="noopener">payara.fish</a></div>
</aside>

<div id="main"><div id="content">
${sections}
</div></div>

<script>
const I18N = ${JSON.stringify(I18N)};
const LANG_ORDER = ${JSON.stringify(LANGS.map(l => l.code))};
const DEFAULT_LANG = ${JSON.stringify(DEFAULT_LANG)};

const store = {
  get(k, d){ try { return JSON.parse(localStorage.getItem('jaai.'+k)) ?? d; } catch(e){ return d; } },
  set(k, v){ try { localStorage.setItem('jaai.'+k, JSON.stringify(v)); } catch(e){} }
};

/* ── language state ──────────────────────────────── */
function initialLang(){
  const q = new URLSearchParams(location.search).get('lang');
  if(q && I18N[q]) return q;
  const saved = store.get('lang', null);
  if(saved && I18N[saved]) return saved;
  const nav = (navigator.language || '').toLowerCase();
  const guess = LANG_ORDER.find(c => nav.startsWith(c) || (c === 'pt' && nav.startsWith('pt')));
  return guess || DEFAULT_LANG;
}
let LANG = initialLang();
function t(){ return I18N[LANG]; }
function CHAPTERS(){ return t().chapters; }

/* ── syntax highlighting (tiny, dependency-free) ── */
function esc(s){ return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }
function hlJava(src){
  const re = /(\\/\\/[^\\n]*|\\/\\*[\\s\\S]*?\\*\\/)|("(?:\\\\.|[^"\\\\])*")|(@[A-Za-z_]\\w*)|\\b(public|private|protected|abstract|class|interface|record|enum|extends|implements|return|void|new|if|else|for|while|do|switch|case|default|break|continue|try|catch|finally|throw|throws|import|package|static|final|volatile|synchronized|transient|boolean|int|long|double|float|char|byte|short|null|true|false|this|super|var|instanceof|yield)\\b|((?<![\\w.])\\d[\\d_]*(?:\\.\\d+)?)/g;
  let out = '', last = 0, m;
  while((m = re.exec(src))){
    out += esc(src.slice(last, m.index));
    const cls = m[1] ? 'cmt' : m[2] ? 'str' : m[3] ? 'ann' : m[4] ? 'kw' : 'num';
    out += '<span class="tok-'+cls+'">'+esc(m[0])+'</span>';
    last = m.index + m[0].length;
  }
  return out + esc(src.slice(last));
}
function hlProps(src){
  return src.split('\\n').map(line => {
    if(/^\\s*#/.test(line)) return '<span class="tok-cmt">'+esc(line)+'</span>';
    const i = line.indexOf('=');
    if(i < 0) return esc(line);
    return '<span class="tok-key">'+esc(line.slice(0,i))+'</span>=<span class="tok-val">'+esc(line.slice(i+1))+'</span>';
  }).join('\\n');
}
document.querySelectorAll('pre code').forEach(code => {
  const lang = (code.className.match(/language-(\\w+)/)||[])[1];
  const raw = code.textContent;
  if(lang === 'java') code.innerHTML = hlJava(raw);
  else if(lang === 'properties') code.innerHTML = hlProps(raw);
  else if(lang === 'bash') code.innerHTML = raw.split('\\n').map(l => /^\\s*#/.test(l) ? '<span class="tok-cmt">'+esc(l)+'</span>' : esc(l)).join('\\n');
});

/* ── quiz enhancement (per language section) ── */
document.querySelectorAll('.chapter').forEach(section => {
  const chNum = section.dataset.num;
  const secLang = section.dataset.lang;
  const L = I18N[secLang];
  const details = [...section.querySelectorAll('details')];
  if(!details.length) return;

  // banner under the quiz h2
  const quizH2 = [...section.querySelectorAll('h2')].find(h => /quiz|test/i.test(h.textContent));
  if(quizH2){
    const banner = document.createElement('div');
    banner.className = 'quiz-banner';
    banner.innerHTML = '<div class="qb-ico">🎯</div><div><h2>'+quizH2.textContent+'</h2>'+
      '<p>'+L.quizHint+'</p></div>';
    quizH2.replaceWith(banner);
  }

  details.forEach((det, qi) => {
    // progress is shared across languages: same chapter, same question number
    const key = 'score.'+chNum+'.'+qi;
    const card = document.createElement('div');
    card.className = 'quiz-card';
    const qwrap = document.createElement('div');
    qwrap.className = 'q';
    // collect question nodes: walk backwards from details to previous card/banner boundary
    let node = det.previousElementSibling;
    const qnodes = [];
    while(node && !node.classList?.contains('quiz-card') && !node.classList?.contains('quiz-banner') && node.tagName !== 'DETAILS'){
      qnodes.unshift(node); node = node.previousElementSibling;
    }
    det.parentNode.insertBefore(card, qnodes[0] || det);
    qnodes.forEach(n => qwrap.appendChild(n));
    card.appendChild(qwrap);

    const answer = document.createElement('div');
    answer.className = 'quiz-answer';
    const summary = det.querySelector('summary'); if(summary) summary.remove();
    answer.innerHTML = det.innerHTML;
    det.remove();

    const actions = document.createElement('div');
    actions.className = 'quiz-actions';
    const btnReveal = document.createElement('button');
    btnReveal.className = 'btn btn-reveal'; btnReveal.textContent = L.reveal;
    const btnOk = document.createElement('button');
    btnOk.className = 'btn btn-ok'; btnOk.textContent = L.got; btnOk.style.display = 'none';
    const btnBad = document.createElement('button');
    btnBad.className = 'btn btn-bad'; btnBad.textContent = L.missed; btnBad.style.display = 'none';
    const verdict = document.createElement('div'); verdict.className = 'quiz-verdict';
    actions.append(btnReveal, btnOk, btnBad);
    card.append(actions, answer, verdict);

    function applyScore(v, save){
      card.classList.toggle('scored-ok', v === 1);
      card.classList.toggle('scored-bad', v === 0);
      verdict.className = 'quiz-verdict ' + (v === 1 ? 'v-ok' : 'v-bad');
      verdict.textContent = v === 1 ? L.scoredOk : L.scoredBad;
      if(save){ store.set(key, v); updateSidebar(); updateSummary(section); }
    }
    btnReveal.onclick = () => {
      answer.classList.add('open');
      btnReveal.style.display = 'none';
      btnOk.style.display = btnBad.style.display = 'inline-block';
    };
    btnOk.onclick = () => applyScore(1, true);
    btnBad.onclick = () => applyScore(0, true);

    const saved = store.get(key, null);
    if(saved !== null){
      answer.classList.add('open');
      btnReveal.style.display = 'none';
      btnOk.style.display = btnBad.style.display = 'inline-block';
      applyScore(saved, false);
    }
  });

  // summary card after last quiz card
  const summaryEl = document.createElement('div');
  summaryEl.className = 'quiz-summary';
  summaryEl.innerHTML = '<div class="big"></div><p></p>';
  const resetBtn = document.createElement('button');
  resetBtn.className = 'btn'; resetBtn.textContent = L.retake;
  resetBtn.onclick = () => {
    details.forEach((_, qi) => localStorage.removeItem('jaai.score.'+chNum+'.'+qi));
    location.reload();
  };
  summaryEl.appendChild(resetBtn);
  const cards = section.querySelectorAll('.quiz-card');
  if(cards.length) cards[cards.length-1].after(summaryEl);
  updateSummary(section);
});

function chapterScore(chNum, lang){
  const section = document.getElementById('ch-'+(lang||LANG)+'-'+chNum);
  if(!section) return { total: 0, answered: 0, right: 0 };
  const total = section.querySelectorAll('.quiz-card').length;
  let answered = 0, right = 0;
  for(let qi = 0; qi < total; qi++){
    const v = store.get('score.'+chNum+'.'+qi, null);
    if(v !== null){ answered++; if(v === 1) right++; }
  }
  return { total, answered, right };
}
function updateSummary(section){
  const s = chapterScore(section.dataset.num, section.dataset.lang);
  const el = section.querySelector('.quiz-summary');
  if(!el) return;
  const L = I18N[section.dataset.lang];
  if(s.answered === s.total && s.total > 0){
    el.classList.add('show');
    el.querySelector('.big').textContent = s.right + '/' + s.total;
    el.querySelector('p').textContent = s.right === s.total ? L.perfect : L.complete;
  } else {
    el.classList.remove('show');
  }
}

/* ── language switcher ── */
const langBar = document.getElementById('langbar');
LANG_ORDER.forEach(code => {
  const b = document.createElement('button');
  b.type = 'button';
  b.dataset.lang = code;
  b.title = I18N[code].name;
  b.setAttribute('aria-label', I18N[code].name);
  b.innerHTML = I18N[code].flag;
  b.onclick = () => setLang(code);
  langBar.appendChild(b);
});
function setLang(code){
  if(!I18N[code] || code === LANG) return;
  LANG = code;
  store.set('lang', code);
  const url = new URL(location.href);
  url.searchParams.set('lang', code);
  history.replaceState(null, '', url.toString().replace(/%23/g, '#'));
  applyLang();
  show(current());
}
function applyLang(){
  const L = t();
  document.documentElement.lang = L.htmlLang;
  document.title = L.title;
  document.getElementById('brand-word').textContent = L.brand;
  document.getElementById('tagline').textContent = L.tagline;
  document.getElementById('foot-text').textContent = L.foot;
  langBar.querySelectorAll('button').forEach(b => b.classList.toggle('active', b.dataset.lang === LANG));
  buildNav();
  buildChapterFooters();
}

/* ── sidebar ── */
const navList = document.getElementById('chnav-list');
function buildNav(){
  navList.innerHTML = '';
  CHAPTERS().forEach(c => {
    const a = document.createElement('a');
    a.href = '#' + c.n;
    a.innerHTML = '<span class="num">'+c.n+'</span><span></span><span class="score"></span>';
    a.children[1].textContent = c.t;
    navList.appendChild(a);
  });
  updateSidebar();
}
function updateSidebar(){
  navList.querySelectorAll('a').forEach((a, i) => {
    const s = chapterScore(i + 1);
    const el = a.querySelector('.score');
    if(s.answered === 0){ el.textContent = ''; el.className = 'score'; }
    else { el.textContent = s.right + '/' + s.total; el.className = 'score' + (s.answered === s.total ? ' done' : ''); }
  });
}

/* prev/next footers, rebuilt on language change */
function buildChapterFooters(){
  document.querySelectorAll('.chapter').forEach(section => {
    const lang = section.dataset.lang;
    const L = I18N[lang];
    const chs = L.chapters;
    const n = parseInt(section.dataset.num);
    section.querySelector('.chnav')?.remove();
    const nav = document.createElement('div');
    nav.className = 'chnav';
    const prev = n > 1 ? '<a href="#'+(n-1)+'"><span class="dir">'+L.prev+'</span><span class="ttl"></span></a>' : '<a class="hidden"></a>';
    const next = n < chs.length ? '<a class="next" href="#'+(n+1)+'"><span class="dir">'+L.next+'</span><span class="ttl"></span></a>' : '<a class="hidden"></a>';
    nav.innerHTML = prev + next;
    const titles = nav.querySelectorAll('.ttl');
    let ti = 0;
    if(n > 1) titles[ti++].textContent = chs[n-2].t;
    if(n < chs.length) titles[ti].textContent = chs[n].t;
    section.appendChild(nav);
  });
}

function show(n){
  n = Math.min(Math.max(1, n), CHAPTERS().length);
  document.querySelectorAll('.chapter').forEach(s => s.classList.remove('visible'));
  document.getElementById('ch-'+LANG+'-'+n)?.classList.add('visible');
  navList.querySelectorAll('a').forEach((a, i) => a.classList.toggle('active', i === n-1));
  document.getElementById('sidebar').classList.remove('open');
  window.scrollTo({top: 0, behavior: 'instant'});
  updateBar();
}
function current(){ return parseInt(location.hash.slice(1)) || 1; }
window.addEventListener('hashchange', () => show(current()));

/* reading progress bar */
function updateBar(){
  const h = document.documentElement;
  const max = h.scrollHeight - h.clientHeight;
  document.getElementById('progressbar').style.width = (max > 0 ? (h.scrollTop / max) * 100 : 0) + '%';
}
window.addEventListener('scroll', updateBar, { passive: true });

/* keyboard: ← → switch chapters */
window.addEventListener('keydown', e => {
  if(e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return;
  if(e.key === 'ArrowRight' && current() < CHAPTERS().length) location.hash = '#' + (current() + 1);
  if(e.key === 'ArrowLeft' && current() > 1) location.hash = '#' + (current() - 1);
});

document.getElementById('menu-btn').onclick = () => document.getElementById('sidebar').classList.toggle('open');

applyLang();
show(current());
</script>
</body>
</html>
`;

writeFileSync(OUT, page);
console.log('written', OUT, (page.length / 1024).toFixed(0) + ' KB',
            '·', LANGS.map(l => l.code).join('/'));
