# Roteiro de demo — Cap. 5: Payara — the CDI extension

**Tempo-alvo:** ~3 min · **Objetivo:** é tudo CDId padrão — uma extensão portável
transforma classes `@Agent` em workflows; nada proprietário.

**Beats:**
- `AgenticAIExtension` = extensão CDI **portável** (SPI de extensões). Sem bytecode
  weaving, sem SPI proprietária.
- `ProcessAnnotatedType` → `processAgent`: aplica `@WorkflowScoped` default; **REMOVE
  `@Observes`** do `@Trigger` (o truque → ponto de entrada único, evita dupla
  invocação).
- `ProcessManagedBean` → `watchForLlm`: LLM default **auto-vetado** (LLM da app
  vence → sem `AmbiguousResolutionException`; testes rodam offline).
- `AfterBeanDiscovery`: registra o Context de `@WorkflowScoped`; **um observer
  sintético por agente** → `WorkflowEngine.execute`; registra o LLM default se a app
  não trouxe.
- `buildMetadata`: validação **no deploy** → `DefinitionException` (fail fast).

---

## 🇬🇧 Script (spoken)

So how does Payara turn a class with these annotations into a running workflow? With
nothing but standard CDI. There's a portable CDI extension — `AgenticAIExtension` —
that hooks into the container's bootstrap. No bytecode weaving, no proprietary SPI.
This is the part I love, because it means the model is portable in principle.

Three hooks. First, `ProcessAnnotatedType`, for every `@Agent` class. It does two
things: it applies the default `@WorkflowScoped` if you didn't declare a scope, and
— the key trick — it **removes the `@Observes`** from your `@Trigger` method. Why?
If it stayed, CDI would invoke your trigger directly, as a plain observer — outside
the engine, with no workflow context, and no later phases. And since the extension
also registers its own observer, your trigger would fire twice. By stripping
`@Observes`, the synthetic observer becomes the single entry point, and the workflow
context wraps the whole run.

Second hook: `ProcessManagedBean`. It watches whether your application ships its own
`LargeLanguageModel`. That drives a self-vetoing default: if you provide an LLM bean
— the TCK stub, or a LangChain4j bean — Payara registers no default, so there's no
`AmbiguousResolutionException`. The app's LLM always wins; the runtime's is only a
fallback. That's exactly why the integration tests run with zero network.

Third: `AfterBeanDiscovery`. Here it registers the `@WorkflowScoped` context, and
for each agent it registers **one synthetic observer** for the trigger's event type
— that observer just calls `WorkflowEngine.execute`. And if the app didn't bring an
LLM, it registers the default backend from config.

And crucially, it **validates each agent at deploy time** — two triggers, two
outcomes, inconsistent ordering — all fail the deployment with a
`DefinitionException`. Fail fast, at boot, not in production. Now let's open the
engine that observer calls.

---

## 🇧🇷 Script (ensaio / tradução de apoio)

Então, como a Payara transforma uma classe com essas anotações num workflow que
roda? Com nada além de CDI padrão. Existe uma extensão CDI portável —
`AgenticAIExtension` — que se pluga no bootstrap do container. Sem bytecode weaving,
sem SPI proprietária. É a parte que eu mais gosto, porque significa que o modelo é
portável em princípio.

Três ganchos. Primeiro, `ProcessAnnotatedType`, para cada classe `@Agent`. Faz duas
coisas: aplica o `@WorkflowScoped` default se você não declarou escopo e — o truque
central — **remove o `@Observes`** do método `@Trigger`. Por quê? Se ficasse, o CDI
invocaria o trigger direto, como observer comum — fora do engine, sem contexto de
workflow, sem as fases seguintes. E como a extensão também registra o observer dela,
o trigger dispararia duas vezes. Removendo o `@Observes`, o observer sintético vira
o ponto de entrada único, e o contexto do workflow envolve a execução inteira.

Segundo gancho: `ProcessManagedBean`. Ele observa se a sua aplicação fornece o
próprio `LargeLanguageModel`. Isso comanda um default auto-vetado: se você fornece
um bean de LLM — o stub do TCK, ou um bean LangChain4j — a Payara não registra
default nenhum, então não há `AmbiguousResolutionException`. O LLM da aplicação
sempre vence; o do runtime é só fallback. É exatamente por isso que os testes de
integração rodam com zero rede.

Terceiro: `AfterBeanDiscovery`. Aqui ela registra o contexto de `@WorkflowScoped` e,
para cada agente, registra **um observer sintético** para o tipo de evento do
trigger — esse observer só chama `WorkflowEngine.execute`. E, se a app não trouxe
LLM, registra o backend default a partir da config.

E, crucialmente, ela **valida cada agente no deploy** — dois triggers, dois
outcomes, ordenação inconsistente — tudo derruba o deployment com
`DefinitionException`. Fail fast, no boot, não em produção. Agora vamos abrir o
engine que esse observer chama.

---

➡️ Próximo: [Cap. 6 — WorkflowEngine & the scope](demo-roteiro-cap6.md)