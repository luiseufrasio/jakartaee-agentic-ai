# Roteiro de demo — Cap. 8: LIVE DEMO — Course Content Studio

**Tempo-alvo:** ~6–7 min · **Objetivo:** mostrar o app de educação com **dois
agentes encadeados por eventos CDI**, ponta a ponta, e amarrar tudo que foi
apresentado (fases, extensão, engine, config).

---

## ✅ Checklist pré-voo (fazer ANTES da palestra)

- [ ] Payara no ar com o `agentic-ai-core`; `course.war` deployado em `/course`.
- [ ] **Token do Vertex válido** (causa nº 1 de falha ao vivo): rodar
      `gcloud auth application-default print-access-token` para renovar o ADC.
      Conferir `ANTHROPIC_VERTEX_PROJECT_ID` e `CLOUD_ML_REGION` no ambiente **do
      servidor** (setados antes do `start-domain`/`restart-domain`).
- [ ] Browser aberto em <http://localhost:8080/course/> · segunda aba pronta para
      `student.html` · (opcional) `server.log` em `tail` numa 2ª tela.
- [ ] **Texto do capítulo pronto para colar** (ex.: Física — "Newton's Second Law",
      2–3 parágrafos) num snippet à mão.
- [ ] Zoom/fonte do browser aumentados.
- [ ] **Pré-aquecer:** gerar um pacote 1× antes da palestra (prova o caminho do
      token/modelo). Ter um **screenshot/GIF de backup** de cada tela.

---

## 🗣️ Abertura

**🇬🇧** "This is an education app — but really it's the whole talk, running. A
teacher pastes a chapter, and an agent writes an intro, a quiz, and a conclusion.
Then a *second* agent publishes the student version. Watch the popup — it streams
the agent's phases live, over Server-Sent Events."

**🇧🇷** "É um app de educação — mas, na prática, é a palestra inteira funcionando.
O professor cola um capítulo e um agente escreve introdução, quiz e conclusão.
Depois um *segundo* agente publica a versão do aluno. Olhem o popup — ele transmite
as fases do agente ao vivo, por Server-Sent Events."

---

## ▶️ Passo a passo

### 1 · Gerar o pacote
**[FAZER]** Subject = **Physics**, título "Newton's Second Law", colar o capítulo,
clicar **Generate packet**. Apontar para os passos no popup.

**[FALAR] 🇬🇧** "That's exactly the workflow from the API: trigger, a decision gate,
then writeIntro, writeQuiz, writeConclusion, and the outcome — streaming live.
Notice the formulas render properly: the per-subject rubric asks the model for
LaTeX, and we render it with MathJax, self-hosted, so it works offline."

**[FALAR] 🇧🇷** "É exatamente o workflow da API: trigger, um gate de decisão, depois
writeIntro, writeQuiz, writeConclusion e o outcome — transmitindo ao vivo. Reparem
que as fórmulas renderizam: a rubrica por matéria pede LaTeX ao modelo e a gente
renderiza com MathJax, self-hosted, então funciona offline."

### 2 · Refinar só o quiz (mostra HITL por seção + memória de workflow)
**[FAZER]** Refine → seção **Quiz** → instrução: *"make the questions harder and add
a final open question for the student to answer in free text"* → **Refine**.

**[FALAR] 🇬🇧** "Refinement sends the current artifact back to the model, and I'm
scoping it to just the quiz — the intro and conclusion are untouched. The new
question comes back typed as 'open'. And note: the conclusion earlier didn't re-send
the chapter — it rode the workflow's own conversational memory."

**[FALAR] 🇧🇷** "O refino manda o artefato atual de volta ao modelo, e eu limitei só
ao quiz — intro e conclusão ficam intactas. A nova questão volta com o tipo 'open'.
E reparem: a conclusão, antes, não reenviou o capítulo — ela usou a própria memória
conversacional do workflow."

### 3 · Aprovar e publicar (O PONTO ALTO: multi-agente por evento)
**[FAZER]** Clicar **Approve & publish**. Apontar para o popup do segundo agente.

