# Chapter 8 — The samples

Three samples, three levels: the **quickstart** teaches the
programming model in 5 classes; the **tutorial generator** shows a real use case
with a chat refinement loop; and the **Course Content Studio** raises the bar to
**two agents chained by CDI events**, with a human approval gate in the middle and
a final student-facing view.

---

## Sample 1 — `agentic-ai-quickstart`

**The smallest possible agent that exercises the four phases.** A REST POST fires
a CDI event; the agent answers the question with the configured LLM.

```
POST /agentic-ai-quickstart/api/ask  { "question": "..." }  →  { "question", "answer" }
```

### The complete flow, class by class

**`Question`** — a simple record, the **CDI event** that triggers the workflow.
*Deliberately without validation constraints*, so a blank question reaches the
`@Decision` and demonstrates early termination.

**`AskResource`** (JAX-RS, `@RequestScoped`):

```java
@Inject Event<Question> trigger;
@Inject AnswerStore answers;

trigger.fire(question);            // runs the ENTIRE workflow synchronously
String answer = answers.get(text); // reads the result produced by the @Action
```

The code comment is the soul of the demo: `Event.fire` is synchronous, so the
complete workflow (including the LLM call) finishes **before** `fire` returns.

**`QuestionAgent`** — the four phases, each logging its prefix so `server.log`
tells the story:

```java
@Agent(name = "QuestionAgent", description = "Answers a question using the configured LLM backend.")
public class QuestionAgent {
    @Inject LargeLanguageModel model;
    @Inject AnswerStore answers;

    @Trigger  void onQuestion(@Valid Question question) { /* ... */ }   // [TRIGGER]
    @Decision Result hasContent(Question question) {                    // [DECISION]
        boolean proceed = question.text() != null && !question.text().isBlank();
        return new Result(proceed, question);
    }
    @Action   void generate(Question question) {                        // [ACTION]
        String answer = model.query("Answer concisely in one short paragraph: {}",
                                    question.text());
        answers.put(question.text(), answer);
    }
    @Outcome  void complete(Question question) { /* ... */ }            // [OUTCOME]
}
```

Note the didactic details:

- **No scope annotation** → the runtime applies `@WorkflowScoped` (the spec's
  default, via the extension).
- The `@Decision` uses the **`Result`** pattern: `Result(false, ...)` when the
  question is blank → `@Action` and `@Outcome` **do not run** (the early
  termination demo).
- The `@Action` uses a **`{}` placeholder** with a positional parameter.
- **`AnswerStore`** is `@ApplicationScoped` with a `ConcurrentHashMap` — the
  bridge between the agent and the synchronous HTTP response.

### Configuration (local Ollama — zero-cost demo)

```properties
payara.agentic.llm.provider=ollama
payara.agentic.llm.model=gemma3:4b
payara.agentic.llm.ollama.base-url=http://localhost:11434
```

### Manual run script

1. `winget install Ollama.Ollama` and `ollama pull gemma3:4b`;
2. Make sure the distribution has the current `agentic-ai-core` (package + copy
   the JAR into `glassfish/modules/` + restart clearing the OSGi cache);
3. `mvn package` the sample and `asadmin deploy .../agentic-ai-quickstart.war`;
4. POST to `/agentic-ai-quickstart/api/ask` and watch
   `[TRIGGER] → [DECISION] → [ACTION] → [OUTCOME]` in `server.log`;
5. Repeat with an empty `question` → `[DECISION] proceed=false` and the answer
   "(no answer — workflow terminated...)".

### Integration test

`AgenticQuickstartIT` (Arquillian) **needs no live LLM**: the deployment includes
`StubLargeLanguageModel` and, by the **self-vetoing LLM** rule (chapter 5), the
application's LLM beats the runtime's default. The test asserts the scripted
answer and the early termination on a blank question.

---

## Sample 2 — `agentic-ai` (Tutorial Generator)

**A real use case:** an agent writes a **field-by-field guide** for a web form (a
customer registration form to contract Azul Payara Server) and allows **refining
the guide via chat**. The page shows the form on the left, the generated guide on
the right, and a refinement chat box below.

```
GET  /agentic-ai/                           the side-by-side UI
GET  /agentic-ai/api/form                   the form metadata (FormSpec)
POST /agentic-ai/api/tutorial/generate      generate a fresh guide
POST /agentic-ai/api/tutorial/refine        { "instruction": "..." } refine the whole guide
POST /agentic-ai/api/tutorial/refine-field  refine ONE field and merge it back
```

### The strong design ideas

1. **Single source of truth:** `CustomerFormSpec` defines the form; the page
   renders the live form from it **and** the agent explains those same fields —
   they cannot diverge.
