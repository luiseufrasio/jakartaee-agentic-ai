# Chapter 2 — The programming model (the annotations)

This chapter covers every type in the `jakarta.ai.agent` package with the exact
rules of the spec — including the subtleties that tend to trip people up.

## `@Agent` — declaring the agent

```java
@Agent(name = "QuestionAgent", description = "Answers a question using the configured LLM.")
public class QuestionAgent { /* ... */ }
```

- A **class-level** annotation (`@Target(TYPE)`), runtime retention.
- `name` default: the simple class name with the first letter lowercased
  (`MyAgent` → `myAgent`).
- `description`: for documentation and discovery.
- **Supported scopes: only two** — `@WorkflowScoped` and `@ApplicationScoped`.
  If none is declared, **the default is `@WorkflowScoped`** (in Payara, the CDI
  extension adds the annotation in `ProcessAnnotatedType`).

### The two scopes side by side

| Aspect | `@WorkflowScoped` (default) | `@ApplicationScoped` |
| --- | --- | --- |
| Agent instance | **A new one per workflow execution**; born at the trigger, dies after the outcome (or failure) | **A single one** shared by all executions, for the application's lifetime |
| Instance fields | Private to that execution — accumulate workflow state freely | Shared across concurrent workflows — must be **thread-safe** and must not hold state of a specific execution |
| Generic CDI observers (`@Observes` without `@Trigger`) | **Forbidden** (deployment error) | Allowed |
| Typical use | Agent with per-execution state (the common case) | Stateless agent, expensive-to-initialize resources, or an agent that also needs to be a conventional CDI observer |

And the fine detail that makes them equal: even when the agent is
`@ApplicationScoped`, **a workflow context is created for every execution**. The
conversational state of the injected `LargeLanguageModel` follows the lifecycle of
**that context**, not the bean's — two concurrent executions of a singleton agent
still have isolated conversations. In other words: the agent's scope decides where
**the agent's fields** live; the LLM conversation is always per workflow.

Why does the generic-`@Observes` restriction apply only to `@WorkflowScoped`? A
regular observer is invoked by the container **outside** a workflow — and with no
active workflow there is no context in which to create/resolve the
`@WorkflowScoped` agent instance. With `@ApplicationScoped` the instance exists
independently of any workflow, so the regular observer just works.

### Example 1 — `@WorkflowScoped`: fraud analysis with accumulated state

The classic case for the default scope: the agent **accumulates state in instance
fields across the phases** — each execution gets its own fresh instance, so the
fields are a private scratchpad of the workflow, with no concurrency risk.

```java
@Agent(description = "Analyzes suspicious transactions and builds a fraud dossier.")
public class FraudAnalysisAgent {          // no scope declared ⇒ @WorkflowScoped

    @Inject
    LargeLanguageModel llm;

    // State PRIVATE to this execution — born at the trigger, dies after the outcome.
    private final List<String> findings = new ArrayList<>();  // no synchronization!
    private int riskScore;

    @Trigger
    void onTransaction(BankTransaction tx) {
        riskScore = tx.amount() > 10_000 ? 20 : 0;            // first hint
    }

    @Decision
    boolean isSuspicious(BankTransaction tx) {
        String verdict = llm.query("Is this transaction suspicious? {}", tx);
        if (verdict.contains("yes")) {
            findings.add(verdict);                             // accumulate in the field
            riskScore += 50;
        }
        return riskScore > 40;                                 // otherwise, terminate
    }

    @Action
    void investigate(BankTransaction tx) {
        findings.add(llm.query("List the fraud indicators in: {}", tx));
        riskScore += findings.size() * 5;                      // refine the score
    }

    @Outcome
    void fileReport(BankTransaction tx, CaseService cases) {
        cases.open(tx, riskScore, findings);   // consolidates ALL accumulated state
    }
}
```

Why `@WorkflowScoped` is the right call here: `findings` and `riskScore` grow phase
by phase and only make sense **for this transaction**. If this agent were
`@ApplicationScoped`, two simultaneous transactions would mix each other's
dossiers. And notice there is no `synchronized` and no concurrent collections —
none needed: nobody else can see this instance.

### Example 2 — `@ApplicationScoped`: triage with a shared expensive resource

The application scope pays off when the agent carries an **expensive resource that
should be initialized once** and/or also needs to be a **regular CDI observer** —
the two capabilities `@WorkflowScoped` does not give you:

