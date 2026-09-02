# Capítulo 3 — `LargeLanguageModel` y tratamiento de errores

## La fachada

`LargeLanguageModel` es la **única interfaz de la API** — una fachada minimalista,
inyectable vía CDI, para hablar con el modelo:

```java
public interface LargeLanguageModel {
    String query(String prompt);
    <T> T query(String prompt, Class<T> resultType);
    String query(String prompt, Object... parameters);
    <T> T query(String prompt, Class<T> resultType, Object... parameters);
    <T> T unwrap(Class<T> implClass);
}
```

Cuatro variantes de `query` que cubren los dos ejes: **con/sin parámetros
posicionales** × **respuesta String/tipada**. Más `unwrap`, siguiendo el patrón
`EntityManager.unwrap()` de Jakarta Persistence, para acceder a APIs específicas del
proveedor sin romper la portabilidad del resto del código.

## Las reglas del placeholder `{}`

El prompt acepta el token exacto `{}` como marcador **posicional** (inspirado en
SLF4J). Las reglas exactas — verificadas por el TCK:

1. Los parámetros se sustituyen **en orden de declaración**, cada uno serializado
   con **Jakarta JSON Binding**.
2. Si el prompt tiene N placeholders, se **deben** suministrar exactamente N
   parámetros. Un desajuste ⇒ `IllegalArgumentException`.
3. **La excepción a la regla:** un prompt **sin** placeholder puede recibir **como
   máximo un** parámetro — se envía al modelo como **contexto estructurado** (Payara
   lo añade como JSON en una línea nueva tras el prompt).
4. Solo el token exacto `{}` es un placeholder; cualquier otro uso de llaves
   (`{name}`, `{ }`) es texto literal del prompt.

```java
llm.query("Classify this event: {}", event);   // 1 placeholder, 1 parámetro ✔
llm.query("Classify this event", event);       // 0 placeholders, 1 contexto ✔
llm.query("Compare {} with {}", a);            // ✘ IllegalArgumentException
```

## Respuestas tipadas

```java
Sentiment s = llm.query("Return JSON {\"score\": ..., \"label\": ...} for: {}",
                        Sentiment.class, review);
```

La respuesta del LLM (que se espera en JSON) se **deserializa con JSON-B** al tipo
solicitado. Si la deserialización falla (el modelo devolvió texto suelto, JSON
truncado…), el error se convierte en **`LLMException`** — no en
`IllegalArgumentException`, porque la culpa está en la respuesta del servicio, no en
los argumentos de quien llama.

## Estado conversacional — la regla más importante

> Las implementaciones **deben mantener el estado conversacional del contexto de
> workflow actual** entre llamadas a `query`.

Es decir: dentro del mismo workflow, la segunda llamada a `query(...)` "recuerda" la
primera — el historial se acumula y se reenvía al modelo. Y los límites:

- **Agente `@WorkflowScoped`:** la conversación está ligada al contexto de workflow
  y **termina con él**.
- **Agente `@ApplicationScoped`:** el bean es uno solo, pero la conversación debe
  permanecer **aislada por contexto de workflow** — las ejecuciones concurrentes no
  pueden filtrarse historial entre sí.
- Las implementaciones deben ser **thread-safe dentro de un mismo workflow**.

### Escenario 1 — memoria entre fases del mismo workflow

La segunda `query` no necesita reenviar lo ya dicho: el historial acumulado viaja
con ella.

```java
@Agent
public class TriageAgent {

    @Inject
    private LargeLanguageModel llm;

    @Decision
    public boolean isRelevant(Ticket ticket) {
        // 1.er turno: el ticket entra aquí en la conversación
        String category = llm.query("Classify this ticket: {}", ticket);
        return !"SPAM".equals(category);
    }

    @Action
    public String draftReply(Ticket ticket) {
        // 2.º turno: el modelo "recuerda" el ticket clasificado arriba —
        // fíjate en que el prompt ni siquiera repite el contenido del ticket.
        return llm.query(
            "Write an initial reply for the ticket you just classified.");
    }
}
```

### Escenario 2 — `@WorkflowScoped`: la conversación muere con el workflow

Cada evento disparado crea un nuevo contexto de workflow — y una conversación
fresca. No hay memoria *entre* workflows:

```java
tickets.fire(new Ticket("A"));  // workflow 1: su propia conversación, descartada al final
tickets.fire(new Ticket("B"));  // workflow 2: empieza de cero — sin memoria del ticket A
```

Si el segundo prompt fuera `"Compare with the previous ticket"`, el modelo no
tendría forma de responder: el historial del workflow 1 ya no existe.

### Escenario 3 — `@ApplicationScoped`: un singleton, pero conversaciones aisladas

El bean es uno para toda la aplicación; el estado conversacional no — es **por
contexto de workflow**, incluso bajo concurrencia:

```java
@Agent
@ApplicationScoped
public class SupportAgent {

    @Inject
    private LargeLanguageModel llm;   // inyectado una vez en el singleton...

    @Decision
    public boolean needsHuman(CustomerMessage msg) {
        String mood = llm.query("What is this customer's mood? {}", msg);
        return "ANGRY".equals(mood);
    }

    @Action
    public String reply(CustomerMessage msg) {
        // ...pero cada workflow ve SOLO su propio historial: si se atiende a los
        // clientes X e Y en paralelo, el estado de ánimo detectado para X
        // nunca aparece en el prompt del workflow de Y.
        return llm.query("Reply in a tone that fits the mood you detected.");
    }
}
```

