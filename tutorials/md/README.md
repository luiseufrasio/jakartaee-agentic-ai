# Tutorial — Jakarta Agentic AI (Spec + Payara Implementation + Samples)

Study material for the Payara conference talk (August 2026).
It covers the three layers of the presentation:

| Layer | Location | What it is |
| --- | --- | --- |
| **Specification** | `jakartaee-agentic-ai` repository | The `jakarta.ai.agent` API, the spec document and the TCK |
| **Implementation** | `Payara\appserver\agentic-ai\agentic-ai-core` | The Payara runtime: CDI extension, workflow engine, LLM backends |
| **Samples** | `payara-samples\samples\agentic-ai-quickstart` and `...\samples\agentic-ai` | Quickstart (question/answer) and Tutorial Generator (generation + chat refinement) |

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
9. [Presentation playbook](09-presentation-guide.md) — suggested narrative, demo checklist, likely audience questions + final quiz

Every chapter ends with a **quiz** — at the conference you will get similar
questions from the audience, so treat each quiz as a Q&A rehearsal.