2. **The event carries the mode:** `TutorialRequest(formSpec, instruction,
   currentHtml)`. `currentHtml` null/blank → **generate** from scratch; filled →
   **refine** applying `instruction`. One agent, two behaviors, decided in the
   `@Action`.
3. **Refinement passes the real artifact:** on every chat turn, the **current**
   guide + the instruction go into the prompt — the model edits the actual
   artifact instead of relying on conversational memory alone. (An important
   agent-engineering pattern worth remembering.)
4. **Per-field refinement with a merge:** `refine-field` extracts only the target
   field's description (JSON-P), runs the workflow over that fragment, and
   **merges** the result back into the full guide — preserving the other fields
   and saving tokens.
5. **LLM robustness:** `stripCodeFences` removes the ``` fences that models
   sometimes insist on adding — an honest reminder that LLM output requires
   defensive post-processing.

### The agent

```java
@Trigger  void onRequest(TutorialRequest request)      // logs generate|refine
@Decision Result hasFields(TutorialRequest request)    // any fields? if not, stop
@Action   void render(TutorialRequest request) {
    if (generate)  content = model.query("Generate the field-guide JSON ... : {}", request.formSpec());
    else           content = model.query("Current field-guide JSON:\n{}\n\nApply this change...: {}\n\n...",
                                         request.currentHtml(), request.instruction());
    store.put(stripCodeFences(content));
}
@Outcome  void complete(TutorialRequest request)       // logs the guide's size
```

Note: the refinement prompt uses **two `{}` placeholders** — the current guide and
the instruction, substituted positionally.

### Configuration (Anthropic/Claude — HTML quality)

```properties
payara.agentic.llm.provider=anthropic
payara.agentic.llm.model=claude-opus-4-8
payara.agentic.llm.max-tokens=8192
payara.agentic.llm.system=You are a senior technical writer...
```

The system prompt comes from **configuration** (not code) and becomes the **prompt
caching prefix** (chapter 7). To run fully local: switch to
`provider=ollama` / `model=gemma3:12b` (a 12B-class model is recommended for HTML
quality).

⚠️ **Operational gotcha:** `ANTHROPIC_API_KEY` must be in the environment
**before** `asadmin restart-domain`, so the server process inherits it.

### Integration test

`AgenticTutorialIT` — same pattern as the quickstart: `StubLargeLanguageModel` in
the deployment, no live LLM. It asserts the form is exposed, the guide is
generated, and a chat refinement produces a different result.

---

## Sample 3 — `course-content-studio` (education domain)

📦 Repository: <https://github.com/luieufrasio/course-content-studio>

**An advanced use case:** the teacher pastes a chapter's content and picks a
subject (mathematics, physics, English); an agent generates an **introduction, a
quiz and a conclusion**; the teacher **refines** by section and, on **approval**, a
**second agent** builds the **published lesson** that the student sees. It is the
sample that exercises almost the whole spec and shows the architectural
differentiator: **agent composition through CDI events**.

```
GET  /course/                           the studio (chapter left, packet right, refine chat)
GET  /course/student.html               the published lesson, as a student sees it
POST /course/api/packet/generate        generate intro + quiz + conclusion
POST /course/api/packet/refine-section  { section: intro|quiz|conclusion|all, instruction }
POST /course/api/packet/approve         approve + trigger the PublishAgent
GET  /course/api/lesson                 the published (student-facing) lesson
POST /course/api/quiz/grade             grade an open answer via the LLM (similarity)
GET  /course/api/progress/{runId}       live phase progress (Server-Sent Events)
```

### The strong design ideas

1. **Two agents, chained only by CDI events.** `CourseContentAgent` generates/
   refines the packet; on approval, the REST layer fires the `LessonApproved`
   event, which is the **`@Trigger`** of a second `@Agent` (`PublishAgent`). There
   is no orchestrator: the **human approval** is the gate between them. Each agent
   has its own workflow and its own LLM conversation.
2. **Truly ordered phases.** The phases carry an explicit `order`
   (`@Decision(order=5)`, `@Action(order=10/20/30)`), guaranteeing intro → quiz →
   conclusion. Reminder from ch. 5: if **one** phase is ordered, **all** must be,
   otherwise the deploy fails with "Inconsistent order".
3. **Per-workflow state in the agent itself.** No scope annotation → the runtime
   applies `@WorkflowScoped`, so the `draft` instance field accumulates the packet
   across phases safely (one instance per execution).
4. **Workflow conversational memory.** The conclusion does **not** re-send the
   chapter: it relies on the earlier turns (intro and quiz) of the same workflow
   conversation.
5. **Typed result via JSON-B, with defensive parsing.** The quiz becomes a `Quiz`
   record. Because small models wrap JSON in ``` fences, `parseQuiz` **strips the
   fences and extracts the `{…}`** before binding, with a placeholder quiz as a
   last resort — the workflow never aborts on bad JSON.
