# Chapter 3 — `LargeLanguageModel` and error handling

## The facade

`LargeLanguageModel` is the **only interface in the API** — a minimalist,
CDI-injectable facade for talking to the model:

```java
public interface LargeLanguageModel {
    String query(String prompt);
    <T> T query(String prompt, Class<T> resultType);
    String query(String prompt, Object... parameters);
    <T> T query(String prompt, Class<T> resultType, Object... parameters);
    <T> T unwrap(Class<T> implClass);
}
```

Four `query` variations covering the two axes: **with/without positional
parameters** × **String/typed response**. Plus `unwrap`, following the
`EntityManager.unwrap()` pattern from Jakarta Persistence, to access
vendor-specific APIs without breaking the portability of the rest of your code.

## The `{}` placeholder rules

The prompt accepts the exact token `{}` as a **positional** marker (inspired by
SLF4J). The exact rules — verified by the TCK:

1. Parameters are substituted **in declaration order**, each serialized with
   **Jakarta JSON Binding**.
2. If the prompt has N placeholders, exactly N parameters **must** be supplied.
   A mismatch ⇒ `IllegalArgumentException`.
3. **The exception to the rule:** a prompt with **no** placeholder may receive
   **at most one** parameter — it is sent to the model as **structured context**
   (Payara appends it as JSON on a new line after the prompt).
4. Only the exact token `{}` is a placeholder; any other use of braces
   (`{name}`, `{ }`) is literal prompt text.

```java
llm.query("Classify this event: {}", event);   // 1 placeholder, 1 parameter ✔
llm.query("Classify this event", event);       // 0 placeholders, 1 context ✔
llm.query("Compare {} with {}", a);            // ✘ IllegalArgumentException
```

## Typed responses

```java
Sentiment s = llm.query("Return JSON {\"score\": ..., \"label\": ...} for: {}",
                        Sentiment.class, review);
```

The LLM response (expected to be JSON) is **deserialized with JSON-B** into the
requested type. If deserialization fails (the model returned loose text, truncated
JSON...), the error becomes an **`LLMException`** — not `IllegalArgumentException`,
because the fault lies with the service's response, not the caller's arguments.

## Conversational state — the most important rule

> Implementations **must maintain conversational state for the current workflow
> context** across `query` calls.

That is: within the same workflow, the second `query(...)` call "remembers" the
first — the history is accumulated and re-sent to the model. And the boundaries:

- **`@WorkflowScoped` agent:** the conversation is bound to the workflow context
  and **ends with it**.
- **`@ApplicationScoped` agent:** the bean is a single one, but the conversation
  must remain **isolated per workflow context** — concurrent executions cannot
  leak history into each other.
- Implementations must be **thread-safe within a single workflow**.

### Scenario 1 — memory across phases of the same workflow

The second `query` does not need to resend what was already said: the
accumulated history travels along.

```java
@Agent
public class TriageAgent {

    @Inject
    private LargeLanguageModel llm;

    @Decision
    public boolean isRelevant(Ticket ticket) {
        // 1st turn: the ticket enters the conversation here
        String category = llm.query("Classify this ticket: {}", ticket);
        return !"SPAM".equals(category);
    }

    @Action
    public String draftReply(Ticket ticket) {
        // 2nd turn: the model "remembers" the ticket classified above —
        // note the prompt does not even repeat the ticket's content.
        return llm.query(
            "Write an initial reply for the ticket you just classified.");
    }
}
```

### Scenario 2 — `@WorkflowScoped`: the conversation dies with the workflow

Each event fired creates a new workflow context — and a fresh conversation.
There is no memory *across* workflows:

```java
tickets.fire(new Ticket("A"));  // workflow 1: its own conversation, discarded at the end
tickets.fire(new Ticket("B"));  // workflow 2: starts from scratch — no memory of ticket A
```

If the second prompt were `"Compare with the previous ticket"`, the model would
have no way to answer: workflow 1's history no longer exists.

### Scenario 3 — `@ApplicationScoped`: a singleton, but isolated conversations

The bean is one for the whole application; the conversational state is not — it
is **per workflow context**, even under concurrency:

