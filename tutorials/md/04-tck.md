# Chapter 4 — The TCK (Technology Compatibility Kit)

## What it is for

The TCK is the test suite an implementation must pass to claim it is
**compatible** with the spec. It is the executable contract: every test is tied to
a specification requirement via `@Assertion(id, section, strategy)`.

A structural peculiarity: **the TCK tests live in `src/main/java`**, not in
`src/test/java`. Reason: they are **compiled and packaged** so implementors can run
them against their own implementation. Only the TCK's internal framework unit tests
live in `src/test/java`.

## The test-framework annotations

| Annotation | Level | Effect |
| --- | --- | --- |
| `@Standalone` | class | Reflection-based structural tests; **no container needed**. Adds only the `AssertionExtension`. |
| `@Deployed` | class | **Arquillian** integration tests; require a full CDI container (weld-embedded in CI). Adds `ArquillianExtension` + `AssertionExtension`. |
| `@Assertion(id, section, strategy)` | method | Meta-annotation embedding `@Test` that maps the test to a spec requirement (e.g. `id = "AGENTICAI-ORCHESTRATION-BHV-002"`). |
| `@RequiresImplementation` | method/class | Skips the test when **no** compatible implementation is present. |
| `@RequiresNoImplementation` | method/class | Skips the test when an implementation **is** present — used for "plain CDI" baseline assertions (trigger only). |

## Implementation detection — the opt-in switch

How does the TCK know whether it is running on a compatible implementation
(Payara) or on plain CDI (Weld without the engine)? The
`ImplementationPresentCondition` (a JUnit 5 `ExecutionCondition`) checks **at
runtime, inside the container**, a single system property:

```java
public static final String IMPLEMENTATION_PRESENT_PROPERTY =
        "jakarta.ai.agent.tck.implementation.present";

// ...
return Boolean.getBoolean(IMPLEMENTATION_PRESENT_PROPERTY);
```

An implementation running the TCK sets it to `true` (typically via
`-Djakarta.ai.agent.tck.implementation.present=true` on the Surefire/Failsafe
`argLine`). **Unset means "no compatible implementation"** — exactly what a
plain-CDI (Weld/OpenWebBeans) run of the baseline assertions needs, so the default
requires no configuration at all.

> **Why a property and not a container probe?** An earlier version fingerprinted
> the implementation by asking the `BeanManager` whether a `Context` was
> registered for `@WorkflowScoped` — every compatible implementation registers
> one, plain CDI does not. Elegant, but **not portable on the Jakarta EE 10
> baseline**: CDI 4.0's `BeanManager` exposes no way to enumerate registered
> contexts. The explicit opt-in works on every CDI 4.0 container.

A subtle detail: with Arquillian, conditions are evaluated **twice** — on the
client JVM (outside the container) and inside the container. Outside the container
there is no way to know; the condition then **leaves the test enabled and defers**
the real decision to the in-container evaluation (the detector returns `null` and
the condition answers "enabled" with a "deferring" reason).

This replaced the old `@Disabled` on `AgentSmokeTest`: instead of a permanently
switched-off test, `fullLifecycleRequiresCompatibleImplementation` runs
automatically when an implementation is present and is skipped (with a clear
reason) when it is not.

## Test infrastructure (for implementors)

Two `@ApplicationScoped` classes that are not tests, but tools:

- **`LargeLanguageModelStub`** — implements `LargeLanguageModel` with scripted
  responses: the test calls `enqueueResponse("...")` before firing the workflow,
  and the stub returns the responses in order, recording every call for
  assertions. `reset()` clears it between tests. It is also the proof that the
  **application's LLM beats the runtime's default** (Payara self-vetoes its
  default LLM when the application provides one — chapter 5).
- **`ExecutionTraceRecorder`** — records the executed phases
  (`TRIGGER`, `DECISION`, `ACTION`, `OUTCOME`, `HANDLE_EXCEPTION`) and enables
  ordering assertions: `trace.assertOrder(TRIGGER, DECISION, ACTION, OUTCOME)`.
  ⚠️ Known pitfall: in `@Deployed` tests, `@BeforeEach` does not run between
  methods the way you expect — call `trace.reset()` inline at the start of the
  test.

## What the TCK covers (package map)

- `core/agent` — structure of the `@Agent` annotations, the `LargeLanguageModel`
  contract, `LLMException` (standalone/reflection).
- `core/lifecycle` — structure of `@Trigger`, `@Decision`, `@Action`, `@Outcome`,
  `@HandleException`.
- `core/cdi` — CDI metadata of the agent and of `@WorkflowScoped`.
- `core/integration` — `AgentSmokeTest`: the end-to-end sanity check on a
  `GreetingAgent`.
- `core/behavior` — the deployed behavioral test classes (`OrchestrationTests`,
  `TerminationTests`, `DataPropagationTests`, `PhaseOrderingTests`,
  `HandleExceptionTests`, `CdiIntegrationTests`, `ContextInjectionTests`,
  `LlmContractTests`, `VoidPhasesTests`, `TopologyFlexTests`,
  `WorkflowScopeLifecycleTests`). Each class deploys its own fixture agents, which
  live one package deeper, under `core/behavior/agents/<topic>`:
  - `orchestration` — topologies: minimalist, linear, intermixed, branching,
    outcome-only, anchored;
  - `termination` — the three decision termination patterns (boolean, `Result`,
    object/null);
  - `datapropagation` — type-based propagation across phases;
  - `phaseordering` — `@Priority`/`order`/declaration order;
  - `errorhandling` — recovery, propagation, handler hierarchy, recursion guard,
    missing handler;
  - `cdi` — interceptors, constructor injection, lifecycle callbacks, default
    scope, singleton agents;
  - `contextinjection` — what a phase may receive from the workflow context;
  - `voidphases`, `topologyflex`, `llm` — void phases, optional phases, the LLM
    contract inside a real workflow.
