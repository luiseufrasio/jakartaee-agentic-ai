# Chapter 9 — Presentation playbook (Payara conference, August 2026)

## Suggested narrative arc (45–60 min)

1. **The problem (5 min).** Everyone wants AI agents; in Java, every framework has
   its own proprietary model. Hook: "what would the *Jakarta Persistence* of
   agents look like?"
2. **The spec (15 min).** The phase diagram
   (`Trigger → Decision* → Action* → Outcome` + `HandleException`); a complete
   agent on one slide (the `QuestionAgent` fits whole); the three `@Decision`
   return patterns; the `LargeLanguageModel` facade with `{}` placeholders;
   `@WorkflowScoped`.
3. **Demo 1 — quickstart (10 min).** POST a real question → show
   `[TRIGGER] → [DECISION] → [ACTION] → [OUTCOME]` in `server.log`; POST an empty
   question → early termination live. Running on **local Ollama** (no network, no
   cost — a conference-wifi-proof demo).
4. **Inside the implementation (10 min).** The CDI extension pipeline (the
   `@Observes` removal + synthetic observer is the "aha!" slide); the
   `WorkflowEngine`; the `ThreadLocal` scope; the self-vetoing LLM (and how it
   enables stub-based testing).
5. **Demo 2 — tutorial generator (10 min).** Generate the form guide with Claude;
   refine via chat ("make the business email explanation friendlier and add an
   example"); refine a single field. Show that the Ollama↔Claude switch is **one
   properties file**.
6. **TCK and the spec's path (5 min).** How compatibility is proven; detecting the
   implementation via the `@WorkflowScoped` context; what comes in 2.0 (multiple
   triggers, other event sources, standardized LLM config).
7. **Q&A.**

## Pre-demo technical checklist

- [ ] Ollama installed, `ollama pull gemma3:4b` done, the service answering at
      `http://localhost:11434` (test: `ollama run gemma3:4b "hi"`).
- [ ] The current `agentic-ai-core.jar` copied into the distribution's
      `glassfish/modules/` + domain restarted **clearing the OSGi cache**
      (classic gotcha: new JAR with an old cache = old class).
- [ ] `ANTHROPIC_API_KEY` exported **in the same shell/environment that starts the
      domain** (`$env:ANTHROPIC_API_KEY = "sk-ant-..."` before
      `asadmin restart-domain`) — the server process inherits its parent's
      environment.
- [ ] Both WARs deployed and tested the day before AND on the morning of the talk.
- [ ] `server.log` open in a big-font terminal (`Get-Content -Wait -Tail 0`).
- [ ] Offline plan B: the quickstart on Ollama already covers the main demo; the
      tutorial generator can fall back to `provider=ollama` / `model=gemma3:12b`
      (pull the model beforehand!).
- [ ] Requests prepared (no typing JSON live): a script/`.http` file with the
      valid POST, the empty POST and the refines.

## Likely audience questions (and the answers)

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
Position it carefully: it is a specification proposal under development in the
Jakarta EE ecosystem, with an API, a spec document, a TCK and a working
implementation in Payara — the material of this talk. The roadmap (multiple
triggers, standardized LLM config) is already documented in the API Javadocs.

**"Does it run locally? How much does the demo cost?"**
Quickstart: Ollama + gemma3:4b, zero cost, zero network. Tutorial generator:
Claude for HTML quality, with prompt caching to cut cost — but it runs on Ollama
too.

## Key messages (if the audience takes away only three things)

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

**3.** Your tutorial generator demo fails at the conference: the guide comes back
empty and the log shows `IllegalStateException: Anthropic provider selected but no
API key found`. What was the operational mistake and what is the fix?

<details><summary>Show answer</summary>

The `ANTHROPIC_API_KEY` was not in the **server process's** environment — probably
exported in a different shell or after the domain started. Fix:
`$env:ANTHROPIC_API_KEY = "sk-ant-..."` and **then** `asadmin restart-domain` in
the same shell (the server inherits the environment of whoever starts it). Plan B:
switch to `provider=ollama` in the microprofile-config.
</details>

**4.** An audience member claims: "this is just an annotation wrapper around an
HTTP call to the LLM". Refute it with three concrete container capabilities the
manual wrapper would not have.

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
`agentic-ai-core`, the quickstart and the tutorial generator in your talk?

<details><summary>Show answer</summary>

**API**: the vendor-neutral contract (`jakarta.ai.agent`) the developer programs
against. **TCK**: the executable proof that an implementation honors the contract.
**agentic-ai-core**: Payara's implementation — CDI extension + engine + LLM
backends. **Quickstart**: the "hello world" that teaches the four phases in five
classes. **Tutorial generator**: the real use case showing iterative chat
refinement and production defensive practices.
</details>

---

🏁 End of the tutorial. Review the quizzes you missed and run the demos. Good luck
on stage! 🎤