**[FALAR] 🇬🇧** "Approving fires a second CDI event — and that triggers a completely
separate agent, the PublishAgent, which writes the learning objectives and publishes
the lesson. Two agents, composed purely by an event, with my approval as the
human-in-the-loop gate in between. There is no orchestrator — just the same CDI
events you saw in the spec."

**[FALAR] 🇧🇷** "Aprovar dispara um segundo evento CDI — e isso aciona um agente
totalmente separado, o PublishAgent, que escreve os objetivos de aprendizagem e
publica a aula. Dois agentes, compostos puramente por um evento, com a minha
aprovação como gate humano no meio. Não há orquestrador — só os mesmos eventos CDI
que vocês viram na spec."

### 4 · A visão do aluno + correção por LLM
**[FAZER]** Clicar **View published lesson ↗** (`student.html`). Responder uma
questão de múltipla escolha → **Check answers** (verde/vermelho). Depois, na questão
**aberta**, digitar uma resposta → **Check answers**.

**[FALAR] 🇬🇧** "This is what the student sees. Multiple-choice is checked locally.
But the open answer is graded by the LLM — a 0-to-100 similarity score against a
model answer, mapped to correct, partial, or incorrect, with the thresholds enforced
on the server. And that grading call is just an injected `LargeLanguageModel` in a
plain REST resource — no agent — because the facade is `@Dependent`. Not every LLM
call has to be an agent."

**[FALAR] 🇧🇷** "É o que o aluno vê. Múltipla escolha é conferida localmente. Mas a
resposta aberta é corrigida pelo LLM — um score de similaridade de 0 a 100 contra
uma resposta-modelo, mapeado para correta, parcial ou errada, com os limiares
aplicados no servidor. E essa correção é só um `LargeLanguageModel` injetado num
resource REST comum — sem agente — porque o facade é `@Dependent`. Nem toda chamada
ao LLM precisa ser um agente."

### 5 · (Opcional) O log conta a história
**[FAZER]** Mostrar rapidamente o `server.log`.

**[FALAR] 🇬🇧** "And there's the whole story in the log — both agents' phases, in
order."

**[FALAR] 🇧🇷** "E aí está a história inteira no log — as fases dos dois agentes, em
ordem."

---

## 🧯 Plano B (se algo falhar ao vivo)

- **Generate travou/deu erro:** quase sempre é **token ADC expirado**. **Não**
  debugar no palco — narrar pelo pacote já gerado no pré-aquecimento (recarregar a
  página) ou pelo screenshot de backup. Frase de contorno 🇬🇧: *"Looks like my cloud
  token just expired — here's the run I did moments ago; the flow is identical."*
- **Quiz veio como placeholder:** é a rede de segurança. 🇬🇧 *"The model returned
  unusable JSON there, so the agent inserted a placeholder instead of crashing —
  that's the defensive parsing plus `@HandleException` safety net we built."* Depois,
  refinar o quiz de novo.
- **Rede/Vertex fora:** ir direto para o **GIF/screenshots** e narrar por cima. Ter
  esse backup aberto numa aba.

---

## 🎬 Fechamento (transição para os takeaways)

**🇬🇧** "So that's Jakarta Agentic AI, end to end: the same phase model from the API,
the CDI extension and the engine running it, the provider swapped by a single config
file — and here, two agents collaborating through events to build *and* grade a
lesson. All standard Jakarta EE, no proprietary framework."

**🇧🇷** "Então esse é o Jakarta Agentic AI, ponta a ponta: o mesmo modelo de fases da
API, a extensão CDI e o engine rodando, o provider trocado por um único arquivo de
config — e aqui, dois agentes colaborando por eventos para construir *e* corrigir uma
aula. Tudo Jakarta EE padrão, sem framework proprietário."

---

## ⏱️ Orçamento de tempo (visão geral da demo)

| Parte | Alvo |
| --- | --- |
| Cap. 2 — Programming model | ~3.0 min |
| Cap. 5 — CDI extension | ~3.0 min |
| Cap. 6 — WorkflowEngine | ~2.5 min |
| Cap. 7 — LLM backends | ~2.5 min |
| Cap. 8 — Live demo | ~6.5 min |
| **Total** | **~17.5 min** (folga para Q&A dentro dos 20) |