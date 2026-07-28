# Roteiro de demo — Cap. 2: The Programming Model

**Tempo-alvo:** ~3 min · **Objetivo:** mostrar que um agente é só um bean CDI com
um workflow de fases; dados fluem por tipo; erros estruturais caem no deploy.

**Beats (bata nestes pontos):**
- `@Agent` = bean CDI; escopo default `@WorkflowScoped`.
- 5 anotações de fase: `@Trigger` (entrada, evento CDI, exatamente 1) · `@Decision`
  (gates) · `@Action` (trabalho) · `@Outcome` (fechamento) · `@HandleException`.
- Dados fluem **por tipo** pelo workflow context — sem passar parâmetro na mão.
- Só **dois escopos**; a conversa do LLM é **sempre por workflow**.
- Lei da consistência de ordenação (tudo ou nada) → erro de deploy.

---

## 🇬🇧 Script (spoken)

Let's start with the programming model — and the big idea is deliberately small:
an agent is just a CDI bean. You annotate a class with `@Agent`, and inside it you
write methods, each tagged with one of five phase annotations.

The entry point is `@Trigger` — exactly one per agent, fired by a CDI event. So you
start a workflow simply by firing an event: from a REST call, a timer, a message,
anything. Then `@Decision` methods are gates — they return a boolean, a `Result`,
or a domain object; return false or null and the workflow stops cleanly, right
there. `@Action` methods do the real work — call a service, persist, query the LLM.
`@Outcome` is the closing phase. And `@HandleException` is recovery — it catches an
exception from any phase; return normally and the workflow continues, rethrow and
it stops.

Now the part that makes this a framework and not glue code: data flows between
phases **by type**, through a workflow context. The trigger event is added
automatically; any non-void phase return is added too; and every later phase just
declares the parameters it wants, by type, and the container resolves them. No
manual parameter passing. It's CDI injection, scoped to one workflow run.

On scopes — there are only two. The default, `@WorkflowScoped`, gives you a fresh
instance per execution, so you keep state in plain fields with no locking — a
private scratchpad for that run. Or `@ApplicationScoped`, one shared instance, for
resources you load once — but then thread-safety is on you. And the subtle bit
worth remembering: even on a shared agent, the **LLM conversation is always
isolated per workflow**. Two requests never see each other's conversation.

One rule that always comes up in Q&A: ordering. By default decisions and actions
run in declaration order, but you can pin it with `@Priority` or an `order`. The one
law — if any phase declares an explicit order, they all must; mixing is a deployment
error, caught at boot. Fail fast, not at 2 a.m. in production.

So: five annotations, data flowing by type, two scopes, structural mistakes caught
at deployment. That's the whole model. Now let's see how Payara makes it work.

---

## 🇧🇷 Script (ensaio / tradução de apoio)

Vamos começar pelo modelo de programação — e a ideia central é de propósito
pequena: um agente é só um bean CDI. Você anota a classe com `@Agent` e, dentro
dela, escreve métodos, cada um marcado com uma de cinco anotações de fase.

A entrada é `@Trigger` — exatamente um por agente, disparado por um evento CDI.
Então você inicia um workflow só disparando um evento: de um REST, um timer, uma
mensagem, qualquer coisa. Os `@Decision` são gates — retornam boolean, `Result` ou
um objeto de domínio; retornou false ou null, o workflow para ali, de forma limpa.
Os `@Action` fazem o trabalho real — chamar serviço, persistir, consultar o LLM.
`@Outcome` é a fase de fechamento. E `@HandleException` é recuperação — captura
exceção de qualquer fase; retornou normal, o workflow segue; relançou, ele para.

O que faz isso parecer um framework e não código-cola: os dados fluem entre fases
**por tipo**, por um workflow context. O evento do trigger entra automaticamente;
todo retorno não-void de fase também entra; e cada fase seguinte só declara os
parâmetros que quer, por tipo, e o container resolve. Sem passar parâmetro na mão.
É injeção CDI, no escopo de uma execução.

Escopos — só existem dois. O default, `@WorkflowScoped`, dá uma instância nova por
execução, então você guarda estado em campos comuns sem lock — um rascunho privado
daquela execução. Ou `@ApplicationScoped`, uma instância compartilhada, para
recursos caros carregados uma vez — mas aí thread-safety é com você. E o detalhe
sutil que vale lembrar: mesmo num agente compartilhado, a **conversa do LLM é
sempre isolada por workflow**. Duas requisições nunca veem a conversa uma da outra.

Uma regra que sempre aparece no Q&A: ordenação. Por padrão, decisions e actions
rodam na ordem de declaração, mas dá pra fixar com `@Priority` ou `order`. A única
lei — se uma fase declara ordem explícita, todas têm que declarar; misturar é erro
de deploy, pego no boot. Fail fast, não às 2h da manhã em produção.

Então: cinco anotações, dados por tipo, dois escopos, erros estruturais pegos no
deploy. É o modelo inteiro. Agora vamos ver como a Payara faz isso funcionar.

---

➡️ Próximo: [Cap. 5 — the CDI extension](demo-roteiro-cap5.md)