# Capítulo 5 — Implementação Payara: a extensão CDI

A partir daqui saímos da spec e entramos no runtime da Payara
(`fish.payara.ai.agent.*`, módulo `agentic-ai-core`). A porta de entrada é a
**extensão CDI portável** `AgenticAIExtension` — ela transforma classes `@Agent`
em workflows executáveis usando apenas mecanismos padrão do CDI (SPI de extensões).

## Visão geral do pipeline de boot

```
Deploy da aplicação
  │
  ├─ ProcessAnnotatedType (por classe) ──► processAgent()
  │     • aplica @WorkflowScoped default se não houver escopo
  │     • REMOVE @Observes do método @Trigger
  │     • coleciona a classe do agente
  │
  ├─ ProcessManagedBean (por bean) ──► watchForLlm()
  │     • marca se a aplicação fornece seu próprio LargeLanguageModel
  │
  └─ AfterBeanDiscovery ──► afterBeanDiscovery()
        • registra o WorkflowScopeContext (o Context do @WorkflowScoped)
        • cria o WorkflowEngine
        • para cada agente: valida metadados + registra um OBSERVER SINTÉTICO
        • se a app não trouxe LLM: registra o LLM default (backend via config)
```

## `processAgent` — preparando cada agente

Para cada tipo anotado com `@Agent`:

1. **Escopo default.** Se a classe não tem `@WorkflowScoped` nem
   `@ApplicationScoped`, a extensão adiciona `WorkflowScoped.Literal.INSTANCE` via
   `configureAnnotatedType()` — é assim que o "default é WorkflowScoped" da spec é
   implementado na prática.
2. **Remoção do `@Observes` do trigger.** Este é o truque central da implementação:
   se o desenvolvedor escreveu `@Trigger void on(@Observes MyEvent e)`, o CDI
   invocaria o método **diretamente** como observer comum — fora do engine, sem
   contexto de workflow ativo e sem as fases seguintes. A extensão **remove a
   anotação `@Observes`** do parâmetro, e o **observer sintético** registrado depois
   passa a ser o único ponto de entrada. Isso evita a **dupla invocação** do trigger
   e garante que o contexto do workflow envolva a execução inteira.

## `watchForLlm` — o LLM default auto-vetado

A extensão observa cada `ProcessManagedBean` e liga a flag `appProvidesLlm` se
algum bean **da aplicação** tem `LargeLanguageModel` entre seus tipos.

No `afterBeanDiscovery`, **só se a aplicação não trouxe LLM próprio**, o runtime
registra o seu: um bean `@Dependent` criado com
`new LargeLanguageModelImpl(backend)`, onde o backend vem da
`LlmBackendFactory.create(config)` (capítulo 7).

Por que esse jogo? Se o runtime registrasse seu LLM incondicionalmente e a aplicação
também fornecesse um (o stub do TCK, ou um bean LangChain4j), a injeção de
`LargeLanguageModel` daria **`AmbiguousResolutionException`**. O "self-vetoing"
garante: **o LLM da aplicação sempre vence; o do runtime é só fallback**. Note que
beans sintéticos não passam por `ProcessManagedBean`, então o próprio LLM default
não engana a detecção.

## `afterBeanDiscovery` — contexto, engine e observers sintéticos

```java
afterBeanDiscovery.addContext(workflowScopeContext);            // registra @WorkflowScoped
...
afterBeanDiscovery.addObserverMethod()
        .beanClass(agentClass)
        .observedType(eventType)                                 // tipo do evento do @Trigger
        .notifyWith(ctx -> workflowEngine.execute(agentMetadata, ctx.getEvent()));
```

Para cada agente é registrado **um observer sintético** para o tipo de evento do
trigger. Quando alguém faz `event.fire(new Question(...))`, é esse observer que
recebe — e ele delega ao `WorkflowEngine.execute(...)`, que roda o workflow inteiro.
Agentes cujo trigger não declara tipo de evento são pulados (reservado para
triggering programático futuro).

O **tipo do evento** é extraído do trigger com esta precedência: parâmetro
anotado com `@Observes` (mesmo que a anotação vá ser removida, ela declara a
intenção); senão, o primeiro parâmetro que **não** seja `LargeLanguageModel`.

## `buildMetadata` — validação em tempo de deploy

O metadado de cada agente (`AgentMetadata`) é montado por reflexão e **validado no
deploy** — a filosofia é "fail fast": erro estrutural derruba o deployment com
`DefinitionException`, não explode em runtime. Casos:

