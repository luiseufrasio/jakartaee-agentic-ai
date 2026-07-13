# Chapter 5 — Payara implementation: the CDI extension

From here on we leave the spec and enter the Payara runtime
(`fish.payara.ai.agent.*`, the `agentic-ai-core` module). The entry gate is the
**portable CDI extension** `AgenticAIExtension` — it turns `@Agent` classes into
executable workflows using only standard CDI mechanisms (the extension SPI).

## Boot pipeline overview

```
Application deployment
  │
  ├─ ProcessAnnotatedType (per class) ──► processAgent()
  │     • applies the default @WorkflowScoped when no scope is present
  │     • REMOVES @Observes from the @Trigger method
  │     • collects the agent class
  │
  ├─ ProcessManagedBean (per bean) ──► watchForLlm()
  │     • flags whether the application supplies its own LargeLanguageModel
  │
  └─ AfterBeanDiscovery ──► afterBeanDiscovery()
        • registers the WorkflowScopeContext (the @WorkflowScoped Context)
        • creates the WorkflowEngine
        • per agent: validates metadata + registers a SYNTHETIC OBSERVER
        • if the app brought no LLM: registers the default LLM (backend via config)
```

## `processAgent` — preparing each agent

For each type annotated with `@Agent`:

1. **Default scope.** If the class has neither `@WorkflowScoped` nor
   `@ApplicationScoped`, the extension adds `WorkflowScoped.Literal.INSTANCE` via
   `configureAnnotatedType()` — this is how the spec's "default is WorkflowScoped"
   is implemented in practice.
2. **Removing `@Observes` from the trigger.** This is the implementation's central
   trick: if the developer wrote `@Trigger void on(@Observes MyEvent e)`, CDI would
   invoke the method **directly** as a regular observer — outside the engine, with
   no active workflow context and without the following phases. The extension
   **removes the `@Observes` annotation** from the parameter, and the **synthetic
   observer** registered later becomes the single entry point. This prevents the
   trigger's **double invocation** and guarantees the workflow context wraps the
   entire run.

## `watchForLlm` — the self-vetoing default LLM

The extension observes every `ProcessManagedBean` and raises the `appProvidesLlm`
flag if any **application** bean has `LargeLanguageModel` among its types.

In `afterBeanDiscovery`, **only if the application brought no LLM of its own**, the
runtime registers its default: a `@Dependent` bean created with
`new LargeLanguageModelImpl(backend)`, where the backend comes from
`LlmBackendFactory.create(config)` (chapter 7).

Why this dance? If the runtime registered its LLM unconditionally and the
application also supplied one (the TCK stub, or a real LangChain4j-backed bean),
injecting `LargeLanguageModel` would raise an **`AmbiguousResolutionException`**.
The self-vetoing guarantees: **the application's LLM always wins; the runtime's is
only a fallback**. Note that synthetic beans do not go through
`ProcessManagedBean`, so the default LLM itself cannot fool the detection.

## `afterBeanDiscovery` — context, engine and synthetic observers

```java
afterBeanDiscovery.addContext(workflowScopeContext);            // registers @WorkflowScoped
// ...
afterBeanDiscovery.addObserverMethod()
        .beanClass(agentClass)
        .observedType(eventType)                                 // the @Trigger's event type
        .notifyWith(ctx -> workflowEngine.execute(agentMetadata, ctx.getEvent()));
```

For each agent, **one synthetic observer** is registered for the trigger's event
type. When someone calls `event.fire(new Question(...))`, this observer receives
it — and delegates to `WorkflowEngine.execute(...)`, which runs the whole workflow.
Agents whose trigger declares no event type are skipped (reserved for future
programmatic triggering).

The **event type** is extracted from the trigger with this precedence: a parameter
explicitly annotated with `@Observes` (even though the annotation will be removed,
it declares the intent); otherwise, the first parameter that is **not** a
`LargeLanguageModel`.

## `buildMetadata` — deploy-time validation

