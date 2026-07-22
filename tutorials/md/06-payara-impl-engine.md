# Chapter 6 — Payara implementation: WorkflowEngine and the workflow scope

## `WorkflowEngine.execute` — the backbone

A single `execute(agentMetadata, triggerEvent)` call runs the complete workflow for
one event. The flow, with the details that matter:

```java
workflowScopeManager.activate();                    // 1. activates @WorkflowScoped on the thread
WorkflowContext ctx = new WorkflowContext();
ctx.add(triggerEvent);                              // 2. seeds the event into the context
try {
    agentInstance = resolveBean(agentClass);        // 3. resolves the agent bean
    llm = resolveBean(LargeLanguageModel.class);    //    and the LLM (one per workflow)

    Object r = invokePhase(triggerMethod, ...);     // 4. @Trigger
    ctx.add(r);                                     //    return enters the context

    for (PhaseMethod phase : sortedPhases) {        // 5. pre-sorted @Decision/@Action
        Object result = invokePhase(phase, ...);
        if (phase is DECISION) {
            if (!shouldContinue(result)) return;    //    early termination
            addDecisionResultToContext(result);     //    publishes Result.details()
        } else {
            ctx.add(result);
        }
    }

    invokePhase(outcomeMethod, ...);                // 6. @Outcome (if present)
} catch (Exception e) {
    // 7. dispatch to @HandleException (see below)
} finally {
    workflowScopeManager.deactivate();              // 8. ALWAYS destroys the context
}
```

Points worth highlighting:

