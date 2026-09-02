# Capítulo 6 — Implementação Payara: WorkflowEngine e o escopo de workflow

## `WorkflowEngine.execute` — a espinha dorsal

Uma única chamada `execute(agentMetadata, triggerEvent)` roda o workflow completo
para um evento. O fluxo, com os detalhes que importam:

```java
workflowScopeManager.activate();                    // 1. ativa @WorkflowScoped na thread
WorkflowContext ctx = new WorkflowContext();
ctx.add(triggerEvent);                              // 2. semeia o evento no contexto
try {
    agentInstance = resolveBean(agentClass);        // 3. resolve o bean do agente
    llm = resolveBean(LargeLanguageModel.class);    //    e o LLM (um por workflow)

    Object r = invokePhase(triggerMethod, ...);     // 4. @Trigger
    ctx.add(r);                                     //    retorno entra no contexto

    for (PhaseMethod phase : sortedPhases) {        // 5. @Decision/@Action pré-ordenadas
        Object result = invokePhase(phase, ...);
        if (phase é DECISION) {
            if (!shouldContinue(result)) return;    //    terminação antecipada
            addDecisionResultToContext(result);     //    publica Result.details()
        } else {
            ctx.add(result);
        }
    }

    invokePhase(outcomeMethod, ...);                // 6. @Outcome (se existir)
} catch (Exception e) {
    // 7. despacho para @HandleException (ver abaixo)
} finally {
    workflowScopeManager.deactivate();              // 8. SEMPRE destrói o contexto
}
```

Pontos a destacar em palestra:

- **O workflow roda na thread do chamador** — `Event.fire()` é síncrono, então quem
  fez o POST REST espera o workflow terminar (é por isso que os samples conseguem
  devolver a resposta do LLM na mesma requisição HTTP).
- O contexto é destruído **sempre** (`finally`) — sucesso, terminação antecipada ou
  falha.
- O LLM é resolvido **uma vez por execução** — como o bean é `@Dependent`, cada
  workflow ganha sua instância, e é daí que vem o isolamento conversacional exigido
  pela spec.

## Semântica de terminação (`shouldContinue`)

```java
return switch (result) {
    case null      -> false;   // objeto null ⇒ para
    case Boolean b -> b;       // false ⇒ para
    case Result r  -> r.success();
    default        -> true;    // qualquer objeto não-nulo ⇒ segue
};
```

E a publicação do dado da decisão: para `Result`, entra o `details()` no contexto;
`Boolean` não carrega dado; outro objeto entra como está.

## `WorkflowContext` — propagação de dados por tipo

Uma lista simples dos valores produzidos, em ordem de produção. `add(null)` é
ignorado (fases void não contribuem). `getByType(Class)` percorre **do mais novo
para o mais antigo** — se duas fases produziram o mesmo tipo, a fase seguinte
recebe o valor **mais fresco**.

## `ParameterResolver` — a ordem de resolução dos parâmetros

Para cada parâmetro de um método de fase, nesta ordem:

1. Tipo atribuível a `LargeLanguageModel` → a instância LLM do workflow;
2. (só em `@HandleException`) a exceção em voo, se o tipo do parâmetro casar;
3. Valor do `WorkflowContext` por tipo (mais recente primeiro);
4. Bean CDI resolvido via `BeanManager`;
5. Nada encontrado → `null`.

É isso que permite assinaturas como
`@Action void handle(Fraud fraud, BankTransaction tx, AuditService audit)` — dois
objetos vindos de fases anteriores e um bean CDI, resolvidos de forma transparente.

## Despacho de exceções — o caminho menos óbvio

Quando qualquer fase lança:

1. A exceção é **desembrulhada** do `InvocationTargetException` reflexivo (o
   handler vê a causa original, não o wrapper).
2. `dispatchException` procura, entre os handlers cujo parâmetro de exceção é
   compatível (`isInstance`), o de tipo **mais específico** (mais derivado).
3. **Sem handler** → a exceção é relançada ao container (RuntimeException direto;
   checked embrulhada em RuntimeException).
4. **Handler lança** → essa exceção propaga ao container — **sem tratamento
   recursivo** (um handler não trata a falha de outro handler).
5. **Handler retorna normalmente** → recuperação. E aqui o detalhe fino: o engine
   então executa o `@Outcome` como **fase de fechamento da recuperação** — mas
   **só se o `@Outcome` não foi a fase que lançou a exceção original** (a flag
   `outcomeAttempted` evita re-invocar um outcome que acabou de falhar).

## Bean Validation nas fases

Antes de invocar qualquer fase, o engine valida os argumentos resolvidos com o
`ExecutableValidator` (se disponível): constraints como `@Valid` e `@NotNull` nos
parâmetros dos métodos de fase. Violação ⇒ `ConstraintViolationException`, que é
roteada aos `@HandleException` **como qualquer outra falha**.

