# Roteiro de demo — Cap. 6: WorkflowEngine & the scope

**Tempo-alvo:** ~2.5 min · **Objetivo:** uma chamada síncrona roda o workflow
inteiro na thread do chamador; o contexto `ThreadLocal` dá isolamento de graça.

**Beats:**
- `execute()`: ativa contexto → semeia evento → resolve agente + LLM → trigger →
  loop das fases ordenadas (gate/publica) → outcome → catch→dispatch handler →
  `finally` **sempre** desativa.
- Roda na **thread do chamador** (síncrono) → resposta no mesmo HTTP.
- LLM é `@Dependent` → um por workflow → **isolamento conversacional**.
- Ordem do `ParameterResolver`.
- Contexto `ThreadLocal` → isolamento entre workflows concorrentes; `deactivate`
  destrói os beans (`@PreDestroy`). Registrar esse Context é a **impressão digital**
  que o TCK usa.

---

## 🇬🇧 Script (spoken)

The engine is one method — `execute` — and it's the backbone. Walk through it: it
activates the `@WorkflowScoped` context on the current thread, seeds the trigger
event into a workflow context, and resolves the agent bean and the LLM. Then it runs
the trigger, and loops over the pre-sorted decisions and actions: for a decision it
checks whether to continue — false or null and it returns early — otherwise it
publishes the result into the context. Then the outcome. If anything throws, it
dispatches to the most specific handler. And in a `finally` block, it **always**
deactivates the context.

Two things to underline. First: it all runs on the caller's thread, because
`Event.fire` is synchronous. That's why our REST samples return the LLM's answer in
the very same HTTP response — when `fire` returns, every phase has already run.
Second: the LLM is resolved once per execution, and because it's a `@Dependent`
bean, each workflow gets its own instance — that's where the per-workflow
conversation isolation actually comes from. It's not magic; it's CDI scoping.

Parameter resolution has a clear order: the LLM first, then — only for handlers —
the in-flight exception, then values from the context by type, newest-first, then a
CDI bean, else null. That's what lets a phase just declare "give me the Fraud
object, the transaction, and this service" and get all three.

And the context itself is a `ThreadLocal`. Since each workflow runs on its own fire
thread, you get isolation between concurrent workflows for free; and on deactivate
every bean is destroyed — `@PreDestroy` fires. Nice full circle: registering this
very context is the fingerprint the TCK uses to detect a compatible implementation.
Now — where do the actual LLM calls go? That's the backends.

---

## 🇧🇷 Script (ensaio / tradução de apoio)

O engine é um método só — `execute` — e é a espinha dorsal. Percorrendo: ativa o
contexto de `@WorkflowScoped` na thread atual, semeia o evento do trigger num
workflow context, e resolve o bean do agente e o LLM. Aí roda o trigger e itera
sobre as decisions e actions já ordenadas: numa decision, checa se continua — false
ou null, sai cedo — senão, publica o resultado no contexto. Depois o outcome. Se
algo lança, ele despacha para o handler mais específico. E num bloco `finally`,
**sempre** desativa o contexto.

Dois pontos para reforçar. Primeiro: tudo roda na thread do chamador, porque o
`Event.fire` é síncrono. É por isso que os samples REST devolvem a resposta do LLM
na mesma resposta HTTP — quando o `fire` retorna, todas as fases já rodaram.
Segundo: o LLM é resolvido uma vez por execução e, como é um bean `@Dependent`, cada
workflow ganha a própria instância — é daí que vem, de verdade, o isolamento da
conversa por workflow. Não é mágica; é escopo do CDI.

A resolução de parâmetros tem uma ordem clara: o LLM primeiro, depois — só para
handlers — a exceção em curso, depois valores do contexto por tipo (mais recente
primeiro), depois um bean CDI, senão null. É isso que deixa uma fase só declarar
"me dá o objeto Fraud, a transação e este serviço" e receber os três.

E o contexto em si é um `ThreadLocal`. Como cada workflow roda na própria thread do
fire, você ganha isolamento entre workflows concorrentes de graça; e no deactivate
cada bean é destruído — dispara `@PreDestroy`. Um belo fechamento de ciclo:
registrar justamente esse contexto é a impressão digital que o TCK usa para detectar
uma implementação compatível. Agora — para onde vão as chamadas de LLM de verdade?
São os backends.

---

➡️ Próximo: [Cap. 7 — LLM backends & config](demo-roteiro-cap7.md)