- `framework/signature` — API signature tests (binary compatibility), with the
  recorded surface in
  `src/main/resources/.../signature/jakarta.ai.agent.sig_1.0`.

## Concrete examples

To put a face on each bucket, four real samples from the TCK — one per test "flavor".

### Standalone / reflection (`core/agent`)

Verifies the **shape** of the annotation without booting a container. Cheap, runs on any JVM.

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

### Orchestration (`core/behavior/orchestration`)

The classic: fire an event, check the **phase sequence** recorded by `ExecutionTraceRecorder`. The `AnchoredAgent` proves that execution order comes from **source declaration order**, not from the position of `@Trigger`/`@Outcome`.

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

### Termination (`core/behavior/termination`)

The three `@Decision` termination patterns — each one becomes a test with the **same shape** and the same assertion (`TRIGGER, DECISION` — the pipeline stops there):

```java
@RequiresImplementation
@Assertion(id = "AGENTICAI-TERM-004",
           strategy = "Boolean false from @Decision halts all downstream phases")
public void booleanFalseTerminatesWorkflow() {
    trace.reset();
    booleanEvents.fire(new BooleanTerminationEvent("x"));
    assertThat(trace.phases()).containsExactly(Phase.TRIGGER, Phase.DECISION);
}
// same for Result(success=false) and for returning a null object
```

### Data propagation (`core/behavior/datapropagation`)

Checks that the value returned by one phase arrives as a **typed parameter** in the next. `trace.entries()` stores the arguments each method received:

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

### LLM contract (`core/behavior/llm`)

Covers the `LargeLanguageModel` **error contract** — argument validation, `{}` placeholder mapping, JSON-B serialization, and the guarantee of **per-workflow isolation** (conversational state does not leak between executions). A typical example:

```java
@Assertion(id = "AGENTICAI-LLM-BHV-002",
           section = "LLM Interface, Positional Parameters",
           strategy = "more parameters than placeholders must throw IllegalArgumentException")
public void arityMoreParamsThanPlaceholdersThrows() {
    stub.reset();
    stub.enqueueResponse("ok");
    assertThatThrownBy(() -> llm.query("one {} here", "a", "b"))
            .isInstanceOf(IllegalArgumentException.class);
}
```

Note `id` is **mandatory** on `@Assertion` (`section` and `strategy` default to the
empty string): every TCK test must name the requirement it verifies.

## Build commands

```bash
# Full build (CI) — activates the weld-embedded Arquillian container
mvn clean install -Pweld-embedded

# Only the TCK and upstream modules
mvn --projects tck --also-make verify

# A specific standalone class (Failsafe, tests in src/main/java)
mvn -pl tck verify -Dgroups=standalone -Dit.test=AgentAnnotationTests

# A deployed class (requires the container profile)
mvn -pl tck verify -Pweld-embedded -Dit.test=AgentSmokeTest

# Generate the API signature files
mvn -pl tck verify -Psignature-generation
```

Without the `weld-embedded` profile, `@Deployed` tests are **excluded by default**
in the TCK's Maven configuration.

---

## Quiz — Chapter 4

**1.** Why do the TCK tests live in `src/main/java` and not in `src/test/java`?

<details><summary>Show answer</summary>

Because they are the module's **product**: they are compiled and packaged into an
artifact that **implementors** download and run against their own implementation.
Tests in `src/test/java` are not packaged into the JAR. Only the TCK's internal
framework unit tests live in `src/test/java`.
</details>

**2.** How does the `ImplementationPresentCondition` decide that a compatible
implementation is present, and why is it an explicit opt-in rather than a probe of
the container?

<details><summary>Show answer</summary>

It reads, inside the container, the system property
**`jakarta.ai.agent.tck.implementation.present`** — implementations set it to
`true` when running the TCK; unset means "plain CDI baseline", so the default needs
no configuration. It is an opt-in rather than a container probe because the earlier
approach (checking whether a `Context` was registered for `@WorkflowScoped`) is not
portable on the Jakarta EE 10 baseline: **CDI 4.0's `BeanManager` cannot enumerate
registered contexts**.
</details>

**3.** What happens when the condition is evaluated on the Arquillian **client**
JVM, outside the container?

<details><summary>Show answer</summary>

Outside the container the implementation's presence cannot be determined
(`CDI.current()` fails), so the condition returns **enabled** and **defers** the
real decision to the second evaluation, which happens inside the container, where
detection is reliable.
</details>

**4.** What is the difference in purpose between `@RequiresImplementation` and
`@RequiresNoImplementation`?

<details><summary>Show answer</summary>

`@RequiresImplementation` guards tests that **need the engine** (the
`@Decision`/`@Action`/`@Outcome` phases dispatched) — skipped on plain CDI.
`@RequiresNoImplementation` guards the **plain-CDI baseline** tests (e.g. the
trigger is invocable with CDI alone) — skipped when the implementation is present,
because the implementation's full behavioral assertion supersedes them.
</details>

**5.** What are `LargeLanguageModelStub.enqueueResponse(...)` and
`ExecutionTraceRecorder.assertOrder(...)` for in a typical behavioral test?

<details><summary>Show answer</summary>

`enqueueResponse` scripts the LLM responses (the test controls exactly what the
"model" answers, with no real service call), and the stub records every call for
assertions. `assertOrder(TRIGGER, DECISION, ACTION, OUTCOME)` verifies that the
engine executed the phases in the order the spec requires. Together they make
orchestration testable deterministically.
</details>

---

➡️ Next: [Chapter 5 — Payara implementation: the CDI extension](05-payara-impl-cdi-extension.md)