```java
@Agent(description = "Classifies support tickets against the knowledge base.")
@ApplicationScoped                          // ONE instance for the whole application
public class TicketTriageAgent {

    @Inject
    LargeLanguageModel llm;

    // Expensive resource: loaded ONCE, reused by every workflow.
    private volatile KnowledgeBase kb;
    // Shared state requires thread-safe types:
    private final AtomicLong triaged = new AtomicLong();

    @PostConstruct
    void init() {
        kb = KnowledgeBase.loadFromDisk();   // minutes of loading — startup only
    }

    // REGULAR CDI observer (no @Trigger): allowed because it is @ApplicationScoped.
    // Runs OUTSIDE any workflow — e.g. a knowledge-base reload published by an admin.
    void onKnowledgeBaseUpdated(@Observes KbUpdatedEvent event) {
        kb = KnowledgeBase.loadFromDisk();
    }

    @Trigger
    void onTicket(SupportTicket ticket) {
        triaged.incrementAndGet();           // global metric — AtomicLong
    }

    @Decision
    Result classify(SupportTicket ticket) {
        String category = llm.query(
            "Classify this ticket using these categories: {}\nTicket: {}",
            kb.categories(), ticket);
        return new Result(!"spam".equals(category), category);
    }

    @Action
    void route(SupportTicket ticket, String category, QueueService queues) {
        queues.dispatch(category, ticket);
    }
}
```

Why `@ApplicationScoped` is the right call here: the `KnowledgeBase` is expensive
to load and is **read-only during the workflows** — reloading it for every ticket
(which is what `@WorkflowScoped` would do, via a per-execution `@PostConstruct`)
would be prohibitive. The `onKnowledgeBaseUpdated` observer is the scope's
exclusive bonus: an administrative event that **starts no workflow at all**, it
just refreshes the resource. The price is visible in the code: `volatile`,
`AtomicLong` — every field is shared and the concurrency discipline is yours. And
remember: even here, each ticket gets **its own workflow context** — the `llm`
conversation in one ticket's `classify` never contaminates another's.

**Rule of thumb:** *in-flight case* state in fields →
`@WorkflowScoped` (the default exists for this); *expensive, shared* resources +
the need for regular observers → `@ApplicationScoped`, with thread safety on you.

### Why only two scopes?

The spec does not support `@RequestScoped`, `@SessionScoped`,
`@ConversationScoped` or `@Dependent` for agents, and the rationale is sound:

1. **The agent's natural lifecycle is the workflow, not the request.** A trigger
   can fire from anywhere — a timer, a batch job, a message, another agent — where
   an HTTP request/session **does not even exist**. Tying the agent to web scopes
   would make its behavior depend on who fired the event.
2. **`@Dependent` makes no sense for something that is never injected.** The
   dependent scope follows the lifecycle of whoever injects the bean — but agents
   are injected by no one: they are **event-driven** by the engine. There is no
   "owner" for the dependent to follow.
3. **The two scopes cover the only two answers to the question that matters:**
   is the agent's field state *per execution* (`@WorkflowScoped`) or *shared*
   (`@ApplicationScoped`)? Any other scope would be a confusing answer to that
   question.
4. **1.0 simplicity.** Fewer combinations = leaner spec, smaller TCK, easier to
   verify implementations. If real use cases for other scopes appear, adding later
   is easier than removing.

## `@Trigger` — the entry point

```java
@Trigger
void onQuestion(@Valid Question question) {
    logger.info("workflow started for: " + question.text());
}
```

Rules:

- **Exactly one** `@Trigger` per agent (in 1.0).
- Invoked when a **CDI event** compatible with the parameter is fired. The
  `@Observes` on the parameter is **optional** — the container understands the
  intent from `@Trigger` alone.
- The triggering event is automatically added to the **workflow context**, so
  later phases can receive it as a parameter.
- **Important scope restriction:** `@WorkflowScoped` agents can only observe
  events via `@Trigger`. A "loose" `@Observes` method (without `@Trigger`) on a
  `@WorkflowScoped` agent is a **deployment error**. `@ApplicationScoped` agents
  can have both (triggers and regular CDI observers).
- Accepted parameters: the event, `LargeLanguageModel`, and any injectable CDI
  dependency. They may carry Bean Validation constraints (`@Valid`, `@NotNull`…) —
  validation happens **before** invocation and a violation becomes a
  `ConstraintViolationException`, handleable by `@HandleException`.
