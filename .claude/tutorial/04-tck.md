# Capítulo 4 — O TCK (Technology Compatibility Kit)

## Para que serve

O TCK é o conjunto de testes que uma implementação precisa passar para se declarar
**compatível** com a spec. É o contrato executável: cada teste está amarrado a um
requisito da especificação via `@Assertion(id, section, strategy)`.

Peculiaridade estrutural: **os testes do TCK vivem em `src/main/java`**, não em
`src/test/java`. Motivo: eles são **compilados e empacotados** para que os
implementadores os executem contra a implementação deles. Só os testes unitários do
próprio framework interno do TCK ficam em `src/test/java`.

## As anotações do framework de teste

| Anotação | Nível | Efeito |
| --- | --- | --- |
| `@Standalone` | classe | Testes estruturais por reflexão; **não precisam de container**. Adiciona só a `AssertionExtension`. |
| `@Deployed` | classe | Testes de integração **Arquillian**; precisam de um container CDI completo (weld-embedded no CI). Adiciona `ArquillianExtension` + `AssertionExtension`. |
| `@Assertion(id, section, strategy)` | método | Meta-anotação que embute `@Test` e mapeia o teste ao requisito da spec (ex.: `id = "AGENTICAI-ORCHESTRATION-BHV-002"`). |
| `@RequiresImplementation` | método/classe | Pula o teste quando **não** há implementação compatível presente. |
| `@RequiresNoImplementation` | método/classe | Pula o teste quando **há** implementação — usado para asserções de baseline "CDI puro" (só trigger). |

## A detecção de implementação — o truque elegante

Como o TCK sabe se está rodando sobre uma implementação compatível (Payara) ou
sobre CDI puro (Weld sem engine)? A `ImplementationPresentCondition` (JUnit 5
`ExecutionCondition`) verifica **em runtime, dentro do container**:

```java
!CDI.current().getBeanManager().getContexts(WorkflowScoped.class).isEmpty()
```

**Toda implementação compatível registra um `Context` para `@WorkflowScoped`; CDI
puro não.** Portanto a presença desse contexto é o fingerprint da implementação —
sem precisar de system property, flag de JVM ou configuração do vendor.

Detalhe sutil: no Arquillian as conditions são avaliadas **duas vezes** — no JVM
cliente (fora do container) e dentro do container. Fora do container não dá para
saber; a condition então **deixa o teste habilitado e adia** a decisão real para a
avaliação in-container (retorna `null` no detector e responde "enabled" com a razão
"deferring").

Isso substituiu o antigo `@Disabled` no `AgentSmokeTest`: em vez de um teste
permanentemente desligado, `fullLifecycleRequiresCompatibleImplementation` roda
automaticamente quando uma implementação está presente e é pulado (com razão clara)
quando não está.

## Infraestrutura de teste (para implementadores)

Duas classes `@ApplicationScoped` que não são testes, mas ferramentas:

- **`LargeLanguageModelStub`** — implementa `LargeLanguageModel` com respostas
  roteirizadas: o teste chama `enqueueResponse("...")` antes de disparar o workflow,
  e o stub devolve as respostas na ordem, registrando cada chamada para asserção.
  `reset()` limpa entre testes. É também a prova de que o **LLM da aplicação vence o
  default do runtime** (o Payara auto-vela seu LLM default quando a aplicação fornece
  um — capítulo 5).
- **`ExecutionTraceRecorder`** — registra as fases executadas
  (`TRIGGER`, `DECISION`, `ACTION`, `OUTCOME`, `HANDLE_EXCEPTION`) e permite
  assertivas de ordem: `trace.assertOrder(TRIGGER, DECISION, ACTION, OUTCOME)`.
  ⚠️ Pegadinha conhecida: em testes `@Deployed`, `@BeforeEach` não roda entre os
  métodos como se espera — use `trace.reset()` inline no início do teste.

## O que o TCK cobre (mapa dos pacotes)

- `core/agent` — estrutura das anotações `@Agent`, contrato do
  `LargeLanguageModel`, `LLMException` (standalone/reflexão).
