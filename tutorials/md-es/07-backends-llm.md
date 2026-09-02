# Capítulo 7 — Backends LLM y configuración

## La arquitectura en dos capas

El LLM de Payara separa el **contrato de la especificación** del **transporte del
proveedor**:

```
LargeLanguageModel (spec)
        ▲
        │ implementa
LargeLanguageModelImpl ── placeholders {}, JSON-B, historial conversacional
        │ delega
        ▼
LlmBackend (SPI interno)  ──  chat(systemPrompt, List<Turn>) → String
   ├── NoOpLlmBackend        (por defecto; sin proveedor configurado)
   ├── OllamaLlmBackend      (modelos locales, p. ej. gemma3)
   ├── AnthropicLlmBackend   (Claude vía la Messages API)
   └── VertexLlmBackend      (Claude vía Google Vertex AI)
```

`LlmBackend` es una interfaz mínima: recibe el system prompt y la conversación (una
lista de `Turn(role, content)`) y devuelve el texto de la respuesta. Toda la lógica
de la especificación (placeholders, serialización, estado) vive en
`LargeLanguageModelImpl`, escrita **una sola vez** para todos los proveedores.

## `LargeLanguageModelImpl` — lo que merece la pena destacar

- **Placeholders:** la regex `\{\}`; cuenta los placeholders y valida contra el
  número de parámetros (las reglas exactas del capítulo 3, incluido el caso "0
  placeholders + 1 contexto", que añade el JSON en una línea nueva tras el prompt).
- **Serialización:** un `String` pasa tal cual; cualquier otro objeto se convierte en
  JSON vía JSON-B; un fallo de serialización ⇒ `IllegalArgumentException`.
- **Conversación:** cada `query` añade un turno `user`, llama al backend con una
  **copia inmutable** de toda la conversación, y añade el turno `assistant` con la
  respuesta. Si el backend falla, el turno `user` se **elimina** (un rollback) — el
  historial nunca queda con una pregunta huérfana.
- **`unwrap`:** expone el backend concreto (p. ej.
  `llm.unwrap(AnthropicLlmBackend.class)`).

## `LlmBackendFactory` — selección vía MicroProfile Config

Todas las claves viven bajo el prefijo **`payara.agentic.llm.`**:

| Clave | Valores / valor por defecto |
| --- | --- |
| `provider` | `none` (por defecto → NoOp), `ollama`, `anthropic` (alias `claude`), `vertex` |
| `model` | por defecto en Ollama `gemma`; en Anthropic/Vertex `claude-opus-4-8` |
| `ollama.base-url` | `http://localhost:11434` |
| `anthropic.base-url` | `https://api.anthropic.com` |
| `anthropic.api-key` | o la variable de entorno `ANTHROPIC_API_KEY` |
| `vertex.project-id` | o la variable `ANTHROPIC_VERTEX_PROJECT_ID` (obligatoria) |
| `vertex.region` | o la variable `CLOUD_ML_REGION`; por defecto `global` |
| `max-tokens` | `4096` |
| `system` | system prompt opcional (también usado como prefijo de caché) |

Decisiones de robustez:

- **Proveedor desconocido → NoOp**, nunca un despliegue fallido: el contenedor
  siempre resuelve un `LargeLanguageModel` sin ambigüedad.
- **Anthropic sin API key → `IllegalStateException`** con un mensaje que dice
  exactamente qué clave/variable configurar (fallo rápido con diagnóstico).
- Como es MicroProfile Config, la configuración puede venir del
  `META-INF/microprofile-config.properties` de la aplicación, de system properties o
  de variables de entorno — así es como **cada sample elige su proveedor sin
  código**.

## `AnthropicLlmBackend` — los detalles que atraen preguntas

- **Sin SDK**: `java.net.http.HttpClient` a pelo + JSON-B. Motivo: evitar
  **conflictos de dependencias en el OSGi del servidor** (el runtime de Payara es un
  módulo OSGi; arrastrar un SDK con sus dependencias transitivas al módulo es pedir
  choques de classloader).
- Habla la **Messages API** (`POST /v1/messages`, cabecera `anthropic-version:
  2023-06-01`, autenticación con `x-api-key`).
- **Prompt caching:** cuando hay system prompt, se envía como un único bloque de
  texto con `cache_control: {"type": "ephemeral"}` — el prefijo estable se reutiliza
  entre las fases del workflow (cada fase reenvía la conversación; el system prompt
  cacheado reduce coste y latencia). Un matiz honesto: Claude solo cachea prefijos
  por encima de un tamaño mínimo (~4096 tokens en Opus); los prompts más cortos
  simplemente no se cachean — **en silencio**, no es un error.
- **Sin streaming**, apropiado para los `max_tokens` modestos de las fases de un
  agente; valores de `max_tokens` muy grandes exigirían streaming para no agotar el
  timeout HTTP (120 s).
- Un system prompt **por llamada** (si lo hay) tiene precedencia sobre el
  configurado por defecto.

## Los demás backends

- **`NoOpLlmBackend`** — devuelve una respuesta fija/inerte; garantiza que inyectar
  `LargeLanguageModel` funcione incluso sin proveedor configurado (y es lo que
  muestra el quickstart cuando responde "(no answer — ... LLM provider is 'none')").
- **`OllamaLlmBackend`** — HTTP contra el servidor Ollama local; la opción **sin API
  key y sin coste** para demos offline (el quickstart usa `gemma3:4b`).
- **`VertexLlmBackend`** — Claude servido por Google Vertex AI: misma familia de
  modelos, autenticación/facturación de GCP (project-id + región en lugar de una API
  key).

---

## Test — Capítulo 7

**1.** ¿Por qué se escribió el `AnthropicLlmBackend` con un `HttpClient` pelado en
lugar del SDK oficial de Anthropic?

<details><summary>Ver respuesta</summary>

Porque `agentic-ai-core` se ejecuta como **módulo OSGi dentro del servidor Payara** —
un SDK traería dependencias transitivas que provocan conflictos de
classloader/versión en OSGi. Con `java.net.http.HttpClient` (del JDK) + JSON-B (ya en
la plataforma), el módulo no arrastra nada externo.
</details>

**2.** ¿Qué ocurre en el despliegue si `payara.agentic.llm.provider=banana` (un valor
desconocido)? ¿Y con `provider=anthropic` pero sin API key?

<details><summary>Ver respuesta</summary>

Un proveedor desconocido → cae al **`NoOpLlmBackend`** (el `switch` tiene un
default): el despliegue funciona e inyectar `LargeLanguageModel` resuelve sin
ambigüedad. `anthropic` sin `anthropic.api-key` y sin la variable de entorno
`ANTHROPIC_API_KEY` → una **`IllegalStateException`** inmediata con un mensaje que
indica exactamente qué configurar (fallo rápido).
</details>

**3.** ¿Cómo se comporta el historial conversacional cuando una llamada al backend
falla en mitad del workflow?

<details><summary>Ver respuesta</summary>

El turno `user` recién añadido se **elimina** de la conversación (un rollback) antes
de que la excepción se propague. Así el historial nunca queda con una pregunta sin
respuesta, y un nuevo intento de `query` reconstruye la conversación en un estado
consistente.
</details>

**4.** Explica qué optimiza el `cache_control: ephemeral` sobre el system prompt en
el contexto de un workflow de agente — y la limitación que lo vuelve "silencioso".

<details><summary>Ver respuesta</summary>

Cada fase del workflow reenvía la conversación completa a la Messages API; marcar el
system prompt (el prefijo estable) con `cache_control` permite a Claude **reutilizar
el prefijo cacheado** entre las llamadas, reduciendo coste y latencia. Limitación: la
caché solo aplica a prefijos por encima de un tamaño mínimo (~4096 tokens en Opus) —
los system prompts más pequeños no se cachean, y eso no produce ningún error ni aviso.
</details>

**5.** ¿De dónde sacan el quickstart y el tutorial generator su configuración de LLM,
y por qué eso demuestra tan bien el valor de la especificación?

<details><summary>Ver respuesta</summary>

Del `META-INF/microprofile-config.properties` de cada WAR (quickstart: Ollama +
`gemma3:4b`; tutorial generator y course content studio: Vertex + `claude-sonnet-4-6`
+ un system prompt). El **código de los agentes es idéntico en estilo y no menciona
ningún proveedor** — pasar de un modelo local gratuito a Claude en la nube es editar
un archivo de propiedades. Neutralidad de proveedor en la práctica.
</details>

---

➡️ Siguiente: [Capítulo 8 — Los samples](08-samples.md)
