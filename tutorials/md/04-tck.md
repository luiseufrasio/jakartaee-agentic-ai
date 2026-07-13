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

## Implementation detection — the elegant trick

How does the TCK know whether it is running on a compatible implementation
(Payara) or on plain CDI (Weld without the engine)? The
`ImplementationPresentCondition` (a JUnit 5 `ExecutionCondition`) checks **at
runtime, inside the container**:

```java
!CDI.current().getBeanManager().getContexts(WorkflowScoped.class).isEmpty()
```

**Every compatible implementation registers a `Context` for `@WorkflowScoped`;
plain CDI does not.** So the presence of that context is the implementation's
fingerprint — no system property, JVM flag or vendor configuration required.

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
- `core/behavior` — the deployed behavioral tests, each with its own set of
  fixture agents:
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
  - `voidphases`, `topologyflex`, `llm` — void phases, optional phases, the LLM
    contract inside a real workflow.
- `framework/signature` — API signature tests (binary compatibility).

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

**2.** How does the `ImplementationPresentCondition` detect that a compatible
implementation is present, with zero vendor configuration?

<details><summary>Show answer</summary>

It checks, inside the container, whether a **CDI `Context` is registered for the
`@WorkflowScoped` scope**:
`CDI.current().getBeanManager().getContexts(WorkflowScoped.class)`. Every
compatible implementation registers that context (it is a spec requirement); plain
CDI does not. It is a runtime fingerprint — no system property or JVM flag.
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