Este es exactamente el escenario de la pregunta 3 del test — y lo que el TCK exige
cuando obliga al aislamiento por workflow incluso en agentes `@ApplicationScoped`.

En la implementación de Payara esto sale "gratis" de la arquitectura:
`LargeLanguageModelImpl` mantiene la conversación como una lista de turnos
(`user`/`assistant`) y se registra como bean **`@Dependent`** — cada punto de
inyección/resolución dentro del workflow obtiene su propia instancia, y el motor
resuelve un LLM por ejecución de workflow (detalles en el capítulo 6). Un detalle
elegante: si la llamada al backend falla, el turno del usuario se **elimina** de la
conversación (un rollback), de modo que el historial nunca queda con una pregunta
sin respuesta.

## La jerarquía de errores

Dos clases de fallo, con culpables distintos:

| Excepción | Cuándo | Culpable |
| --- | --- | --- |
| `IllegalArgumentException` | prompt nulo, `resultType` nulo, número de placeholders incorrecto, parámetro no serializable a JSON | quien **llama** |
| `LLMException` (unchecked, extiende `RuntimeException`) | fallo de comunicación, rate limiting, timeout, modelo no disponible, respuesta malformada, fallo de **de**serialización de la respuesta | el **servicio LLM** |

`LLMException` es unchecked a propósito: no obliga a un try-catch en cada query, y
puede capturarse de forma centralizada con un método `@HandleException` del agente —
ese es el patrón idiomático de resiliencia:

```java
@HandleException
void llmDown(LLMException ex, Question q) {
    answers.put(q.text(), "Service unavailable, please try again later.");
    // retorna normalmente ⇒ el workflow avanza hasta el @Outcome
}
```

## Lo que la 1.0 NO estandariza (y por qué)

- **La selección y configuración del proveedor** (temperature, max tokens…) son
  específicas de la implementación en la 1.0. Payara usa MicroProfile Config con el
  prefijo `payara.agentic.llm.*` (capítulo 7).
- El plan declarado en el Javadoc: las versiones futuras estandarizarán la selección
  de proveedor y un conjunto común de propiedades — **el mismo modelo que Jakarta
  Persistence** (providers enchufables + propiedades comunes + `unwrap` para el
  resto).
- Streaming, tools/function calling, embeddings: fuera del alcance de la 1.0.

---

## Test — Capítulo 3

**1.** `llm.query("Summarize the order", order, customer)` — el prompt no tiene `{}`
y se pasaron dos parámetros. ¿Qué ocurre?

<details><summary>Ver respuesta</summary>

**`IllegalArgumentException`**. Un prompt sin placeholders acepta **como máximo un**
parámetro (enviado como contexto estructurado). Dos o más parámetros sin
placeholders es un error de quien llama.
</details>

**2.** El LLM responde `"Sure! Here is the JSON: {...}"` a una llamada
`query(prompt, Invoice.class)` y la deserialización JSON-B falla. ¿Qué excepción se
lanza, y por qué esa (y no la otra)?

<details><summary>Ver respuesta</summary>

**`LLMException`**. El fallo está en la **respuesta del servicio** (el modelo no
devolvió JSON puro), no en los argumentos de quien llama.
`IllegalArgumentException` queda reservada para errores de entrada (prompt nulo,
número de placeholders, parámetro no serializable).
</details>

**3.** Un agente `@ApplicationScoped` atiende dos eventos simultáneamente, y cada
workflow hace dos llamadas `query`. ¿Qué garantiza la especificación sobre el
historial conversacional?

<details><summary>Ver respuesta</summary>

Cada workflow tiene su **propia conversación aislada**: la segunda llamada de cada
workflow ve solo el historial de ese workflow. Aunque el agente sea un singleton de
toda la aplicación, el estado conversacional es **por contexto de workflow** y no
puede filtrarse entre ejecuciones concurrentes. La conversación termina cuando
termina el contexto de workflow.
</details>

**4.** En el prompt `"Generate the guide for form {name} using {}"`, ¿cuántos
placeholders reconoce la especificación?

<details><summary>Ver respuesta</summary>

**Uno** — solo el token exacto `{}`. El `{name}` es texto literal del prompt (no hay
placeholders con nombre). Por tanto debe suministrarse exactamente un parámetro.
</details>

**5.** ¿Para qué sirve `unwrap(Class<T>)` y qué API existente de la plataforma lo
inspiró?

<details><summary>Ver respuesta</summary>

Da acceso a la **implementación de LLM subyacente** para usar funcionalidades
específicas del proveedor que la fachada no expone (en Payara, por ejemplo, el
backend concreto). Inspirado en `EntityManager.unwrap()` de **Jakarta Persistence**.
Si el tipo solicitado no es compatible, lanza `IllegalArgumentException`.
</details>

---

➡️ Siguiente: [Capítulo 4 — El TCK](04-tck.md)
