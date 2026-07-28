# Roteiro de demo — Cap. 7: LLM backends & configuration

**Tempo-alvo:** ~2.5 min · **Objetivo:** a lógica da spec é escrita uma vez; o
provider é uma troca de config; escolhas de robustez. Fecha com a ponte para a demo.

**Beats:**
- Duas camadas: `LargeLanguageModelImpl` (placeholders, JSON-B, histórico) → SPI
  interna `LlmBackend` (NoOp / Ollama / Anthropic / Vertex).
- Seleção via MicroProfile Config `payara.agentic.llm.*`.
- Robustez: provider desconhecido → NoOp (nunca quebra deploy); anthropic sem key →
  fail fast com a mensagem exata.
- Anthropic: `HttpClient` cru + JSON-B, **sem SDK** (OSGi). Prompt caching
  (`cache_control`, silencioso abaixo do mínimo). Chamada falha → **rollback** do
  turno do usuário.
- Os samples trocam de provider com **um arquivo** → neutralidade de fornecedor.
  Nossa demo roda em **Vertex/Claude sonnet**.

---

## 🇬🇧 Script (spoken)

Where do the model calls actually go? Payara splits this into two layers. The spec
logic — placeholder substitution, JSON-B serialization, conversation history — lives
in one class, `LargeLanguageModelImpl`, written once. Underneath is a tiny internal
SPI, `LlmBackend`: give it the system prompt and the turns, get back text. Four
implementations: a no-op default, Ollama for local models, Anthropic for Claude, and
Vertex for Claude on Google Cloud.

Which one you get is pure MicroProfile Config — everything under
`payara.agentic.llm`. And the robustness choices matter in a live setting: an
unknown provider falls back to no-op, so a typo never fails your deployment. But
Anthropic without an API key fails fast, with a message telling you exactly which
key to set.

A couple of details that always draw questions. The Anthropic backend uses a plain
JDK `HttpClient` plus JSON-B — no SDK — deliberately, because `agentic-ai-core` is
an OSGi module in the server, and an SDK's transitive dependencies would cause
classloader clashes. Prompt caching: when there's a system prompt, it's sent with
`cache_control` ephemeral, so the stable prefix is reused across phases — though,
honestly, Claude only caches above a size threshold, silently. And if a call fails
mid-workflow, the just-added user turn is rolled back, so the history never has a
dangling question.

And here's the payoff for the whole talk: the agent code never names a provider. Our
quickstart runs on a free local Ollama model; the samples run on Claude. Switching
is one properties file — no code, no new dependency, no rebuild. For today's demo
I'm running on Claude via Vertex — the sonnet model — because a cloud model answers
in seconds, where a big local model on my laptop would time out. Let me show it
live.

---

## 🇧🇷 Script (ensaio / tradução de apoio)

Para onde vão, de fato, as chamadas ao modelo? A Payara divide isso em duas camadas.
A lógica da spec — substituição de placeholders, serialização JSON-B, histórico da
conversa — mora numa classe, `LargeLanguageModelImpl`, escrita uma vez. Embaixo, uma
SPI interna minúscula, `LlmBackend`: dá o system prompt e os turnos, recebe o texto.
Quatro implementações: um default no-op, Ollama para modelos locais, Anthropic para
Claude e Vertex para Claude no Google Cloud.

Qual delas você usa é pura MicroProfile Config — tudo sob `payara.agentic.llm`. E as
escolhas de robustez importam ao vivo: um provider desconhecido cai no no-op, então
um typo nunca derruba seu deploy. Mas Anthropic sem API key falha rápido, com uma
mensagem dizendo exatamente qual chave configurar.

Alguns detalhes que sempre puxam pergunta. O backend Anthropic usa um `HttpClient`
puro do JDK mais JSON-B — sem SDK — de propósito, porque o `agentic-ai-core` é um
módulo OSGi no servidor, e as dependências transitivas de um SDK causariam conflitos
de classloader. Prompt caching: quando há system prompt, ele vai com `cache_control`
ephemeral, então o prefixo estável é reaproveitado entre as fases — embora,
honestamente, o Claude só faça cache acima de um tamanho mínimo, silenciosamente. E
se uma chamada falha no meio do workflow, o turno de usuário recém-adicionado é
desfeito, então o histórico nunca fica com uma pergunta órfã.

E aqui está o retorno de toda a palestra: o código do agente nunca cita um provider.
Nosso quickstart roda num modelo Ollama local e grátis; os samples rodam no Claude.
Trocar é um arquivo de propriedades — sem código, sem dependência nova, sem rebuild.
Para a demo de hoje estou no Claude via Vertex — o modelo sonnet — porque um modelo
na nuvem responde em segundos, onde um modelo local grande no meu laptop daria
timeout. Deixa eu mostrar ao vivo.

---

➡️ Próximo: [Cap. 8 — Live demo: Course Content Studio](demo-roteiro-cap8.md)