Each agent's metadata (`AgentMetadata`) is built via reflection and **validated at
deployment** — the philosophy is fail-fast: a structural error kills the deployment
with a `DefinitionException` instead of blowing up at runtime. Cases:

| Violation | Result |
| --- | --- |
| More than one `@Trigger` | `DefinitionException` |
| No `@Trigger` at all | `DefinitionException` |
| More than one `@Outcome` | `DefinitionException` |
| A `@WorkflowScoped` agent with `@Observes` outside the `@Trigger` | `DefinitionException` |
| Mixing phases with and without explicit ordering | `DefinitionException` ("Inconsistent order") |

After validation:

- **Phases sorted:** if any phase carries explicit ordering (`@Priority` or
  `order != 0` — encapsulated in `PhaseMethod.isExplicitlyOrdered()`), sort by
  `sortKey`; otherwise sort by **source declaration order**, obtained via
  `ClassMethodOrder`, which reads the **methods table of the `.class` file** —
  more reliable than `getDeclaredMethods()`, whose order the JVM does not
  guarantee.
- **Handlers sorted most-specific-first** (comparison via `isAssignableFrom`
  between the exception types of the parameters), preparing the engine's handler
  selection.

## Optional Bean Validation

The extension tries to build an `ExecutableValidator`
(`Validation.buildDefaultValidatorFactory()`); if no Bean Validation provider is on
the classpath, it returns `null` and the engine simply **skips** parameter
validation — graceful integration, not mandatory.

---

## Quiz — Chapter 5

**1.** Why does the extension **remove** the `@Observes` from the `@Trigger` method
during `ProcessAnnotatedType`?

<details><summary>Show answer</summary>

If the `@Observes` stayed, the CDI container would invoke the trigger method
**directly** as a regular observer — without going through the `WorkflowEngine`,
without an active `@WorkflowScoped` context and without the following phases; and
since the engine also registers a synthetic observer for the same event, the
trigger would be invoked **twice**. By removing the annotation, the synthetic
observer becomes the workflow's **single entry point**.
</details>

**2.** The application deploys with its own bean implementing `LargeLanguageModel`
(e.g. the TCK stub). What does the Payara runtime do with its default LLM, and what
would happen without that mechanism?

<details><summary>Show answer</summary>

The runtime **does not register** its default LLM (the `appProvidesLlm` flag was
raised by `ProcessManagedBean`). Without this "self-vetoing", there would be two
eligible beans for the same injection point and the deployment would fail with an
**`AmbiguousResolutionException`**. Practical rule: the application's LLM always
wins; the runtime's is a fallback.
</details>

**3.** Name three agent structures that kill the deployment with a
`DefinitionException`.

<details><summary>Show answer</summary>

Any three of these: (a) two `@Trigger` methods; (b) no `@Trigger`;
(c) two `@Outcome` methods; (d) a `@WorkflowScoped` agent with an `@Observes`
method outside the trigger; (e) mixing `@Decision`/`@Action` with explicit and
implicit ordering ("Inconsistent order").
</details>

**4.** When no phase declares `@Priority`/`order`, how does the implementation get
the methods' declaration order, given that `getDeclaredMethods()` guarantees no
order?

<details><summary>Show answer</summary>

Via `ClassMethodOrder`, which reads the **methods table straight from the `.class`
file's bytecode**, where methods appear in the order they were declared in the
source. This implements the spec requirement (declaration order as the fallback)
deterministically.
</details>

**5.** What exactly does the synthetic observer registered in `afterBeanDiscovery`
do when the trigger's event is fired?

<details><summary>Show answer</summary>

It calls `workflowEngine.execute(agentMetadata, eventContext.getEvent())` — that
is, it hands the event to the engine, which activates the `@WorkflowScoped`
context, resolves the agent bean and the LLM, and runs all the phases in order
(trigger → decisions/actions → outcome), with exception dispatching. The observer
is the link between the CDI world (`Event.fire`) and the orchestration engine.
</details>

---

➡️ Next: [Chapter 6 — WorkflowEngine and scope](06-payara-impl-engine.md)