6. **Per-section HITL.** The event carries the mode (`currentDraftJson` blank →
   generate; filled → refine) and the `section`, so `refine-section` rewrites
   **only the quiz** (or only the intro), preserving the rest.
7. **Live progress (SSE).** Since `Event.fire` is synchronous, each phase reports
   to a `ProgressTracker` that streams over Server-Sent Events; the browser popup
   evolves with the agent's real steps.
8. **Polymorphic quiz + LLM grading.** `QuizQuestion` has a `type`
   (`multiple_choice` | `open`). For an **open** question the student answers in a
   `<textarea>` and the `quiz/grade` endpoint asks the LLM for a **0–100
   semantic-similarity** score against the `sampleAnswer`, mapped on the server to
   a verdict (≥70 correct, 50–69 partial, <50 incorrect). A spec detail worth
   citing: this endpoint **injects the `LargeLanguageModel` directly** into a
   `@RequestScoped` resource — it works because the runtime's LLM is `@Dependent`
   (it needs no active workflow), so not every use of the model must be inside an
   agent.

### The two agents

```java
// Agent 1 — authoring (ordered, workflow memory, defensive parsing)
@Agent(name = "CourseContentAgent")
class CourseContentAgent {
    private CoursePacket draft;                                   // @WorkflowScoped state
    @Trigger  void onRequest(@Valid CoursePacketRequest r)        // generate | refine
    @Decision(order = 5)  boolean hasTeachableContent(...)        // gate
    @Action(order = 10)   void writeIntro(...)                    // prose (per-subject rubric)
    @Action(order = 20)   void writeQuiz(...)  { draft.setQuiz(parseQuiz(model.query(...))); }
    @Action(order = 30)   void writeConclusion(...)               // uses workflow memory
    @Outcome  void publish(...)                                   // writes to PacketStore
    @HandleException void onLlmFailure(LLMException e)            // resilience
}

// Agent 2 — publishing, triggered by LessonApproved (event chaining)
@Agent(name = "PublishAgent")
class PublishAgent {
    @Trigger  void onApproved(LessonApproved e)                  // receives the approved packet
    @Decision boolean hasApprovedContent(...)
    @Action   void writeObjectives(...)                          // LLM: "what you'll learn"
    @Outcome  void publish(...)                                  // writes to PublishedLessonStore
}
```

### Configuration (Vertex/Claude — cloud, demo-proof)

```properties
payara.agentic.llm.provider=vertex
payara.agentic.llm.model=claude-sonnet-4-6
payara.agentic.llm.max-tokens=8192
payara.agentic.llm.system=You are an expert curriculum designer and teacher...
```

Vertex authenticates via ADC (`gcloud auth application-default login`) or
`GOOGLE_ACCESS_TOKEN`; project and region come from `ANTHROPIC_VERTEX_PROJECT_ID` /
`CLOUD_ML_REGION`. **Why Vertex rather than Ollama here:** the Ollama backend has a
**120 s per-call timeout**; a 12B model on a laptop takes ~2 min per call and the
quiz step blows the limit. On the cloud it answers in seconds.

⚠️ Maths/physics formulas are rendered with **self-hosted MathJax**
(`webapp/vendor/mathjax/tex-svg.js`) — the rubric asks for LaTeX (`$...$`,
`$$...$$`) and rendering works **offline**.

### Details worth highlighting

- The student view (`student.html`) shows the lesson with an **interactive quiz**
  (answer and *Check answers* marks correct/incorrect + explanation for MCQ; for
  the open question it grades by LLM similarity and reveals the model answer) —
  the "final product" that closes the generate → review → **publish** → consume
  narrative.
- Everything is single-author (single-slot stores), consistent with a live demo.

---

## Quiz — Chapter 8

**1.** In the quickstart, why does the `Question` record have **no** Bean
Validation constraints, even though the `@Trigger` uses `@Valid`?

<details><summary>Show answer</summary>

It is intentional and didactic: with no constraints, a **blank question passes the
trigger** and reaches the `@Decision`, which returns `Result(false, ...)` —
demonstrating the workflow's **early termination** (the `@Action` never runs and
the API answers "(no answer ...)"). With a `@NotBlank`, the violation would become
a `ConstraintViolationException` before the decision and the demo would showcase a
different feature.
</details>

**2.** Trace the full path of a `POST /api/ask` with a valid question all the way
to the JSON response, naming the classes involved.

<details><summary>Show answer</summary>