```java
@Agent
@ApplicationScoped
public class SupportAgent {

    @Inject
    private LargeLanguageModel llm;   // injected once into the singleton...

    @Decision
    public boolean needsHuman(CustomerMessage msg) {
        String mood = llm.query("What is this customer's mood? {}", msg);
        return "ANGRY".equals(mood);
    }

    @Action
    public String reply(CustomerMessage msg) {
        // ...but each workflow sees ONLY its own history: if customers X
        // and Y are being served in parallel, the mood detected for X
        // never shows up in the prompt of Y's workflow.
        return llm.query("Reply in a tone that fits the mood you detected.");
    }
}
```

This is exactly the scenario of quiz question 3 — and what the TCK enforces
when it requires per-workflow isolation even for `@ApplicationScoped` agents.

In the Payara implementation this falls out of the architecture "for free":
`LargeLanguageModelImpl` keeps the conversation as a list of turns
(`user`/`assistant`) and is registered as a **`@Dependent`** bean — each injection
point/resolution inside the workflow gets its own instance, and the engine resolves
one LLM per workflow execution (details in chapter 6). One elegant detail: if the
backend call fails, the user turn is **removed** from the conversation (a
rollback), so the history is never left with a question and no answer.

## The error hierarchy

Two failure kinds, with different culprits:

| Exception | When | Culprit |
| --- | --- | --- |
| `IllegalArgumentException` | null prompt, null `resultType`, wrong placeholder count, parameter not serializable to JSON | the **caller** |
| `LLMException` (unchecked, extends `RuntimeException`) | communication failure, rate limiting, timeout, model unavailable, malformed response, **de**serialization failure of the response | the **LLM service** |

`LLMException` is unchecked on purpose: it does not force a try-catch around every
query, and it can be caught centrally by an agent's `@HandleException` method —
that is the idiomatic resilience pattern:

```java
@HandleException
void llmDown(LLMException ex, Question q) {
    answers.put(q.text(), "Service unavailable, please try again later.");
    // returns normally ⇒ the workflow proceeds to the @Outcome
}
```

## What 1.0 does NOT standardize (and why)

- **Provider selection and configuration** (temperature, max tokens...) are
  implementation-specific in 1.0. Payara uses MicroProfile Config with the
  `payara.agentic.llm.*` prefix (chapter 7).
- The plan declared in the Javadoc: future versions will standardize provider
  selection and a common set of properties — **the same model as Jakarta
  Persistence** (pluggable providers + common properties + `unwrap` for the rest).
- Streaming, tools/function calling, embeddings: out of scope for 1.0.

---

## Quiz — Chapter 3

**1.** `llm.query("Summarize the order", order, customer)` — the prompt has no `{}`
and two parameters were passed. What happens?

<details><summary>Show answer</summary>

**`IllegalArgumentException`**. A prompt without placeholders accepts **at most
one** parameter (sent as structured context). Two or more parameters with no
placeholders is a caller error.
</details>

**2.** The LLM answers `"Sure! Here is the JSON: {...}"` to a
`query(prompt, Invoice.class)` call and JSON-B deserialization fails. Which
exception is thrown, and why that one (and not the other)?

<details><summary>Show answer</summary>

**`LLMException`**. The failure lies in the **service's response** (the model did
not return pure JSON), not in the caller's arguments. `IllegalArgumentException`
is reserved for input errors (null prompt, placeholder count, non-serializable
parameter).
</details>

**3.** An `@ApplicationScoped` agent serves two events simultaneously, and each
workflow makes two `query` calls. What does the spec guarantee about conversational
history?

<details><summary>Show answer</summary>

Each workflow has its **own isolated conversation**: the second call of each
workflow sees only that workflow's history. Even though the agent is an
application-wide singleton, conversational state is **per workflow context** and
cannot leak across concurrent executions. The conversation ends when the workflow
context ends.
</details>

**4.** In the prompt `"Generate the guide for form {name} using {}"`, how many
placeholders does the spec recognize?

<details><summary>Show answer</summary>

**One** — only the exact token `{}`. The `{name}` is literal prompt text (there are
no named placeholders). Therefore exactly one parameter must be supplied.
</details>

**5.** What is `unwrap(Class<T>)` for, and which existing platform API inspired it?

<details><summary>Show answer</summary>

It grants access to the **underlying LLM implementation** to use vendor-specific
features not exposed by the facade (in Payara, for instance, the concrete backend).
Inspired by `EntityManager.unwrap()` from **Jakarta Persistence**. If the requested
type is not compatible, it throws `IllegalArgumentException`.
</details>

---

➡️ Next: [Chapter 4 — The TCK](04-tck.md)