## `WorkflowScopeContext` — o escopo `@WorkflowScoped` por dentro

Implementa `AlterableContext` com armazenamento em **`ThreadLocal`**:

```java
private static final ThreadLocal<Map<Contextual<?>, BeanInstance<?>>> STORE = ...;
```

- `activate()` põe um mapa vazio na thread → contexto ativo;
- `get(contextual, creationalContext)` cria a instância do bean na primeira
  requisição e a memoriza (uma instância por bean por workflow);
- `deactivate()` **destrói cada bean** (disparando `@PreDestroy`) e remove o
  `ThreadLocal`;
- acesso com contexto inativo ⇒ `ContextNotActiveException`.

Como cada workflow roda na thread do `Event.fire()`, o `ThreadLocal` dá **isolamento
entre workflows concorrentes** de graça: duas requisições REST simultâneas ativam
contextos independentes em threads diferentes.

Registrar este `Context` é também o que torna a Payara uma implementação
*compatível* no sentido da spec — embora o TCK não o sonde: no baseline Jakarta EE
10, o `BeanManager` do CDI 4.0 não consegue enumerar os contextos registrados, então
o TCK pede que a implementação se declare pela system property
`jakarta.ai.agent.tck.implementation.present` (capítulo 4).

---

## Quiz — Capítulo 6

**1.** Por que o sample de REST consegue devolver a resposta do LLM na **mesma**
resposta HTTP que disparou o agente?

<details><summary>Ver resposta</summary>

Porque `Event.fire(...)` é **síncrono** e o `WorkflowEngine` executa o workflow
inteiro **na thread do chamador**. Quando o `fire` retorna, todas as fases
(inclusive a chamada ao LLM na `@Action`) já rodaram, e o recurso REST pode ler o
resultado (do `AnswerStore`/`TutorialStore`) e devolvê-lo na mesma requisição.
</details>

**2.** Uma `@Decision` retorna um objeto `Plan` e, mais adiante, uma `@Action`
também retorna um `Plan`. Um `@Outcome` declara um parâmetro `Plan`. Qual instância
ele recebe e por quê?

<details><summary>Ver resposta</summary>

A da **`@Action`** — a mais recente. O `WorkflowContext.getByType` percorre os
valores produzidos **do mais novo para o mais antigo**, garantindo que fases
posteriores vejam sempre o valor mais fresco quando vários objetos compartilham o
tipo.
</details>

**3.** Liste a ordem de precedência que o `ParameterResolver` usa para preencher
cada parâmetro de um método de fase.

<details><summary>Ver resposta</summary>

1. `LargeLanguageModel` (a instância do workflow); 2. a exceção em voo (apenas para
`@HandleException`); 3. valor do `WorkflowContext` por tipo (mais recente
primeiro); 4. bean CDI via `BeanManager`; 5. `null` se nada casar.
</details>

**4.** Uma `@Action` lança `LLMException`; um `@HandleException(LLMException)`
loga e retorna normalmente. O agente tem `@Outcome`. Descreva o que o engine faz, e
o que mudaria se a exceção tivesse sido lançada pelo próprio `@Outcome`.

<details><summary>Ver resposta</summary>

Com a falha na `@Action`: o engine seleciona o handler mais específico, ele retorna
normalmente (recuperação) e o engine então **executa o `@Outcome`** como fase de
fechamento da recuperação. Se quem lançou fosse o próprio `@Outcome`, a flag
`outcomeAttempted` impediria a **re-invocação** do outcome após a recuperação — o
handler roda, mas o outcome não é tentado de novo.
</details>

**5.** Como o `WorkflowScopeContext` garante isolamento entre dois workflows
executando ao mesmo tempo, e o que acontece com os beans `@WorkflowScoped` ao final?

<details><summary>Ver resposta</summary>

O armazenamento é um **`ThreadLocal`** de mapa bean→instância, e cada workflow roda
na sua própria thread (a do `Event.fire`), então os contextos nunca se enxergam. No
`finally` do engine, `deactivate()` **destrói cada instância** (invocando
`@PreDestroy`) e remove o `ThreadLocal` — o contexto morre com o workflow, em
sucesso ou falha.
</details>

**6.** Um parâmetro `@NotNull` de uma `@Decision` chega nulo. O que acontece e onde
isso pode ser tratado?

<details><summary>Ver resposta</summary>

O engine valida os argumentos com o `ExecutableValidator` **antes** de invocar a
fase; a violação vira `ConstraintViolationException`, que é roteada aos métodos
`@HandleException` do agente como qualquer outra exceção de fase (handler pode
recuperar ou deixar propagar). Se não houver provider de Bean Validation no
classpath, a validação é simplesmente pulada.
</details>

---

➡️ Próximo: [Capítulo 7 — Backends LLM e configuração](07-backends-llm.md)