- `core/lifecycle` — estrutura de `@Trigger`, `@Decision`, `@Action`, `@Outcome`,
  `@HandleException`.
- `core/cdi` — metadados CDI do agente e do `@WorkflowScoped`.
- `core/behavior` — os testes comportamentais deployados, cada um com seu conjunto
  de agentes de fixture:
  - `orchestration` — topologias: minimalista, linear, intercalada, branching,
    outcome-only, anchored;
  - `termination` — os três padrões de terminação de decisão (boolean, `Result`,
    objeto/null);
  - `datapropagation` — propagação por tipo entre fases;
  - `phaseordering` — `@Priority`/`order`/ordem de declaração;
  - `errorhandling` — recuperação, propagação, hierarquia de handlers,
    guarda anti-recursão, ausência de handler;
  - `cdi` — interceptors, injeção por construtor, callbacks de ciclo de vida,
    escopo default, agentes singleton;
  - `voidphases`, `topologyflex`, `llm` — fases void, fases opcionais, contrato LLM
    em workflow real.
- `framework/signature` — testes de assinatura da API (compatibilidade binária).

## Exemplos concretos

Para dar cara a cada bucket, quatro amostras reais do TCK — uma por "sabor" de teste.

### Standalone / reflexão (`core/agent`)

Verifica a **forma** da anotação sem subir container. Barato, roda em qualquer JVM.

```java
@Standalone
public class AgentAnnotationTests {

    @Assertion(id = "AGENTICAI-AGENT-003",
               strategy = "Verify @Agent annotation targets TYPE elements")
    public void testAgentAnnotationTarget() {
        Target target = Agent.class.getAnnotation(Target.class);
        assertNotNull(target, "@Agent must have @Target annotation");
        ElementType[] targets = target.value();
        assertEquals(1, targets.length);
        assertEquals(ElementType.TYPE, targets[0]);
    }
}
```

### Orquestração (`core/behavior/orchestration`)

O clássico: dispara um evento, checa a **sequência de fases** registrada pelo `ExecutionTraceRecorder`. O `AnchoredAgent` prova que a ordem de execução vem da **ordem de declaração no fonte**, não da posição do `@Trigger`/`@Outcome`.

```java
@RequiresImplementation
@Assertion(id = "AGENTICAI-ORCHESTRATION-BHV-002",
           strategy = "@Decision and @Action execute in source-file declaration order; AnchoredAgent "
                    + "declares @Action BEFORE @Decision so the impl must invoke act() before decide()")
public void methodsExecuteInDeclarationOrder() {
    trace.reset();
    anchoredEvents.fire(new AnchoredEvent("test"));
    assertThat(trace.phases())
            .containsExactly(Phase.TRIGGER, Phase.ACTION, Phase.DECISION, Phase.OUTCOME);
}
```

### Terminação (`core/behavior/termination`)

Os três padrões de terminação de `@Decision` — cada um vira um teste com **mesma forma** e mesma asserção (`TRIGGER, DECISION` — o pipeline para aí):

```java
@RequiresImplementation
@Assertion(id = "AGENTICAI-TERM-004",
           strategy = "Boolean false from @Decision halts all downstream phases")
public void booleanFalseTerminatesWorkflow() {
    trace.reset();
    booleanEvents.fire(new BooleanTerminationEvent("x"));
    assertThat(trace.phases()).containsExactly(Phase.TRIGGER, Phase.DECISION);
}
// idem para Result(success=false) e para retorno de objeto null
```

### Propagação de dados (`core/behavior/datapropagation`)

Checa que o valor retornado por uma fase chega como **parâmetro tipado** na próxima. O `trace.entries()` guarda os argumentos recebidos por cada método:

```java
@RequiresImplementation
@Assertion(id = "AGENTICAI-DATA-002",
           strategy = "TriggerOutput returned by @Trigger is injectable as a parameter in @Decision")
public void triggerOutputIsInjectableInDecision() {
    llm.enqueueResponse("ok");
    events.fire(new DataPropagationEvent("input"));
    assertThat(trace.entries().get(1).args()[1]).isInstanceOf(TriggerOutput.class);
}
```