- **The workflow runs on the caller's thread** — `Event.fire()` is synchronous, so
  whoever made the REST POST waits for the workflow to finish (that is why the
  samples can return the LLM's answer in the same HTTP response).
- The context is destroyed **always** (`finally`) — success, early termination or
  failure.
- The LLM is resolved **once per execution** — since the bean is `@Dependent`,
  each workflow gets its own instance, and that is where the conversational
  isolation required by the spec comes from.

## Termination semantics (`shouldContinue`)

```java
return switch (result) {
    case null      -> false;   // null object ⇒ stop
    case Boolean b -> b;       // false ⇒ stop
    case Result r  -> r.success();
    default        -> true;    // any non-null object ⇒ proceed
};
```

And the decision data publication: for a `Result`, the `details()` enters the
context; a `Boolean` carries no data; any other object enters as-is.

## `WorkflowContext` — type-based data propagation

A simple list of the produced values, in production order. `add(null)` is ignored
(void phases contribute nothing). `getByType(Class)` walks **from newest to
oldest** — if two phases produced the same type, the next phase receives the
**freshest** value.

## `ParameterResolver` — the parameter resolution order

For each parameter of a phase method, in this order:

1. A type assignable to `LargeLanguageModel` → the workflow's LLM instance;
2. (only for `@HandleException`) the in-flight exception, if the parameter type
   matches;
3. A value from the `WorkflowContext` by type (most recent first);
4. A CDI bean resolved via the `BeanManager`;
5. Nothing found → `null`.

This is what enables signatures like
`@Action void handle(Fraud fraud, BankTransaction tx, AuditService audit)` — two
objects coming from earlier phases plus a CDI bean, all resolved transparently.

## Exception dispatch — the less obvious path

When any phase throws:

1. The exception is **unwrapped** from the reflective
   `InvocationTargetException` (the handler sees the original cause, not the
   wrapper).
2. `dispatchException` looks, among the handlers whose exception parameter is
   compatible (`isInstance`), for the **most specific** (most derived) type.
3. **No handler** → the exception is rethrown to the container (a
   RuntimeException directly; a checked one wrapped in a RuntimeException).
4. **The handler throws** → that exception propagates to the container — **no
   recursive handling** (a handler never handles another handler's failure).
5. **The handler returns normally** → recovery. And here is the fine detail: the
   engine then runs the `@Outcome` as the **recovery's closure phase** — but
   **only if the `@Outcome` was not the phase that threw the original exception**
   (the `outcomeAttempted` flag prevents re-invoking an outcome that just failed).

## Bean Validation on phases

Before invoking any phase, the engine validates the resolved arguments with the
`ExecutableValidator` (when available): constraints like `@Valid` and `@NotNull`
on phase method parameters. A violation ⇒ `ConstraintViolationException`, which is
routed to the `@HandleException` methods **like any other failure**.

## `WorkflowScopeContext` — `@WorkflowScoped` from the inside

Implements `AlterableContext` with **`ThreadLocal`** storage:

```java
private static final ThreadLocal<Map<Contextual<?>, BeanInstance<?>>> STORE = ...;
```

- `activate()` puts an empty map on the thread → context active;
- `get(contextual, creationalContext)` creates the bean instance on first access
  and memoizes it (one instance per bean per workflow);
- `deactivate()` **destroys every bean** (firing `@PreDestroy`) and removes the
  `ThreadLocal`;
- access with an inactive context ⇒ `ContextNotActiveException`.

Since each workflow runs on the `Event.fire()` thread, the `ThreadLocal` provides
**isolation between concurrent workflows** for free: two simultaneous REST requests
activate independent contexts on different threads.

It is the **registration of this `Context`** that the TCK uses as the fingerprint
to detect a compatible implementation (chapter 4) — a neat full-circle moment
between spec and implementation.

---

## Quiz — Chapter 6

**1.** Why can the REST sample return the LLM's answer in the **same** HTTP
response that triggered the agent?

<details><summary>Show answer</summary>

Because `Event.fire(...)` is **synchronous** and the `WorkflowEngine` runs the
whole workflow **on the caller's thread**. When `fire` returns, every phase
(including the LLM call in the `@Action`) has already run, and the REST resource
can read the result (from the `AnswerStore`/`TutorialStore`) and return it in the
same request.
</details>

**2.** A `@Decision` returns a `Plan` object and, later on, an `@Action` also
returns a `Plan`. An `@Outcome` declares a `Plan` parameter. Which instance does it
receive, and why?

<details><summary>Show answer</summary>

The **`@Action`'s** — the most recent one. `WorkflowContext.getByType` walks the
produced values **from newest to oldest**, guaranteeing later phases always see the
freshest value when several produced objects share a type.
</details>

**3.** List the precedence order the `ParameterResolver` uses to fill each
parameter of a phase method.

<details><summary>Show answer</summary>

1. `LargeLanguageModel` (the workflow's instance); 2. the in-flight exception
(only for `@HandleException`); 3. a `WorkflowContext` value by type (most recent
first); 4. a CDI bean via the `BeanManager`; 5. `null` if nothing matches.
</details>

**4.** An `@Action` throws an `LLMException`; a `@HandleException(LLMException)`
logs and returns normally. The agent has an `@Outcome`. Describe what the engine
does, and what would change if the exception had been thrown by the `@Outcome`
itself.

<details><summary>Show answer</summary>

With the failure in the `@Action`: the engine selects the most specific handler, it
returns normally (recovery) and the engine then **runs the `@Outcome`** as the
recovery's closure phase. If the thrower had been the `@Outcome` itself, the
`outcomeAttempted` flag would prevent **re-invoking** the outcome after recovery —
the handler runs, but the outcome is not attempted again.
</details>

**5.** How does the `WorkflowScopeContext` guarantee isolation between two
workflows running at the same time, and what happens to the `@WorkflowScoped` beans
at the end?

<details><summary>Show answer</summary>

Storage is a **`ThreadLocal`** map of bean→instance, and each workflow runs on its
own thread (the `Event.fire` one), so the contexts never see each other. In the
engine's `finally`, `deactivate()` **destroys every instance** (invoking
`@PreDestroy`) and removes the `ThreadLocal` — the context dies with the workflow,
on success or failure.
</details>

**6.** A `@NotNull` parameter of a `@Decision` arrives null. What happens, and
where can it be handled?

<details><summary>Show answer</summary>

The engine validates the arguments with the `ExecutableValidator` **before**
invoking the phase; the violation becomes a `ConstraintViolationException`, which
is routed to the agent's `@HandleException` methods like any other phase exception
(the handler can recover or let it propagate). If no Bean Validation provider is on
the classpath, validation is simply skipped.
</details>

---

➡️ Next: [Chapter 7 — LLM backends and configuration](07-llm-backends.md)
