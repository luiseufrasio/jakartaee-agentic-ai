// Builds tutorials/html/index.html from tutorials/md/*.md
// Payara palette (extracted from payara.fish frontend.css):
//   primary orange #ee992f | brand navy #001b27 | inversed #00131b
//   light #f9fafb | subtle #f4f4f4 | body #121212 | orange-subtle #fcebd5
import { marked } from 'marked';
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
const HERE = dirname(fileURLToPath(import.meta.url));
const MD = join(HERE, '..', 'md');
const OUT = join(HERE, 'index.html');

const chapters = [
  { file: '01-overview.md',                 short: 'Overview & Motivation' },
  { file: '02-programming-model.md',        short: 'The Programming Model' },
  { file: '03-largelanguagemodel.md',       short: 'LargeLanguageModel & Errors' },
  { file: '04-tck.md',                      short: 'The TCK' },
  { file: '05-payara-impl-cdi-extension.md',short: 'Payara Impl: CDI Extension' },
  { file: '06-payara-impl-engine.md',       short: 'Payara Impl: WorkflowEngine' },
  { file: '07-llm-backends.md',             short: 'LLM Backends & Config' },
  { file: '08-samples.md',                  short: 'The Samples' },
  { file: '09-presentation-guide.md',       short: 'Presentation Playbook' },
];

marked.setOptions({ gfm: true, breaks: false });

function render(md) {
  // strip the trailing "➡️ Next:" nav line (the app has its own nav)
  md = md.replace(/^➡️ Next:.*$/m, '').replace(/^---\s*$(?![\s\S]*^---\s*$)/m, m => m);
  return marked.parse(md);
}

const sections = chapters.map((ch, i) => {
  const html = render(readFileSync(join(MD, ch.file), 'utf8'));
  return `<section class="chapter" id="ch-${i + 1}" data-num="${i + 1}">\n${html}\n</section>`;
}).join('\n');

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

<aside id="sidebar">
  <div class="brand">
    <div class="fish">
      <svg width="34" height="34" viewBox="0 0 34 34" fill="none" xmlns="http://www.w3.org/2000/svg">
        <rect width="34" height="34" rx="8" fill="#ee992f"/>
        <path d="M6 17c3.2-4.6 7.6-7 12.2-7 3.4 0 6.6 1.3 9.3 3.8l-2.3 3.2 2.3 3.2c-2.7 2.5-5.9 3.8-9.3 3.8-4.6 0-9-2.4-12.2-7z" fill="#001b27"/>
        <circle cx="22.6" cy="15.4" r="1.5" fill="#ee992f"/>
      </svg>
      <h1>Jakarta <em>Agentic AI</em><br>Tutorial</h1>
    </div>
    <p>Spec · Payara implementation · Samples — with a quiz after every chapter.</p>
  </div>
  <nav id="chnav-list"></nav>
  <div class="foot">Built for the Payara Conference talk · colors from <a href="https://payara.fish" target="_blank" rel="noopener">payara.fish</a></div>
</aside>

<div id="main"><div id="content">
${sections}
</div></div>

<script>
const CHAPTERS = ${JSON.stringify(chapters.map((c, i) => ({ n: i + 1, t: c.short })))};
const store = {
  get(k, d){ try { return JSON.parse(localStorage.getItem('jaai.'+k)) ?? d; } catch(e){ return d; } },
  set(k, v){ try { localStorage.setItem('jaai.'+k, JSON.stringify(v)); } catch(e){} }
};

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

