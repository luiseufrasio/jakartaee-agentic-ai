# Chapter 1 — Overview and motivation

## What is Jakarta Agentic AI

**Jakarta Agentic AI** is a Jakarta EE specification (package `jakarta.ai.agent`)
for building **AI agents** in a vendor-neutral way. An agent is a **CDI bean**
that encapsulates autonomous, goal-driven behavior: it **perceives** an event,
**reasons** (typically by querying an LLM), **decides** whether and how to proceed,
and **acts** — all inside a workflow with well-defined phases.

The analogy that works well on stage: just as Jakarta Persistence standardized
relational data access (you program against `EntityManager`, and Hibernate or
EclipseLink implement it), Jakarta Agentic AI standardizes agent construction —
you program against annotations and the `LargeLanguageModel` interface, and the
application server (Payara, in our case) provides the orchestration engine and the
LLM provider integration.

## Why a spec for agents?

Today every AI framework in Java (LangChain4j, Spring AI, etc.) has its own
programming model. Problems the spec attacks:

1. **Vendor lock-in** — switching the LLM provider or the framework means
   rewriting the agent code. With the spec, provider selection is server
   configuration (in Payara, via MicroProfile Config), not code.
2. **No container integration** — agents need dependency injection, scopes,
   events, validation, transactions. Instead of reinventing all that, the spec
   **builds on CDI**: the trigger is a CDI event observer, the agent is a bean,
   the workflow scope is a custom CDI scope.
3. **Ad-hoc workflows** — without a phase model, every application invents its own
   state machine. The spec defines a standard lifecycle:
   `Trigger → Decision* → Action* → Outcome`, with `HandleException` cutting across.

### A practical example: swapping GPT (OpenAI) for Claude (Anthropic)

The same requirement — "starting today we use Claude" — solved in the three worlds.

**With LangChain4j**, the provider choice is *compiled into* the application: the
wiring class and the Maven dependency are vendor-specific.

```java
// BEFORE — pom.xml: dev.langchain4j:langchain4j-open-ai
ChatModel model = OpenAiChatModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-4o")
        .build();
```

```java
// AFTER — swap the pom.xml dependency to dev.langchain4j:langchain4j-anthropic,
// rewrite the wiring and recompile/repackage the application
ChatModel model = AnthropicChatModel.builder()
        .apiKey(System.getenv("ANTHROPIC_API_KEY"))
        .modelName("claude-opus-4-8")
        .build();
```

The code that *uses* the `ChatModel` interface may survive — but the switch still
requires changing a dependency + construction code, a rebuild and a redeploy. And
the abstraction belongs to a single-vendor library, not to a standard.

**With Spring AI**, you must swap the starter in `pom.xml`
(`spring-ai-starter-model-openai` → `spring-ai-starter-model-anthropic`) and
migrate the property block (`spring.ai.openai.*` → `spring.ai.anthropic.*`). The
code injecting `ChatClient` can stay the same — fair to acknowledge — but it is
still an application rebuild, the abstraction only exists inside Spring, and there
is a single implementation of it, with no spec or TCK guaranteeing portable
behavior.

**With Jakarta Agentic AI**, the agent injects the **platform** interface and
mentions no provider at all:

```java
@Agent
public class QuestionAgent {

    @Inject
    LargeLanguageModel model;   // jakarta.ai.agent — no vendor here

    @Action
    void generate(Question question, AnswerStore answers) {
        String answer = model.query("Answer concisely: {}", question.text());
        answers.put(question.text(), answer);
    }
}
```

The whole switch is **one configuration file** (in Payara, MicroProfile Config):

```properties
# BEFORE                                   # AFTER
payara.agentic.llm.provider=ollama         payara.agentic.llm.provider=anthropic
payara.agentic.llm.model=gemma3:4b         payara.agentic.llm.model=claude-opus-4-8
```

Zero code changes, zero `pom.xml` changes (the provider's HTTP backend lives in
the **server**, not in the WAR), zero recompilation — at most a redeploy. It is the
same leap Jakarta Persistence made over hand-rolled JDBC: the provider became a
configuration detail. The two samples in chapter 8 prove this live: their agents
are written the same way, one running on local Ollama and the other on Claude, and
the only difference between them is `microprofile-config.properties`.

## The mental model: a workflow of phases

```
   CDI event
       │
       ▼
  ┌─────────┐    ┌───────────┐    ┌─────────┐    ┌──────────┐
  │ @Trigger │──▶│ @Decision │──▶│ @Action │──▶│ @Outcome │
  └─────────┘    └───────────┘    └─────────┘    └──────────┘
   (required,     (0..N, may       (0..N)         (0..1, void,
    exactly 1)     stop the flow)                  ends the context)

              @HandleException (0..N) catches exceptions from ANY phase
```

Key points:

- **Trigger** is the only required phase — exactly **one** method per agent in
  version 1.0 (a restriction expected to be relaxed in the future).
