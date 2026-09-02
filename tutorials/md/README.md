# Tutorial — Jakarta Agentic AI (Spec + Payara Implementation + Samples)

A hands-on tutorial for Jakarta Agentic AI: the specification, the Payara
implementation, and the samples. It covers three layers:

| Layer | Location | What it is |
| --- | --- | --- |
| **Specification** | `jakartaee-agentic-ai` repository | The `jakarta.ai.agent` API, the spec document and the TCK |
| **Implementation** | `Payara\appserver\agentic-ai\agentic-ai-core` | The Payara runtime: CDI extension, workflow engine, LLM backends |
| **Samples** | `examples/` in this repository | `quickstart` (question/answer), `tutorial-generator` (generation + chat refinement) and `course-content-studio` (two agents + approval gate). Payara-flavoured twins of the first two, carrying the Arquillian tests, live under `payara-samples\samples\`. |

## How to use it

- **Reading mode:** read the chapters in order. Quiz answers are hidden behind
  `▸ Show answer` blocks (click to expand).
- **Interactive mode:** open the HTML version (`tutorials/html/index.html`) for
  chapter navigation, interactive quizzes and progress tracking.

## Chapters

1. [Overview and motivation](01-overview.md) — what the spec is, why it exists, module architecture
2. [The programming model](02-programming-model.md) — `@Agent`, `@Trigger`, `@Decision`, `@Action`, `@Outcome`, `@HandleException`, `@WorkflowScoped`
3. [LargeLanguageModel and errors](03-largelanguagemodel.md) — the LLM facade, `{}` placeholders, JSON-B, `LLMException`
4. [The TCK](04-tck.md) — how the spec is verified: `@Standalone`, `@Deployed`, `@Assertion`, stub and trace recorder
5. [Payara implementation: the CDI extension](05-payara-impl-cdi-extension.md) — agent discovery, synthetic observer, self-vetoing default LLM
6. [Payara implementation: WorkflowEngine and scope](06-payara-impl-engine.md) — phase orchestration, `WorkflowContext`, `ParameterResolver`, `@WorkflowScoped`
7. [LLM backends and configuration](07-llm-backends.md) — MicroProfile Config, Ollama, Anthropic (Claude), Vertex, NoOp, prompt caching
8. [The samples](08-samples.md) — quickstart and tutorial generator, line by line
9. [Wrap-up: running the samples and FAQ](09-presentation-guide.md) — recap, how to run the samples, FAQ + final quiz

Every chapter ends with a **quiz** to check your understanding — reveal the
answer and score yourself.