/* ── quiz enhancement ── */
document.querySelectorAll('.chapter').forEach(section => {
  const chNum = section.dataset.num;
  const details = [...section.querySelectorAll('details')];
  if(!details.length) return;

  // banner under the quiz h2
  const quizH2 = [...section.querySelectorAll('h2')].find(h => /quiz/i.test(h.textContent));
  if(quizH2){
    const banner = document.createElement('div');
    banner.className = 'quiz-banner';
    banner.innerHTML = '<div class="qb-ico">🎯</div><div><h2>'+quizH2.textContent+'</h2>'+
      '<p>Answer in your head (or out loud — it is Q&amp;A rehearsal), then reveal and score yourself.</p></div>';
    quizH2.replaceWith(banner);
  }

  details.forEach((det, qi) => {
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
    btnReveal.className = 'btn btn-reveal'; btnReveal.textContent = '💡 Reveal answer';
    const btnOk = document.createElement('button');
    btnOk.className = 'btn btn-ok'; btnOk.textContent = '✓ I got it'; btnOk.style.display = 'none';
    const btnBad = document.createElement('button');
    btnBad.className = 'btn btn-bad'; btnBad.textContent = '✗ Missed it'; btnBad.style.display = 'none';
    const verdict = document.createElement('div'); verdict.className = 'quiz-verdict';
    actions.append(btnReveal, btnOk, btnBad);
    card.append(actions, answer, verdict);

    function applyScore(v, save){
      card.classList.toggle('scored-ok', v === 1);
      card.classList.toggle('scored-bad', v === 0);
      verdict.className = 'quiz-verdict ' + (v === 1 ? 'v-ok' : 'v-bad');
      verdict.textContent = v === 1 ? 'Scored: correct ✓' : 'Scored: to review ✗ — come back to this one before the talk';
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
  resetBtn.className = 'btn'; resetBtn.textContent = '↺ Retake quiz';
  resetBtn.onclick = () => {
    details.forEach((_, qi) => localStorage.removeItem('jaai.score.'+chNum+'.'+qi));
    location.reload();
  };
  summaryEl.appendChild(resetBtn);
  const cards = section.querySelectorAll('.quiz-card');
  if(cards.length) cards[cards.length-1].after(summaryEl);
  updateSummary(section);
});

function chapterScore(chNum){
  const section = document.getElementById('ch-'+chNum);
  const total = section.querySelectorAll('.quiz-card').length;
  let answered = 0, right = 0;
  for(let qi = 0; qi < total; qi++){
    const v = store.get('score.'+chNum+'.'+qi, null);
    if(v !== null){ answered++; if(v === 1) right++; }
  }
  return { total, answered, right };
}
function updateSummary(section){
  const chNum = section.dataset.num;
  const s = chapterScore(chNum);
  const el = section.querySelector('.quiz-summary');
  if(!el) return;
  if(s.answered === s.total && s.total > 0){
    el.classList.add('show');
    el.querySelector('.big').textContent = s.right + '/' + s.total;
    el.querySelector('p').textContent = s.right === s.total
      ? 'Perfect score — you are stage-ready for this chapter. 🎤'
      : 'Quiz complete. Revisit the ✗ questions above before moving on.';
  }
}

/* ── sidebar + routing ── */
const navList = document.getElementById('chnav-list');
CHAPTERS.forEach(c => {
  const a = document.createElement('a');
  a.href = '#' + c.n;
  a.innerHTML = '<span class="num">'+c.n+'</span><span>'+c.t+'</span><span class="score"></span>';
  navList.appendChild(a);
});
function updateSidebar(){
  document.querySelectorAll('#chnav-list a').forEach((a, i) => {
    const s = chapterScore(i + 1);
    const el = a.querySelector('.score');
    if(s.answered === 0){ el.textContent = ''; el.className = 'score'; }
    else { el.textContent = s.right + '/' + s.total; el.className = 'score' + (s.answered === s.total ? ' done' : ''); }
  });
}
function show(n){
  n = Math.min(Math.max(1, n), CHAPTERS.length);
  document.querySelectorAll('.chapter').forEach(s => s.classList.remove('visible'));
  document.getElementById('ch-'+n).classList.add('visible');
  document.querySelectorAll('#chnav-list a').forEach((a, i) => a.classList.toggle('active', i === n-1));
  document.getElementById('sidebar').classList.remove('open');
  window.scrollTo({top: 0, behavior: 'instant'});
  updateBar();
}
function current(){ return parseInt(location.hash.slice(1)) || 1; }
window.addEventListener('hashchange', () => show(current()));

/* prev/next footers */
document.querySelectorAll('.chapter').forEach(section => {
  const n = parseInt(section.dataset.num);
  const nav = document.createElement('div');
  nav.className = 'chnav';
  const prev = n > 1 ? '<a href="#'+(n-1)+'"><span class="dir">← Previous</span><span class="ttl">'+CHAPTERS[n-2].t+'</span></a>' : '<a class="hidden"></a>';
  const next = n < CHAPTERS.length ? '<a class="next" href="#'+(n+1)+'"><span class="dir">Next →</span><span class="ttl">'+CHAPTERS[n].t+'</span></a>' : '<a class="hidden"></a>';
  nav.innerHTML = prev + next;
  section.appendChild(nav);
});

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
  if(e.key === 'ArrowRight' && current() < CHAPTERS.length) location.hash = '#' + (current() + 1);
  if(e.key === 'ArrowLeft' && current() > 1) location.hash = '#' + (current() - 1);
});

document.getElementById('menu-btn').onclick = () => document.getElementById('sidebar').classList.toggle('open');

updateSidebar();
show(current());
</script>
</body>
</html>
`;

writeFileSync(OUT, page);
console.log('written', OUT, (page.length / 1024).toFixed(0) + ' KB');