| Violação | Resultado |
| --- | --- |
| Mais de um `@Trigger` | `DefinitionException` |
| Nenhum `@Trigger` | `DefinitionException` |
| Mais de um `@Outcome` | `DefinitionException` |
| Agente `@WorkflowScoped` com `@Observes` fora do `@Trigger` | `DefinitionException` |
| Mistura de fases com e sem ordenação explícita | `DefinitionException` ("Inconsistent order") |

Depois da validação:

- **Fases ordenadas:** se alguma fase tem ordenação explícita (`@Priority` ou
  `order != 0` — encapsulado em `PhaseMethod.isExplicitlyOrdered()`), ordena por
  `sortKey`; senão, ordena pela **ordem de declaração no fonte**, obtida por
  `ClassMethodOrder`, que lê a **tabela de métodos do arquivo `.class`** — mais
  confiável que `getDeclaredMethods()`, cuja ordem a JVM não garante.
- **Handlers ordenados do mais específico para o mais genérico** (comparação por
  `isAssignableFrom` entre os tipos de exceção dos parâmetros), preparando a
  seleção de handler do engine.

## Bean Validation opcional

A extensão tenta construir um `ExecutableValidator`
(`Validation.buildDefaultValidatorFactory()`); se não houver provider de Bean
Validation no classpath, retorna `null` e o engine simplesmente **pula** a
validação de parâmetros — integração graciosa, não obrigatória.

---

## Quiz — Capítulo 5

**1.** Por que a extensão **remove** o `@Observes` do método `@Trigger` durante o
`ProcessAnnotatedType`?

<details><summary>Ver resposta</summary>

Se o `@Observes` ficasse, o container CDI invocaria o método trigger **diretamente**
como observer comum — sem passar pelo `WorkflowEngine`, sem contexto
`@WorkflowScoped` ativo e sem as fases seguintes; e como o engine também registra um
observer sintético para o mesmo evento, o trigger seria invocado **duas vezes**.
Removendo a anotação, o observer sintético vira o **único ponto de entrada** do
workflow.
</details>

**2.** A aplicação faz o deploy com um bean próprio que implementa
`LargeLanguageModel` (ex.: o stub do TCK). O que o runtime da Payara faz com o LLM
default dele, e o que aconteceria sem esse mecanismo?

<details><summary>Ver resposta</summary>

O runtime **não registra** seu LLM default (a flag `appProvidesLlm` foi ligada pelo
`ProcessManagedBean`). Sem esse "self-vetoing", haveria dois beans elegíveis para o
mesmo ponto de injeção e o deploy falharia com **`AmbiguousResolutionException`**.
Regra prática: o LLM da aplicação sempre vence; o do runtime é fallback.
</details>

**3.** Cite três estruturas de agente que derrubam o deployment com
`DefinitionException`.

<details><summary>Ver resposta</summary>

Qualquer três destas: (a) dois métodos `@Trigger`; (b) nenhum `@Trigger`;
(c) dois métodos `@Outcome`; (d) agente `@WorkflowScoped` com método `@Observes`
fora do trigger; (e) mistura de `@Decision`/`@Action` com ordenação explícita e sem
ordenação ("Inconsistent order").
</details>

**4.** Quando nenhuma fase declara `@Priority`/`order`, como a implementação obtém a
ordem de declaração dos métodos, já que `getDeclaredMethods()` não garante ordem?

<details><summary>Ver resposta</summary>

Via `ClassMethodOrder`, que lê a **tabela de métodos diretamente do bytecode do
arquivo `.class`**, onde os métodos aparecem na ordem em que foram declarados no
fonte. Isso implementa o requisito da spec (ordem de declaração como fallback) de
forma determinística.
</details>

**5.** O que exatamente o observer sintético registrado em `afterBeanDiscovery` faz
quando o evento do trigger é disparado?

<details><summary>Ver resposta</summary>

Ele chama `workflowEngine.execute(agentMetadata, eventContext.getEvent())` — ou
seja, entrega o evento ao engine, que ativa o contexto `@WorkflowScoped`, resolve o
bean do agente e o LLM, e executa todas as fases na ordem (trigger → decisions/
actions → outcome), com despacho de exceções. O observer é o elo entre o mundo CDI
(`Event.fire`) e o motor de orquestração.
</details>

---

➡️ Próximo: [Capítulo 6 — WorkflowEngine e escopo](06-implementacao-engine.md)
