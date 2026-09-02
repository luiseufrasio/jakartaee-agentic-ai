# Chapter 9 — Wrap-up: running the samples and FAQ

## Recap: the whole story, end to end

The pieces you have seen fit together like this:

1. **The problem.** Everyone wants AI agents; in Java, every framework has its own
   proprietary model. The guiding question: "what would the *Jakarta Persistence*
   of agents look like?"
2. **The spec.** The phase model (`Trigger → Decision* → Action* → Outcome` +
   `HandleException`); a complete agent fits in one class (the `QuestionAgent`); the
   three `@Decision` return patterns; the `LargeLanguageModel` facade with `{}`
   placeholders; `@WorkflowScoped`.
3. **The quickstart.** POST a real question → `[TRIGGER] → [DECISION] → [ACTION] →
   [OUTCOME]` in `server.log`; POST an empty question → early termination. It runs
   on **local Ollama** (no network, no cost).
4. **Inside the implementation.** The CDI extension pipeline (the `@Observes`
   removal + synthetic observer is the key insight); the `WorkflowEngine`; the
   `ThreadLocal` scope; the self-vetoing LLM (which enables stub-based testing).
5. **The tutorial generator.** Generate a form guide with Claude; refine it via
   chat; refine a single field. Switching Ollama↔Claude is **one properties file**.
6. **The Course Content Studio.** Two agents chained by CDI events with a human
   approval gate, ordered phases, workflow conversational memory and LLM grading.
7. **The TCK and the road ahead.** How compatibility is proven; the
   `jakarta.ai.agent.tck.implementation.present` opt-in that separates
   plain-CDI baseline assertions from behavioral ones; what may come next
   (multiple triggers, other event sources, standardized LLM config).

## Running the samples — checklist

- [ ] Ollama installed, `ollama pull gemma3:4b` done, the service answering at
      `http://localhost:11434` (test: `ollama run gemma3:4b "hi"`).
- [ ] The current `agentic-ai-core.jar` copied into the distribution's
      `glassfish/modules/` + domain restarted **clearing the OSGi cache**
      (classic gotcha: new JAR with an old cache = old class).
- [ ] The cloud provider's credentials present **in the same shell/environment
      that starts the domain** (the server process inherits its parent's
      environment), *before* `asadmin restart-domain`. For the default Vertex
      config: `gcloud auth application-default login` plus
      `ANTHROPIC_VERTEX_PROJECT_ID` / `CLOUD_ML_REGION`. If you switch to
      `provider=anthropic`: `$env:ANTHROPIC_API_KEY = "sk-ant-..."`.
- [ ] All three WARs deployed and tested (`quickstart.war`,
      `tutorial-generator.war`, `course.war`).
- [ ] `server.log` open in a terminal (`Get-Content -Wait -Tail 0`).
- [ ] Fully-local option: the quickstart runs on Ollama; the tutorial generator
      can also fall back to `provider=ollama` / `model=gemma3:12b` (pull the model
      first). The Course Content Studio does **not** fall back well — the Ollama
      backend's 120 s per-call timeout is shorter than a laptop 12B model's quiz
      step.
- [ ] Requests ready (no typing JSON by hand): a script/`.http` file with the
      valid POST, the empty POST and the refines.

## Frequently asked questions

**"How does this compare with LangChain4j / Spring AI?"**
It does not compete — it standardizes. LangChain4j is an (excellent) single-vendor
library; Jakarta Agentic AI is a **specification** with a TCK: you program against
`jakarta.ai.agent` and switch implementations/providers without rewriting. An
implementation may even use LangChain4j underneath — and `unwrap()` exists exactly
to reach what the facade does not expose.

**"What if the LLM hallucinates/fails mid-workflow?"**
Two layers: `LLMException` (unchecked) for service failures, catchable by
`@HandleException` with clear recovery semantics (returned normally = continue;
rethrew = stop); and typed responses via JSON-B — if the model does not return the
expected JSON, you get an `LLMException`, not silently corrupted data. The tutorial
generator also shows applied defenses (code-fence stripping, per-field merge).

**"Is this asynchronous? Does it scale?"**
In 1.0 the workflow runs synchronously on the `Event.fire` thread — which keeps
the programming model simple and makes the result available in the same request.
Nothing stops the caller from firing the event from an executor/virtual thread.
Isolation is per workflow context (a ThreadLocal in Payara). Asynchronous
orchestration is a candidate for future versions.

**"Why CDI events as the trigger, and not a method I call directly?"**
Decoupling (the firer does not know the agent), it is infrastructure every Jakarta
EE server already has, and it allows natural fan-out (one event, several agents).
The spec already foresees other sources in the future (Messaging, REST,
programmatic).

**"Can multiple agents collaborate?"**
Yes, via events: one agent's `@Action`/`@Outcome` can inject an `Event<X>` and fire
another agent's trigger. First-class multi-agent orchestration is a topic for
future versions.

