# Chapter 7 — LLM backends and configuration

## The two-layer architecture

Payara's LLM separates the **spec contract** from the **provider transport**:

```
LargeLanguageModel (spec)
        ▲
        │ implements
LargeLanguageModelImpl ── {} placeholders, JSON-B, conversational history
        │ delegates
        ▼
LlmBackend (internal SPI)  ──  chat(systemPrompt, List<Turn>) → String
   ├── NoOpLlmBackend        (default; no provider configured)
   ├── OllamaLlmBackend      (local models, e.g. gemma3)
   ├── AnthropicLlmBackend   (Claude via the Messages API)
   └── VertexLlmBackend      (Claude via Google Vertex AI)
```

`LlmBackend` is a minimal interface: it receives the system prompt and the
conversation (a list of `Turn(role, content)`) and returns the response text. All
the spec logic (placeholders, serialization, state) lives in
`LargeLanguageModelImpl`, written **once** for every provider.

## `LargeLanguageModelImpl` — what is worth highlighting

- **Placeholders:** the `\{\}` regex; it counts the placeholders and validates
  against the number of parameters (the exact rules from chapter 3, including the
  "0 placeholders + 1 context" case, which appends the JSON on a new line after
  the prompt).
- **Serialization:** a `String` passes through as-is; any other object becomes
  JSON via JSON-B; a serialization failure ⇒ `IllegalArgumentException`.
- **Conversation:** each `query` appends a `user` turn, calls the backend with an
  **immutable copy** of the whole conversation, and appends the `assistant` turn
  with the response. If the backend fails, the `user` turn is **removed** (a
  rollback) — the history is never left with an orphaned question.
- **`unwrap`:** exposes the concrete backend (e.g.
  `llm.unwrap(AnthropicLlmBackend.class)`).

## `LlmBackendFactory` — selection via MicroProfile Config

All keys live under the **`payara.agentic.llm.`** prefix:

| Key | Values / default |
| --- | --- |
| `provider` | `none` (default → NoOp), `ollama`, `anthropic` (alias `claude`), `vertex` |
| `model` | Ollama default `gemma`; Anthropic/Vertex default `claude-opus-4-8` |
| `ollama.base-url` | `http://localhost:11434` |
| `anthropic.base-url` | `https://api.anthropic.com` |
| `anthropic.api-key` | or the `ANTHROPIC_API_KEY` env var |
| `vertex.project-id` | or the `ANTHROPIC_VERTEX_PROJECT_ID` env var (required) |
| `vertex.region` | or the `CLOUD_ML_REGION` env var; default `global` |
| `max-tokens` | `4096` |
| `system` | optional system prompt (also used as the cache prefix) |

Robustness decisions:

- **Unknown provider → NoOp**, never a failed deployment: the container always
  resolves a `LargeLanguageModel` without ambiguity.
- **Anthropic without an API key → `IllegalStateException`** with a message that
  says exactly which key/env var to set (fail fast with a diagnosis).
- Since it is MicroProfile Config, the configuration can come from the
  application's `META-INF/microprofile-config.properties`, from system properties
  or from environment variables — that is how **each sample picks its provider
  without code**.

## `AnthropicLlmBackend` — the details that draw questions

- **No SDK**: raw `java.net.http.HttpClient` + JSON-B. Reason: avoiding
  **dependency conflicts in the server's OSGi** (the Payara runtime is an OSGi
  module; dragging an SDK with its transitive dependencies into the module is
  asking for classloader clashes).
- Speaks the **Messages API** (`POST /v1/messages`, header `anthropic-version:
  2023-06-01`, `x-api-key` authentication).
- **Prompt caching:** when a system prompt is present, it is sent as a single text
  block with `cache_control: {"type": "ephemeral"}` — the stable prefix is reused
  across the workflow's phases (each phase re-sends the conversation; the cached
  system prompt reduces cost/latency). An honest nuance for the talk: Claude only
  caches prefixes above a minimum size (~4096 tokens on Opus); shorter prompts
  simply do not cache — **silently**, it is not an error.
- **Non-streaming**, appropriate for the modest `max_tokens` of agent phases; very
  large `max_tokens` would require streaming to avoid the HTTP timeout (120 s).
- A **per-call** system prompt (if any) takes precedence over the configured
  default.

## The other backends

- **`NoOpLlmBackend`** — returns a fixed/inert response; guarantees that injecting
  `LargeLanguageModel` works even without a configured provider (and is what the
  quickstart shows when it answers "(no answer — ... LLM provider is 'none')").
- **`OllamaLlmBackend`** — HTTP to the local Ollama server; the **no-API-key,
  zero-cost** option for offline demos (the quickstart uses `gemma3:4b`).
- **`VertexLlmBackend`** — Claude served by Google Vertex AI: same model family,
  GCP authentication/billing (project-id + region instead of an API key).

---

## Quiz — Chapter 7

**1.** Why was the `AnthropicLlmBackend` written with a plain `HttpClient` instead
of the official Anthropic SDK?

<details><summary>Show answer</summary>

Because `agentic-ai-core` runs as an **OSGi module inside the Payara server** — an
SDK would bring transitive dependencies that cause classloader/version conflicts in
OSGi. With `java.net.http.HttpClient` (JDK) + JSON-B (already on the platform), the
module drags in nothing external.
</details>

**2.** What happens at deployment if `payara.agentic.llm.provider=banana` (an
unknown value)? And with `provider=anthropic` but no API key?

<details><summary>Show answer</summary>

An unknown provider → falls back to the **`NoOpLlmBackend`** (the `switch` has a
default): the deployment works and injecting `LargeLanguageModel` resolves without
ambiguity. `anthropic` without `anthropic.api-key` and without the
`ANTHROPIC_API_KEY` env var → an immediate **`IllegalStateException`** with a
message stating exactly what to configure (fail fast).
</details>

**3.** How does the conversational history behave when a backend call fails in the
middle of the workflow?

<details><summary>Show answer</summary>

The freshly added `user` turn is **removed** from the conversation (a rollback)
before the exception propagates. That way the history is never left with a question
that has no answer, and a new `query` attempt rebuilds the conversation in a
consistent state.
</details>

**4.** Explain what the `cache_control: ephemeral` on the system prompt optimizes
in the context of an agent workflow — and the limitation that makes it "silent".

<details><summary>Show answer</summary>

Every workflow phase re-sends the full conversation to the Messages API; marking
the system prompt (the stable prefix) with `cache_control` lets Claude **reuse the
cached prefix** across the calls, reducing cost and latency. Limitation: the cache
only applies to prefixes above a minimum size (~4096 tokens on Opus) — smaller
system prompts do not cache, and that produces no error and no warning.
</details>

**5.** Where do the quickstart and the tutorial generator get their LLM
configuration from, and why does that showcase the spec's value so well?

<details><summary>Show answer</summary>

From each WAR's `META-INF/microprofile-config.properties` (quickstart: Ollama +
`gemma3:4b`; tutorial: Anthropic + `claude-opus-4-8` + a system prompt). The
**agents' code is identical in style and mentions no provider at all** — switching
from a free local model to Claude in the cloud means editing a properties file.
Vendor neutrality in practice.
</details>

---

➡️ Next: [Chapter 8 — The samples](08-samples.md)