`AskResource.ask` creates a `Question` and calls `trigger.fire(question)` → the
**synthetic observer** (registered by the `AgenticAIExtension`) receives the event
→ the `WorkflowEngine` activates the context, runs `QuestionAgent.onQuestion`
(`@Trigger`), `hasContent` (`@Decision`, `Result(true, question)`), `generate`
(`@Action`, calls `model.query(...)` on the Ollama backend and writes to the
`AnswerStore`), `complete` (`@Outcome`) and destroys the context → `fire` returns →
`AskResource` reads `answers.get(text)` and returns
`AskResponse(question, answer)`.
</details>

**3.** How does the same `TutorialAgent` decide between generating a fresh guide
and refining the existing one, without two agents or two separate phases?

<details><summary>Show answer</summary>

**The event carries the mode**: a null/blank `TutorialRequest.currentHtml()` means
"generate from scratch"; a filled one means "refine applying `instruction()`". The
`@Action render` inspects that and builds the appropriate prompt — the refinement
one sends the current guide and the instruction with two `{}` placeholders.
</details>

**4.** What is the `refine-field` endpoint for, and how does it prevent one
field's refinement from ruining the others?

<details><summary>Show answer</summary>

It refines **a single field**: it extracts from the full guide (JSON-P) only the
requested field's description, fires the workflow over that fragment (fewer
tokens, more focus) and then **merges** the updated value back into the complete
JSON (`mergeField`), keeping the other fields' descriptions intact.
</details>

**5.** Both ITs (quickstart and tutorial) run with no real LLM at all. Which
implementation mechanism makes that possible without touching the agents' code?

<details><summary>Show answer</summary>

The `AgenticAIExtension`'s **self-vetoing default LLM**: the test deployments
include `StubLargeLanguageModel` (an application bean implementing
`LargeLanguageModel`); `watchForLlm` detects it and the runtime **does not
register** its own default LLM. The agents' `@Inject LargeLanguageModel` resolves
to the stub — same code, scripted answers, zero network.
</details>

**6.** Name two defensive measures in the tutorial generator against unpredictable
LLM behavior.

<details><summary>Show answer</summary>

(a) `stripCodeFences` — removes code fences (```) that models add even when told
not to; (b) the **refinement passes the current artifact explicitly** in the
prompt instead of trusting conversational memory — the model edits the real state.
(Bonus: the per-field merge in `refine-field` limits a bad response's blast radius
to a single field.)
</details>

**7.** In the Course Content Studio, how do the **two agents** communicate with no
orchestrator, and what triggers the second one?

<details><summary>Show answer</summary>

Through **CDI events**. `CourseContentAgent` writes the packet in its `@Outcome`;
when the teacher approves, `CourseResource` fires the `LessonApproved` event, which
is the **`@Trigger`** of the second agent (`PublishAgent`). The **human approval**
is the gate between them — full decoupling, no orchestration code. Since `fire` is
synchronous, the lesson is already published when `approve` returns.
</details>

**8.** Why does `writeQuiz` do **defensive parsing** (strip fences and extract the
`{…}`) instead of just using the typed `query(prompt, Quiz.class)` overload?

<details><summary>Show answer</summary>

Because smaller models tend to wrap the JSON in ``` ```json ``` fences or add prose
around it; the typed overload feeds that straight to JSON-B, which throws a
deserialization `LLMException`. `parseQuiz` **strips the fences, extracts the `{…}`
object and binds it to `Quiz`**, with a placeholder quiz as a last resort — so a
bad quiz never aborts the workflow and the later phases (conclusion, outcome) keep
running.
</details>

**9.** The `writeConclusion` `@Action` does **not** re-send the chapter text in the
prompt. Why does it still produce a coherent conclusion?

<details><summary>Show answer</summary>

Through the **workflow's conversational memory**: the previous `query()` calls
(intro and quiz) happened in the same `@WorkflowScoped` execution, and the
implementation keeps the conversational state isolated per workflow (ch. 6). The
conclusion builds on those earlier turns — it just asks to "tie together the intro
and quiz you just produced".
</details>

**10.** Grading an **open question** calls the LLM outside any agent, from a
`@RequestScoped` resource. Why does that work, and how is the verdict
(correct/partial/incorrect) decided?

<details><summary>Show answer</summary>

It works because the runtime's `LargeLanguageModel` is registered as
**`@Dependent`** (the per-workflow isolation comes from the `@Dependent` structure
+ the agent's scope, not from an active workflow context). Injecting it into a
resource yields a fresh instance with an empty conversation — perfect for a one-off
grade. The `quiz/grade` endpoint asks the LLM for a **0–100 similarity** score
between the student's answer and the `sampleAnswer`, and the **server** applies the
thresholds (≥70 correct, 50–69 partial, <50 incorrect) — keeping the deterministic
rule outside the model.
</details>

---

➡️ Next: [Chapter 9 — Wrap-up: running the samples and FAQ](09-presentation-guide.md)