**"When does it ship? Is it official?"**
It is a specification proposal under development in the Jakarta EE ecosystem, with
an API, a spec document, a TCK and a working implementation in Payara. The roadmap
(multiple triggers, standardized LLM config) is already documented in the API
Javadocs.

**"Does it run locally? How much does it cost?"**
Quickstart: Ollama + gemma3:4b, zero cost, zero network. Tutorial generator:
Claude on Vertex for output quality, with prompt caching to cut cost — but it runs
on Ollama too. Course Content Studio needs the cloud: the Ollama backend's 120 s
per-call timeout is too short for its quiz step on a laptop.

## Key takeaways

1. **Agents as CDI beans** — the phase model
   (`@Trigger/@Decision/@Action/@Outcome/@HandleException`) turns "calling an LLM"
   into a container-managed workflow, with standardized scoping, injection,
   validation and error handling.
2. **Real vendor neutrality** — the agent's code does not know which LLM serves
   it; switching Ollama↔Claude↔Vertex is configuration (MicroProfile Config in
   Payara).
3. **A real spec** — with an executable TCK (every test tied to a requirement via
   `@Assertion`) and a complete implementation inside a production server (a
   portable CDI extension + engine), not a paper.

---

## Final quiz — putting it all together

**1.** From `trigger.fire(new Question("..."))` to the response JSON: describe the
path, citing at least: the extension, the observer, the engine, the context,
parameter resolution and the role of the scope.

<details><summary>Show answer</summary>

At deployment, the `AgenticAIExtension` removed the `@Observes` from the trigger,
applied the default `@WorkflowScoped`, validated the metadata and registered the
**synthetic observer** for `Question` + the `WorkflowScopeContext`. On `fire`: the
observer calls `WorkflowEngine.execute` → `activate()` puts the context on the
thread → the agent bean and the LLM (`@Dependent`, one per workflow) are resolved →
the `@Trigger` runs (the event already seeded into the `WorkflowContext`) → the
`@Decision` returns `Result(true, question)` (details goes into the context) → the
`@Action` receives `Question` by type via the `ParameterResolver`, queries the LLM
and writes to the `AnswerStore` → the `@Outcome` runs → `finally` destroys the
scope's beans (`@PreDestroy`) and clears the ThreadLocal → `fire` returns and the
REST resource reads the `AnswerStore`.
</details>

**2.** Name the three most likely `DefinitionException`s when writing a careless
agent, and explain why the spec prefers failing at deployment.

<details><summary>Show answer</summary>

Two `@Trigger`s; two `@Outcome`s; mixing phases with and without explicit ordering
(or: a generic `@Observes` on a `@WorkflowScoped` agent; an agent without a
`@Trigger`). Failing at deployment (fail fast) turns a structural error into
immediate, deterministic feedback, instead of undefined behavior on the first
production run — the same philosophy CDI applies to malformed beans.
</details>

**3.** You switched the tutorial generator to `provider=anthropic`, it returns an
empty guide, and the log shows `IllegalStateException: ... no API key found`. What
was the operational mistake and what is the fix?

<details><summary>Show answer</summary>

The `ANTHROPIC_API_KEY` was not in the **server process's** environment — probably
exported in a different shell or after the domain started. Fix:
`$env:ANTHROPIC_API_KEY = "sk-ant-..."` and **then** `asadmin restart-domain` in
the same shell (the server inherits the environment of whoever starts it). The same
trap applies to the shipped Vertex config, where the missing piece is ADC /
`ANTHROPIC_VERTEX_PROJECT_ID` instead. Plan B: switch to `provider=ollama` in the
microprofile-config.
</details>

**4.** Someone claims: "this is just an annotation wrapper around an HTTP call to
the LLM". Refute it with three concrete container capabilities the manual wrapper
would not have.

<details><summary>Show answer</summary>

(1) **Managed lifecycle**: a `@WorkflowScoped` context with automatic bean
creation/destruction and `@PreDestroy`, isolated across concurrent executions;
(2) **declarative orchestration**: phase ordering (`@Priority`/`order`/
declaration), standardized early termination, type-based data propagation between
phases and exception dispatch to the most specific handler with continue/stop
semantics; (3) **platform integration**: Bean Validation on phase parameters, CDI
interceptors, and vendor neutrality with per-workflow conversational state
guaranteed by the spec (plus the TCK to prove all of it).
</details>

**5.** In one sentence each, what is the role of: the spec API, the TCK,
`agentic-ai-core`, the quickstart and the tutorial generator in this tutorial?

<details><summary>Show answer</summary>

**API**: the vendor-neutral contract (`jakarta.ai.agent`) the developer programs
against. **TCK**: the executable proof that an implementation honors the contract.
**agentic-ai-core**: Payara's implementation — CDI extension + engine + LLM
backends. **Quickstart**: the "hello world" that teaches the four phases in five
classes. **Tutorial generator**: the real use case showing iterative chat
refinement and production defensive practices.
</details>

---

🏁 End of the tutorial. Review the quizzes you missed and run the samples yourself.