- Return: `void` (side effects only) **or** a domain object, which enters the
  workflow context and becomes injectable into later phases.

### A trigger that returns a domain object — enriching the context

The example above is the `void` pattern. The second return pattern makes the
trigger **produce data** for the rest of the workflow — typically a pre-analysis
of the event, often already using the LLM:

```java
@Agent
public class ClaimAgent {

    @Trigger
    ClaimAnalysis analyzeClaim(InsuranceClaim claim, LargeLanguageModel llm) {
        // Pre-analysis at the entry point: classify the claim right in the trigger.
        // The (non-void) return value enters the workflow context.
        return llm.query(
            "Classify this insurance claim (severity, category) as JSON: {}",
            ClaimAnalysis.class, claim);
    }

    @Decision
    boolean needsAdjuster(ClaimAnalysis analysis) {      // ← the trigger's return
        return analysis.severity() > 3;
    }

    @Action
    void assign(ClaimAnalysis analysis, InsuranceClaim claim, AdjusterPool pool) {
        pool.assign(claim, analysis.category());  // event AND analysis, by type
    }
}
```

What the engine does behind the scenes (this is chapter 6's `WorkflowContext`):
after the trigger, the context holds **two** objects —

```
WorkflowContext
├── InsuranceClaim   ← the CDI event (added automatically, always)
└── ClaimAnalysis    ← the trigger's RETURN value (added because it is not void/null)
```

— and every later phase declares in its parameters **whichever of the two it
wants**, by type: `needsAdjuster` asks only for the analysis; `assign` asks for
both. No parameter is passed manually — resolution is the container's job. It is
the same mechanism that later receives the returns of `@Decision` (the `Result`'s
`details()`) and of `@Action`, each stacking onto the context for later phases.

When to use each pattern: `void` when the trigger only initializes/logs (the event
itself is enough for the next phases); a domain return when there is a
**transformation or analysis of the event** that later phases will consume — it
avoids repeating the analysis in every phase and keeps the trigger as the single
place that "translates" the raw event.

## `@Decision` — decision points

```java
@Decision
Result hasContent(Question question) {
    boolean proceed = question.text() != null && !question.text().isBlank();
    return new Result(proceed, question);
}
```

- 0..N per agent; they can be **intermixed with actions**.
- Typically query the LLM to decide the workflow's direction.
- **Three return patterns** (memorize this):

| Return | Proceeds if... | Data propagated |
| --- | --- | --- |
| `boolean` | `true` | nothing |
| `Result` | `result.success() == true` | the `details()` enters the context |
| Domain object | non-null | the object itself enters the context |

- Returning `false`, `Result(false, ...)` or `null` **ends the workflow** without
  running the remaining phases or the `@Outcome`.

### Multiple decisions in the same workflow — how they talk

Multiple decisions form a **chain of serial gates** (a logical AND): each one must
approve for the next phase to run, and they communicate **through the workflow
context** — the data one publishes becomes a parameter of the next. A credit
pipeline shows the three return patterns cooperating:

```java
@Agent
public class LoanAgent {

    // GATE 1 — cheap, no LLM: cut early what does not even deserve analysis.
    // Result(true, policy) publishes the PolicyCheck into the context.
    @Decision
    Result withinPolicy(LoanApplication app, PolicyService policies) {
        PolicyCheck policy = policies.check(app);
        return new Result(policy.approved(), policy);
    }

    // GATE 2 — expensive, with LLM. CONSUMES the PolicyCheck published by gate 1.
    // Object return: non-null ⇒ proceed (and publish); null ⇒ stop.
    @Decision
    RiskAssessment assessRisk(LoanApplication app, PolicyCheck policy,
                              LargeLanguageModel llm) {
        RiskAssessment risk = llm.query(
            "Assess the risk of this application: {} given policy limits: {}",
            RiskAssessment.class, app, policy);
        return risk.score() < 700 ? null : risk;
    }

    // An action between decisions: builds the offer from the risk analysis.
    @Action
    LoanOffer prepareOffer(RiskAssessment risk, LoanApplication app) {
        return new LoanOffer(app, risk.suggestedRate());
    }

    // GATE 3 — AFTER an action: validates what the action produced.
    // Boolean: only decides, publishes nothing.
    @Decision
    boolean offerViable(LoanOffer offer) {
        return offer.rate() < MAX_LEGAL_RATE;
    }

    @Outcome
    void send(LoanOffer offer, NotificationService mail) {
        mail.sendOffer(offer);
    }
}
```

The data flow through the context, gate by gate:

```
Trigger                     ctx: [LoanApplication]
withinPolicy  ✔ Result ──►  ctx: [LoanApplication, PolicyCheck]
assessRisk    ✔ object ──►  ctx: [LoanApplication, PolicyCheck, RiskAssessment]
prepareOffer  (action) ──►  ctx: [..., LoanOffer]
offerViable   ✔ boolean ─►  ctx unchanged (a Boolean publishes no data)
send          (outcome)     consumes LoanOffer
```

The rules of the "conversation":

1. **Order matters** — here it is declaration order; with `@Priority`/`order` the
   chain can be rearranged without moving code (respecting the consistency
   requirement).
2. **Communication is always via the context, by type** — `assessRisk` receives
   the `PolicyCheck` because gate 1 published it via `Result.details()`. There is
   no direct call between decisions and no mandatory shared variable (although a
   `@WorkflowScoped` agent may also use fields, like `FraudAnalysisAgent` does).
3. **Each gate cuts off the rest of the workflow** — if `assessRisk` returns
   `null`, then `prepareOffer`, `offerViable` and `send` do not run. There is no
   "else": in 1.0, branching is a **serial filter**, not an if/else tree.
   Alternative branches are modeled with the gate + action-conditioned-on-published-
   data pattern (or with another agent listening to another event).
4. **A decision after an action is valid and useful** — `offerViable` validates
   the *product* of `prepareOffer`. This is the "intermixed" pattern the TCK covers
   with the `IntermixedAgent`/`BranchingAgent` fixtures.
5. **Pick the return type by what you need to communicate**: `boolean` for a pure
   gate, an object when the verdict *is* the data, `Result` when you want to
   separate the verdict (`success`) from the data (`details`) — including
   publishing data on a negative verdict handled through another path.

⚠️ Nuance: early termination **does not undo side effects** of phases that
already ran. If `prepareOffer` had persisted the offer and `offerViable` returned
`false`, the database row would still be there — the workflow stops, it does not
roll back (unless you integrate it with a transaction of your own). How to do that
is the next topic.

#### Undoing side effects with Jakarta Transactions

First, the honest disclaimer: **the 1.0 spec does not define transactional
semantics for workflows**. But two facts we have already seen make the integration
natural: the workflow runs **synchronously on the thread that called
`Event.fire`**, and synchronous CDI observers execute, by default, **inside the
caller's transactional context**. Therefore a `@Transactional` on the caller wraps
the whole workflow:

```java
@Path("loans")
@RequestScoped
public class LoanResource {

    @Inject Event<LoanApplication> trigger;

    @POST
    @Transactional              // JTA: ONE transaction wraps the ENTIRE workflow
    public Response apply(LoanApplication app) {
        trigger.fire(app);      // trigger→decisions→actions→outcome, in this transaction
        return Response.ok().build();
    }
}
```

But there is a central catch: **early termination is normal completion** — the
decision returns `false`, `fire` returns without error, and the transaction
**commits**, `prepareOffer`'s persistence included. Rollback in JTA requires an
**exception**. So the gate that must undo what came before needs to **throw**
instead of returning `false`:

```java
@Decision
boolean offerViable(LoanOffer offer) {
    if (offer.rate() >= MAX_LEGAL_RATE) {
        // NOT "return false": that would end the workflow and the transaction would COMMIT.
        throw new OfferRejectedException(offer);   // ⇒ rollback of EVERYTHING
    }
    return true;
}
```

The exception path closes the loop with what we have already studied: with no
matching `@HandleException` (or with a handler that **rethrows**), it crosses the
engine, exits through `fire()` and blows up inside the `@Transactional` method →
the transaction is marked for rollback → `prepareOffer`'s `INSERT` is undone with
it. Three consequences to get right:

1. **A `@HandleException` that recovers, commits.** If a handler catches the
   `OfferRejectedException` and returns normally, the exception never reaches the
   transaction — recovery means "the workflow succeeded", and whatever was
   persisted stays. Handler and transaction must be designed **together**:
   recover = keep effects; rethrow = undo.
2. **`@Transactional` on a single phase has a different effect**: annotating only
   `prepareOffer` creates a transaction that commits **when the phase returns** —
   a later gate failing no longer undoes it. It buys atomicity *inside* the phase,
   not protection for the chain.
3. **A redesign often beats the transaction**: if the validation does not depend
   on the side effect, move the gate to run **before** the action (`offerViable`
   checking the rate *before* persisting) or leave persistence to the `@Outcome`,
   which only runs once every gate has approved. The transaction is the tool for
   when the effect and the validation are inseparable (e.g. you must insert to get
   an ID the validation uses).

## `@Action` — the real work

```java
@Action
void generate(Question question) {
    String answer = model.query("Answer concisely: {}", question.text());
    answers.put(question.text(), answer);
}
```

- 0..N per agent; they perform operations (persisting, calling services, updating
  state).
- Return: `void` or a domain object (which enters the context for later phases).
- They receive as parameters: earlier decision results, the trigger event,
  `LargeLanguageModel`, CDI beans.

## Execution order of `@Decision`/`@Action`

Precedence, applied in this order:

1. **`@Priority` on the method** — lower values run first; it **beats** `order()`.
2. **The annotation's own `order()` attribute** — used when there is no
   `@Priority`.
3. **Source-code declaration order** — used when **no** method declares explicit
   ordering. Careful: Java SE reflection does **not** guarantee declaration order,
   though mainstream JVMs preserve it in practice; portable applications that need
   strict ordering must use `@Priority`/`order`.

**Consistency requirement:** if **any** `@Decision`/`@Action` in the agent
declares an explicit `order` or `@Priority`, **all** the others must too. Mixing
ordered and unordered methods is a **deployment error**.

Before the examples, two reminders: ordering applies **only to the
`@Decision`/`@Action` chain** (`@Trigger` always opens and `@Outcome` always
closes, outside the contest), and decisions and actions are ordered **together, in
a single queue** — not as two separate lists.

### Case 1 — No ordering: declaration order rules

```java
@Agent
public class ReportAgent {

    @Trigger  void onRequest(ReportRequest req) { }

    @Decision boolean hasData(ReportRequest req)   { /* ... */ }   // 1st
    @Action   Draft   buildDraft(ReportRequest req){ /* ... */ }   // 2nd
    @Decision boolean draftOk(Draft draft)         { /* ... */ }   // 3rd
    @Action   void    publish(Draft draft)         { /* ... */ }   // 4th

    @Outcome  void done(Draft draft) { }
}
```

Execution: `hasData → buildDraft → draftOk → publish` — exactly the order they
appear in the source. Simple and readable... until someone **reorders the methods
in a refactor** and silently changes the behavior: no compiler warns you that the
method order was semantic. That risk (besides the missing formal guarantee from
reflection) is what explicit ordering eliminates.

### Case 2 — `order()`: the position leaves the text and becomes a contract

The same agent, with the order declared — the methods can now sit at **any position
in the file** (here, deliberately shuffled) without affecting execution:

```java
@Agent
public class ReportAgent {

    @Trigger  void onRequest(ReportRequest req) { }

    @Action(order = 40)   void    publish(Draft draft)          { /* ... */ }  // 4th
    @Decision(order = 10) boolean hasData(ReportRequest req)    { /* ... */ }  // 1st
    @Decision(order = 30) boolean draftOk(Draft draft)          { /* ... */ }  // 3rd
    @Action(order = 20)   Draft   buildDraft(ReportRequest req) { /* ... */ }  // 2nd

    @Outcome  void done(Draft draft) { }
}
```

Execution: `hasData(10) → buildDraft(20) → draftOk(30) → publish(40)`. Practical
tips: use **increments of 10** (inserting a new step between 20 and 30 becomes
`order = 25`, with no renumbering) and **avoid `order = 0`** — zero is the
annotation's default value, so it does not count as explicit ordering; use positive
values.

### Case 3 — `@Priority` beats `order()` on the same method

```java
@Agent
public class MixedAgent {

    @Trigger void on(StartEvent e) { }

    @Priority(1)
    @Action(order = 99)          // order is IGNORED: @Priority is present on the method
    void runsFirst() { /* ... */ }     // sort key = 1

    @Action(order = 2)
    void runsSecond() { /* ... */ }    // no @Priority ⇒ order = 2 applies
}
```

Execution: `runsFirst (key 1) → runsSecond (key 2)` — despite the `order = 99`.
The rule is **per method**: on each one, `@Priority` (if present) supplies the sort
key; otherwise `order()` does. The resulting keys are then compared across every
method in the chain. It pays to pick **one** style per agent (`@Priority` OR
`order`) and mix only during migrations.

### Case 4 — Invalid mix: deployment error

```java
@Agent
public class BrokenAgent {

    @Trigger void on(StartEvent e) { }

    @Decision(order = 10) boolean gate(StartEvent e) { /* ... */ }  // explicit
    @Action               void step1() { /* ... */ }                // ✘ implicit!
}
```

Deployment fails (in Payara: `DefinitionException: Inconsistent order at @Agent ...
all @Decision/@Action should declare @Priority or order or nothing.`). The
reasoning behind the rule: if `step1` had no order, what would its position be
relative to `gate(10)`? Any answer (before? after? declaration order just for it?)
would be an obscure convention — the spec prefers forcing explicit intent over
guessing.

## `@Outcome` — the terminal phase

- **0 or 1** per agent (two is a deployment error); optional.
- Runs after the other phases complete **successfully**.
- **Must return `void`** in 1.0 (finalization and side effects, not data
  production).
- After it completes, **the container destroys the workflow context**.

## `@HandleException` — error recovery

```java
@HandleException
void handleLlmFailure(LLMException ex, Question question) {
    logger.warn("LLM unavailable, using fallback", ex);
    // normal return ⇒ the workflow CONTINUES
}
```

Semantics (the richest in the spec — study it well):

- 0..N per agent; they catch exceptions from **any phase** (trigger, decision,
  action, or outcome).
- **Handler selection:** the one with the **most specific** exception type
  compatible with the thrown exception (follows the Java hierarchy). The exception
  parameter is required.
- **Workflow control:**
  - Handler **returns normally** ⇒ successful recovery, the workflow continues.
  - Handler **rethrows or throws a new exception** ⇒ the workflow stops; the
    exception propagates to the container.
- No matching handler ⇒ the exception propagates to the container.
- **No recursive handling:** an exception thrown by a handler is not redirected to
  another handler — it goes straight to the container.
- Return **must be `void`**.

### The scenarios, one by one

A single payment agent illustrates every possible path. The exception hierarchy
used: `PaymentException` (base) ← `GatewayTimeoutException` (derived).

```java
@Agent
public class PaymentAgent {

    @Trigger
    void onPayment(PaymentRequest req) { /* ... */ }

    @Decision
    boolean authorized(PaymentRequest req, LargeLanguageModel llm) { /* ... */ }

    @Action
    void charge(PaymentRequest req, GatewayClient gateway) {
        gateway.charge(req);   // may throw GatewayTimeoutException, LLMException...
    }

    @Outcome
    void confirm(PaymentRequest req, Receipts receipts) { /* ... */ }

    // ── SCENARIO 1: recovery — returns normally, the workflow "succeeds" ──
    // Note: receives the exception AND workflow state (req comes from the context).
    @HandleException
    void onTimeout(GatewayTimeoutException ex, PaymentRequest req,
                   RetryQueue retries) {
        retries.enqueue(req);              // recovery: reprocess later
        // normal return ⇒ the engine considers the workflow RECOVERED
        // and still runs the @Outcome confirm() as closure
    }

    // ── SCENARIO 2: fatal — rethrows, the workflow stops ──
    @HandleException
    void onPaymentError(PaymentException ex, PaymentRequest req, AuditLog audit) {
        audit.paymentFailed(req, ex);      // record BEFORE giving up
        throw ex;                          // propagates to the container; @Outcome does NOT run
    }

    // ── SCENARIO 3: conditional recovery — decides at runtime ──
    @HandleException
    void onLlmFailure(LLMException ex, PaymentRequest req) {
        if (req.amount() < 100) {
            return;                        // low amount: approve without LLM, continue
        }
        throw new ManualReviewException(req, ex);   // high amount: stop and escalate
    }

    // ── SCENARIO 4: safety net — the most generic type ──
    @HandleException
    void onAnyError(Exception ex, AlertService alerts) {
        alerts.notifyOps(ex);
        throw new IllegalStateException("Unexpected payment failure", ex);
    }
}
```

Now, what happens in each situation:

| `charge` throws... | Handler chosen | Why | Outcome |
| --- | --- | --- | --- |
| `GatewayTimeoutException` | `onTimeout` | The **most specific** match wins — `onPaymentError(PaymentException)` would also match, but it is more generic | Returns normally ⇒ workflow recovered, `confirm()` (`@Outcome`) **runs** |
| `PaymentException` (other than a timeout) | `onPaymentError` | The only specific match | Rethrows ⇒ workflow **stops**, `confirm()` does not run, the exception reaches the container (and the caller's `@Transactional`, if any) |
| `LLMException` | `onLlmFailure` | Exact match | Depends on the amount: returns (continue + outcome) **or** throws `ManualReviewException` — which is **not** re-handled by the other handlers (no recursion): it goes straight to the container |
| `NullPointerException` | `onAnyError` | Only the generic `Exception` matches | Alerts and rethrows wrapped ⇒ workflow stops |
| An `Error` (e.g. `OutOfMemoryError`) | none | An `Error` is not an `Exception` — no parameter matches | Propagates straight to the container |

Four fine details hidden in the example:

1. **Selection follows the hierarchy, not declaration order** — `onAnyError` being
   last in the file does not matter; it is only chosen when no more specific type
   matches (Payara pre-sorts the handlers most-specific-first at deployment).
2. **Handlers receive workflow state** — `onTimeout` declares `PaymentRequest` and
   `RetryQueue` besides the exception: parameter resolution is the same as in the
   other phases (in-flight exception → context → CDI).
3. **Recovering runs the `@Outcome`** — scenario 1 ends with `confirm()` running
   (an engine rule, chapter 6: the outcome as the recovery's closure phase —
   except when the outcome itself was what failed).
4. **Scenario 3's `ManualReviewException` does not fall back to the safety net** —
   that is the "no recursive handling" rule: an exception thrown *by a handler* is
   never dispatched to another handler, even if the agent has a generic
   `Exception` one. Without this, a buggy handler could create an infinite handling
   loop.

And the link with the previous section: if the caller wrapped the `fire` in a
`@Transactional`, **scenario 1 commits** (recovery = success) and **scenarios 2 and
4 roll back** (the exception crosses) — the handler's design decides the
transaction's fate.

## `@WorkflowScoped` — the workflow scope

- A CDI **normal scope** (`@NormalScope`): one context per workflow execution,
  spanning trigger → outcome. Beans are born when the workflow starts and die when
  it ends.
- Typical use: sharing state between phases without passing parameters (e.g. an
  analysis cache).
- Ships a `Literal` (`WorkflowScoped.Literal.INSTANCE`) for inline instantiation —
  it is what the Payara extension uses to apply the default scope
  programmatically.

## `Result` and data propagation

```java
public record Result(boolean success, Object details) {}
```

The general mechanism of **type-based data propagation**:

1. The trigger event enters the context.
2. Every non-null phase return enters the context (for a `Result`, the `details()`
   goes in; a decision's `Boolean` carries no data).
3. When invoking a phase, each parameter is resolved **by type**, preferring the
   **most recently** produced value (if two phases produced the same type, the
   latest wins).
4. Whatever is not in the context is resolved as a CDI bean.

---

## Quiz — Chapter 2

**1.** A `@WorkflowScoped` agent declares, besides the `@Trigger`, a method
`void onAudit(@Observes AuditEvent e)` without `@Trigger`. What happens at
deployment?

<details><summary>Show answer</summary>

**Deployment error** (`DefinitionException`). `@WorkflowScoped` agents can only
observe CDI events through `@Trigger` methods. Generic CDI observers are only
allowed on `@ApplicationScoped` agents.
</details>

**2.** A `@Decision` returns `new Result(true, new Plan("x"))`. What exactly
becomes available to later phases, and how does an `@Action` receive it?

<details><summary>Show answer</summary>

The workflow proceeds (`success == true`) and the **`details()`** — the `Plan("x")`
object — is published into the workflow context. An `@Action` receives it simply by
declaring a parameter of type `Plan`: `@Action void execute(Plan plan) {...}`.
Resolution is by type, most recent value first.
</details>

**3.** In an agent, method A has `@Action(order = 5)` and method B has just
`@Action`. Is that valid?

<details><summary>Show answer</summary>

**No** — it violates the consistency requirement: if any `@Decision`/`@Action`
declares an explicit `order` or `@Priority`, all the others must declare one too.
Mixing explicitly ordered with unordered methods is a deployment error.
</details>

**4.** A method has `@Action(order = 10)` and also `@Priority(1)`. Which value
determines its execution position?

<details><summary>Show answer</summary>

The `@Priority(1)` — when present on the method, `@Priority` **takes precedence**
and `order()` is ignored. Lower values run first.
</details>

**5.** A `@HandleException` catches an `IOException`, logs it, and returns
normally. The exception happened in an intermediate `@Action`. Does the `@Outcome`
run?

<details><summary>Show answer</summary>

**Yes.** A handler returning normally means recovery: the workflow continues, and
the `@Outcome` phase (if present and not yet attempted) runs as closure. If the
handler had rethrown the exception, the workflow would stop and the exception would
propagate to the container.
</details>

**6.** Why is relying only on source declaration order risky for ordering phases,
according to the spec itself?

<details><summary>Show answer</summary>

Because Java SE does **not** guarantee that reflection returns methods in source
declaration order — mainstream JVMs preserve it in practice, but it is not a
contract. Portable applications that need strict ordering must declare `@Priority`
or `order` explicitly.
</details>

**7.** In the `LoanAgent`, how does the `PolicyCheck` produced by the first
decision reach the second one (`assessRisk`)? And if `assessRisk` returns `null`
after `withinPolicy` approved, what runs and what does not?

<details><summary>Show answer</summary>

`withinPolicy` returns `Result(true, policy)` — the `details()` (the `PolicyCheck`)
is **published into the workflow context**, and `assessRisk` receives it by
declaring a parameter of type `PolicyCheck` (type-based resolution; decisions never
call each other directly). If `assessRisk` returns `null`, the workflow **ends
right there**: `prepareOffer`, `offerViable` and the `@Outcome` do not run. And
careful: what already ran is **not undone** — early termination is not a rollback.
</details>

**8.** The `LoanResource` wraps the `fire` in a `@Transactional`. `prepareOffer`
persisted the offer and then `offerViable` returns `false`. Is the `INSERT` undone?
And what if, instead of returning `false`, the decision threw an exception that a
`@HandleException` catches and handles by returning normally?

<details><summary>Show answer</summary>

In both cases the `INSERT` **stays in the database**. Returning `false` is
**normal** early termination: `fire` returns without error and the transaction
**commits**. And if the exception is caught by a handler that returns normally, it
never reaches the `@Transactional` method — recovery means a successful workflow,
hence commit. Rollback only happens when the exception **crosses** the engine (no
handler, or a handler that rethrows) and blows up inside the caller's transaction.
That is why handler and transaction must be designed together: recover = keep
effects; rethrow = undo.
</details>

**9.** An `@ApplicationScoped` agent stores the current workflow's partial result
in an instance field. What is the problem, and where should that state live? And
the LLM conversation — does it leak across executions too?

<details><summary>Show answer</summary>

The bean is **a single one for the whole application**: concurrent workflows
overwrite each other's field (race condition and state leaking across executions).
Per-execution state should live in the **workflow context** — phase returns
propagated by type, an auxiliary `@WorkflowScoped` bean, or simply using the agent
in the default `@WorkflowScoped` scope. The LLM conversation, however, does **not**
leak: the spec requires conversational state isolated **per workflow context**,
even with an `@ApplicationScoped` agent — the agent's scope governs the bean's
fields, not the LLM history.
</details>

**10.** In the `PaymentAgent`, `charge` throws a `GatewayTimeoutException`. The
agent has handlers for `GatewayTimeoutException`, `PaymentException` (supertype)
and `Exception`. Which one is invoked and why? And if that handler in turn throws a
new exception — does the `Exception` handler catch it?

<details><summary>Show answer</summary>

`onTimeout(GatewayTimeoutException)` — selection follows the Java hierarchy and
picks the **most specific** compatible type, regardless of declaration order in the
file. If it then throws a new exception, **no other handler is consulted**: the
"no recursive handling" rule applies — an exception thrown by a handler goes
straight to the container, even if a generic `@HandleException(Exception)` exists.
This prevents infinite handling loops (a handler handling another handler's
failure).
</details>

---

➡️ Next: [Chapter 3 — LargeLanguageModel and errors](03-largelanguagemodel.md)
