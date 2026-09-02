# Capítulo 7 — Backends LLM e configuração

## A arquitetura em duas camadas

O LLM da Payara separa **contrato da spec** de **transporte do provedor**:

```
LargeLanguageModel (spec)
        ▲
        │ implementa
LargeLanguageModelImpl ── placeholders {}, JSON-B, histórico conversacional
        │ delega
        ▼
LlmBackend (SPI interna)  ──  chat(systemPrompt, List<Turn>) → String
   ├── NoOpLlmBackend        (default; sem provedor configurado)
   ├── OllamaLlmBackend      (modelos locais, ex.: gemma3)
   ├── AnthropicLlmBackend   (Claude via Messages API)
   └── VertexLlmBackend      (Claude via Google Vertex AI)
```

`LlmBackend` é uma interface mínima: recebe o prompt de sistema e a conversa
(lista de `Turn(role, content)`) e devolve o texto da resposta. Toda a lógica da
spec (placeholders, serialização, estado) fica em `LargeLanguageModelImpl`, escrita
**uma vez** para todos os provedores.

## `LargeLanguageModelImpl` — o que vale destacar

- **Placeholders:** regex `\{\}`; conta os placeholders e valida contra o número de
  parâmetros (as regras exatas do capítulo 3, incluindo o caso "0 placeholders +
  1 contexto", que anexa o JSON após o prompt em nova linha).
- **Serialização:** `String` passa direto; qualquer outro objeto vira JSON via
  JSON-B; falha de serialização ⇒ `IllegalArgumentException`.
- **Conversa:** cada `query` adiciona um turno `user`, chama o backend com a
  **cópia imutável** da conversa inteira, e adiciona o turno `assistant` com a
  resposta. Se o backend falha, o turno `user` é **removido** (rollback) — o
  histórico nunca fica com pergunta órfã.
- **`unwrap`:** expõe o backend concreto (ex.:
  `llm.unwrap(AnthropicLlmBackend.class)`).

## `LlmBackendFactory` — seleção por MicroProfile Config

Todas as chaves sob o prefixo **`payara.agentic.llm.`**:

| Chave | Valores / default |
| --- | --- |
| `provider` | `none` (default → NoOp), `ollama`, `anthropic` (alias `claude`), `vertex` |
| `model` | Ollama default `gemma`; Anthropic/Vertex default `claude-opus-4-8` |
| `ollama.base-url` | `http://localhost:11434` |
| `anthropic.base-url` | `https://api.anthropic.com` |
| `anthropic.api-key` | ou a env var `ANTHROPIC_API_KEY` |
| `vertex.project-id` | ou env var `ANTHROPIC_VERTEX_PROJECT_ID` (obrigatório) |
| `vertex.region` | ou env var `CLOUD_ML_REGION`; default `global` |
| `max-tokens` | `4096` |
| `system` | prompt de sistema opcional (também usado como prefixo de cache) |

Decisões de robustez:

- **Provider desconhecido → NoOp**, nunca falha o deploy: o container sempre
  resolve um `LargeLanguageModel` sem ambiguidade.
- **Anthropic sem API key → `IllegalStateException`** com mensagem dizendo
  exatamente qual chave/env var configurar (fail fast com diagnóstico).
- Como é MicroProfile Config, a configuração pode vir de
  `META-INF/microprofile-config.properties` da aplicação, de system properties ou
  de variáveis de ambiente — é assim que **cada sample escolhe seu provedor sem
  código**.

## `AnthropicLlmBackend` — os detalhes que rendem perguntas

- **Sem SDK**: usa `java.net.http.HttpClient` cru + JSON-B. Motivo: evitar
  **conflitos de dependência no OSGi** do servidor (o runtime da Payara é um módulo
  OSGi; arrastar um SDK com suas dependências transitivas para dentro do módulo é
  pedir choque de classloader).
- Fala a **Messages API** (`POST /v1/messages`, header `anthropic-version:
  2023-06-01`, autenticação `x-api-key`).
- **Prompt caching:** quando há prompt de sistema, ele é enviado como bloco de
  texto único com `cache_control: {"type": "ephemeral"}` — o prefixo estável é
  reutilizado entre as fases do workflow (cada fase re-envia a conversa; o sistema
  cacheado reduz custo/latência). Nuance honesta para a palestra: o Claude só
  cacheia prefixos acima do tamanho mínimo (~4096 tokens no Opus); prompts menores
  simplesmente não cacheiam — **silenciosamente**, não é erro.
- **Não-streaming**, adequado aos `max_tokens` modestos das fases de agente;
  `max_tokens` muito grandes exigiriam streaming para não estourar o timeout HTTP
  (120s).
- O prompt de sistema **por chamada** (se houver) tem precedência sobre o default
  configurado.

## Os outros backends

- **`NoOpLlmBackend`** — devolve uma resposta fixa/inerte; garante que injetar
  `LargeLanguageModel` funcione mesmo sem provedor configurado (e é o que o
  quickstart mostra quando responde "(no answer — ... LLM provider is 'none')").
- **`OllamaLlmBackend`** — HTTP para o servidor local do Ollama; a opção
  **sem API key e sem custo** para demos offline (o quickstart usa `gemma3:4b`).
- **`VertexLlmBackend`** — Claude servido pelo Google Vertex AI: mesma família de
  modelos, autenticação/billing do GCP (project-id + region em vez de API key).

---

## Quiz — Capítulo 7

**1.** Por que o `AnthropicLlmBackend` foi escrito com `HttpClient` puro em vez de
usar o SDK oficial da Anthropic?

<details><summary>Ver resposta</summary>

Porque o `agentic-ai-core` roda como **módulo OSGi dentro do servidor Payara** — um
SDK traria dependências transitivas que causam conflitos de classloader/versão no
OSGi. Com `java.net.http.HttpClient` (JDK) + JSON-B (já na plataforma), o módulo
não arrasta nada externo.
</details>

**2.** O que acontece no deploy se `payara.agentic.llm.provider=banana` (um valor
desconhecido)? E se `provider=anthropic` sem API key?

<details><summary>Ver resposta</summary>

Provider desconhecido → cai no **`NoOpLlmBackend`** (o `switch` tem default): o
deploy funciona e a injeção de `LargeLanguageModel` resolve sem ambiguidade.
`anthropic` sem `anthropic.api-key` e sem a env var `ANTHROPIC_API_KEY` →
**`IllegalStateException`** imediata, com mensagem indicando exatamente o que
configurar (fail fast).
</details>

**3.** Como o histórico conversacional se comporta quando uma chamada ao backend
falha no meio do workflow?

<details><summary>Ver resposta</summary>

O turno `user` recém-adicionado é **removido** da conversa (rollback) antes de a
exceção propagar. Assim o histórico nunca fica com uma pergunta sem resposta, e uma
nova tentativa de `query` reconstrói a conversa num estado consistente.
</details>

**4.** Explique o que o `cache_control: ephemeral` no prompt de sistema otimiza no
contexto de um workflow de agente — e a limitação que torna isso "silencioso".

<details><summary>Ver resposta</summary>

Cada fase do workflow re-envia a conversa completa à Messages API; marcar o prompt
de sistema (prefixo estável) com `cache_control` permite que o Claude **reutilize o
prefixo cacheado** entre as chamadas, reduzindo custo e latência. Limitação: o
cache só se aplica a prefixos com tamanho mínimo (~4096 tokens no Opus) — prompts
de sistema menores não cacheiam, e isso não gera erro nem aviso.
</details>

**5.** De onde o quickstart e o tutorial generator tiram suas configurações de LLM,
e por que isso demonstra bem o valor da spec?

<details><summary>Ver resposta</summary>

Do `META-INF/microprofile-config.properties` de cada WAR (quickstart: Ollama +
`gemma3:4b`; tutorial generator e course content studio: Vertex +
`claude-sonnet-4-6` + system prompt). O **código
dos agentes é idêntico em estilo e não menciona provedor nenhum** — trocar de um
modelo local gratuito para Claude na nuvem é editar um arquivo de propriedades.
Vendor-neutralidade na prática.
</details>

---

➡️ Próximo: [Capítulo 8 — Os samples](08-samples.md)