### Contrato do LLM (`core/behavior/llm`)

Cobre o **contrato de erro** do `LargeLanguageModel` — validação de argumentos, mapeamento de placeholders `{}`, serialização via JSON-B, e a garantia de **isolamento por workflow** (estado conversacional não vaza entre execuções). Um exemplo típico:

```java
@Assertion(strategy = "more parameters than placeholders must throw IllegalArgumentException")
public void tooManyParamsFailsFast() {
    assertThrows(IllegalArgumentException.class,
        () -> llm.chat("Hello {}", "world", "extra"));
}
```

## Comandos de build

```bash
# Build completo (CI) — ativa o container Arquillian weld-embedded
mvn clean install -Pweld-embedded

# Só o TCK e módulos upstream
mvn --projects tck --also-make verify

# Uma classe standalone específica (Failsafe, testes em src/main/java)
mvn -pl tck verify -Dgroups=standalone -Dit.test=AgentAnnotationTests

# Uma classe deployed (exige o profile do container)
mvn -pl tck verify -Pweld-embedded -Dit.test=AgentSmokeTest

# Gerar arquivos de assinatura da API
mvn -pl tck verify -Psignature-generation
```

Sem o profile `weld-embedded`, os testes `@Deployed` são **excluídos por default**
na configuração Maven do TCK.

---

## Quiz — Capítulo 4

**1.** Por que os testes do TCK ficam em `src/main/java` e não em `src/test/java`?

<details><summary>Ver resposta</summary>

Porque eles são o **produto** do módulo: são compilados e empacotados num artefato
que os **implementadores** baixam e executam contra a implementação deles. Testes em
`src/test/java` não são empacotados no JAR. Apenas os testes unitários do framework
interno do TCK ficam em `src/test/java`.
</details>

**2.** Como a `ImplementationPresentCondition` detecta que uma implementação
compatível está presente, sem nenhuma configuração do vendor?

<details><summary>Ver resposta</summary>

Ela verifica, dentro do container, se existe um **`Context` CDI registrado para o
escopo `@WorkflowScoped`**:
`CDI.current().getBeanManager().getContexts(WorkflowScoped.class)`. Toda
implementação compatível registra esse contexto (é requisito da spec); CDI puro
não registra. É um fingerprint em runtime — sem system property nem flag de JVM.
</details>

**3.** O que acontece quando a condition é avaliada no JVM **cliente** do
Arquillian, fora do container?

<details><summary>Ver resposta</summary>

Fora do container não há como determinar a presença da implementação (o
`CDI.current()` falha), então a condition retorna **enabled** e **adia** a decisão
para a segunda avaliação, que ocorre dentro do container, onde a detecção é
confiável.
</details>

**4.** Qual é a diferença de propósito entre `@RequiresImplementation` e
`@RequiresNoImplementation`?

<details><summary>Ver resposta</summary>

`@RequiresImplementation` protege testes que **precisam do engine** (fases
`@Decision`/`@Action`/`@Outcome` despachadas) — pulados em CDI puro.
`@RequiresNoImplementation` protege os testes de **baseline CDI puro** (ex.: o
trigger é invocável só com CDI) — pulados quando a implementação está presente,
pois a asserção comportamental completa da implementação os substitui.
</details>

**5.** Para que servem `LargeLanguageModelStub.enqueueResponse(...)` e o
`ExecutionTraceRecorder.assertOrder(...)` num teste comportamental típico?

<details><summary>Ver resposta</summary>

`enqueueResponse` roteiriza as respostas do LLM (o teste controla exatamente o que o
"modelo" responde, sem chamar serviço real), e o stub registra as chamadas para
asserção. `assertOrder(TRIGGER, DECISION, ACTION, OUTCOME)` verifica que o engine
executou as fases na ordem exigida pela spec. Juntos permitem testar a orquestração
de forma determinística.
</details>

---

➡️ Próximo: [Capítulo 5 — Implementação Payara: a extensão CDI](05-implementacao-extensao-cdi.md)