- **Decisions and actions can be intermixed** in any sequence, enabling anything
  from `Trigger + Action` (simple execution) up to
  `Trigger + Decision + Action + Decision + Action` (complex branching).
- A **Decision can end the workflow** (by returning `false`, `null` or
  `Result(false, ...)`) — the remaining phases and the Outcome do **not** run.
- **Outcome** marks the successful end of the workflow; after it, the container
  destroys the workflow context.
- **Data flows between phases by type**: whatever a phase returns becomes
  available as a parameter of later phases (type-based injection, no manual
  parameter passing).

## The spec repository architecture

The project is a multi-module Maven build with four modules:

| Module | Contents |
| --- | --- |
| `api/` | The `jakarta.ai.agent` package: 7 annotations, 1 interface (`LargeLanguageModel`), 1 record (`Result`), 1 exception (`LLMException`). **No implementation code.** |
| `spec/` | The specification document in AsciiDoc (`jakarta-agentic-ai.adoc`). |
| `tck/` | The Technology Compatibility Kit — the tests any implementation must pass to claim compatibility (chapter 4). |
| `examples/` | Usage examples. |

## Fundamental design decisions

These are the decisions that generate the most questions — memorize the rationale:

1. **CDI-first.** The agent is a CDI bean; the trigger fires on CDI events
   (`Event.fire(...)`). Future versions may add other sources (Jakarta Messaging,
   REST, programmatic invocation), but 1.0 is pure CDI. This gives you for free:
   injection, interceptors, events, scopes.
2. **Jakarta JSON Binding (JSON-B) for serialization** — not Jackson. Reason:
   **portable, consistent** behavior across implementations; JSON-B is already part
   of the Jakarta EE platform.
3. **Baseline: Java 17, Jakarta EE 10, CDI 4.1.**
4. **The `LargeLanguageModel` facade is minimalist on purpose.** In 1.0 each
   implementation chooses how to configure the provider. Future versions will
   standardize provider selection and common settings (temperature, max tokens) —
   the same evolutionary path Jakarta Persistence took with its providers.
5. **Conversational state per workflow.** Even with an `@ApplicationScoped` agent,
   the LLM conversation is isolated per workflow execution — two concurrent
   executions never mix history.

## Split of responsibilities: spec × implementation

An important subtlety (it shows up in the TCK): **plain CDI can invoke the
`@Trigger`** (it is just an event observer), but the `@Decision`, `@Action` and
`@Outcome` phases require an **orchestration engine** — which is what the Payara
implementation (`agentic-ai-core`) provides. The TCK uses the
`@RequiresImplementation` / `@RequiresNoImplementation` conditions to separate what
is testable with plain CDI from what needs a compatible implementation.

---

## Quiz — Chapter 1

**1.** Which workflow phase is required, and how many methods of that phase can an
agent declare in version 1.0?

<details><summary>Show answer</summary>

`@Trigger` is the only required phase, and the agent must declare **exactly one**
`@Trigger` method. More than one (or none) is a deployment error
(`DefinitionException` in the Payara implementation). The single-trigger
restriction is expected to be relaxed in future versions to support multiple entry
points.
</details>

**2.** Why does the spec require Jakarta JSON Binding instead of letting each
implementation pick its own serialization library (for example, Jackson)?

<details><summary>Show answer</summary>

To guarantee **portable, consistent behavior** across implementations: the same
domain object must produce the same serialized form in the prompt in any compatible
implementation. Besides, JSON-B is already a Jakarta EE platform spec, so it adds
no external dependency.
</details>

**3.** What happens to the rest of the workflow when a `@Decision` method returns
`false`?

<details><summary>Show answer</summary>

The workflow **ends immediately**: the remaining `@Decision`/`@Action` phases and
the `@Outcome` do **not** run. This is not an error — it is normal early
termination. The `@WorkflowScoped` context is destroyed normally.
</details>

**4.** A colleague asks: "if the agent is a regular CDI bean, why do I need the
Payara implementation? Can't Weld alone run the agent?" What do you answer?

<details><summary>Show answer</summary>

Plain CDI can only **fire the `@Trigger`** — it is essentially a CDI event
observer. But orchestrating the following phases (`@Decision`, `@Action`,
`@Outcome`), propagating data between them by type, applying the termination rules
and dispatching exceptions to `@HandleException` requires an **orchestration
engine**, which is exactly what the compatible implementation (Payara's
`agentic-ai-core`) provides via a portable CDI extension.
</details>

**5.** Name the four Maven modules of the spec repository and the role of each.

<details><summary>Show answer</summary>

- `api` — the `jakarta.ai.agent` types (annotations + interfaces, no
  implementation);
- `spec` — the specification document in AsciiDoc;
- `tck` — the compatibility tests implementations must pass;
- `examples` — API usage examples.
</details>

---

➡️ Next: [Chapter 2 — The programming model](02-programming-model.md)