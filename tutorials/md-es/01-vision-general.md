# Capítulo 1 — Visión general y motivación

## Qué es Jakarta Agentic AI

**Jakarta Agentic AI** es una especificación de Jakarta EE (paquete
`jakarta.ai.agent`) para construir **agentes de IA** de forma neutral respecto al
proveedor. Un agente es un **bean CDI** que encapsula un comportamiento autónomo y
orientado a objetivos: **percibe** un evento, **razona** (típicamente consultando
un LLM), **decide** si continuar y cómo, y **actúa** — todo dentro de un workflow
con fases bien definidas.

Una analogía útil: así como Jakarta Persistence estandarizó el acceso a datos
relacionales (programas contra `EntityManager`, y Hibernate o EclipseLink lo
implementan), Jakarta Agentic AI estandariza la construcción de agentes —
programas contra anotaciones y la interfaz `LargeLanguageModel`, y el servidor de
aplicaciones (Payara, en nuestro caso) aporta el motor de orquestación y la
integración con el proveedor de LLM.

## ¿Por qué una especificación para agentes?

Hoy cada framework de IA en Java (LangChain4j, Spring AI, etc.) tiene su propio
modelo de programación. Los problemas que ataca la especificación:

1. **Vendor lock-in** — cambiar de proveedor de LLM o de framework obliga a
   reescribir el código del agente. Con la especificación, elegir proveedor es
   configuración del servidor (en Payara, vía MicroProfile Config), no código.
2. **Sin integración con el contenedor** — los agentes necesitan inyección de
   dependencias, ámbitos, eventos, validación, transacciones. En lugar de
   reinventar todo eso, la especificación **se apoya en CDI**: el trigger es un
   observador de eventos CDI, el agente es un bean, el ámbito de workflow es un
   ámbito CDI propio.
3. **Workflows ad-hoc** — sin un modelo de fases, cada aplicación inventa su
   propia máquina de estados. La especificación define un ciclo de vida estándar:
   `Trigger → Decision* → Action* → Outcome`, con `HandleException` de forma
   transversal.

### Un ejemplo práctico: cambiar GPT (OpenAI) por Claude (Anthropic)

El mismo requisito — "a partir de hoy usamos Claude" — resuelto en los tres mundos.

**Con LangChain4j**, la elección del proveedor está *compilada dentro* de la
aplicación: la clase de cableado y la dependencia Maven son específicas del
proveedor.

```java
// ANTES — pom.xml: dev.langchain4j:langchain4j-open-ai
ChatModel model = OpenAiChatModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-4o")
        .build();
```

```java
// DESPUÉS — cambiar la dependencia del pom.xml a dev.langchain4j:langchain4j-anthropic,
// reescribir el cableado y recompilar/reempaquetar la aplicación
ChatModel model = AnthropicChatModel.builder()
        .apiKey(System.getenv("ANTHROPIC_API_KEY"))
        .modelName("claude-opus-4-8")
        .build();
```

El código que *usa* la interfaz `ChatModel` puede sobrevivir — pero el cambio sigue
exigiendo tocar una dependencia y el código de construcción, recompilar y
redesplegar. Y la abstracción pertenece a una biblioteca de un solo proveedor, no a
un estándar.

**Con Spring AI**, hay que cambiar el starter en el `pom.xml`
(`spring-ai-starter-model-openai` → `spring-ai-starter-model-anthropic`) y migrar
el bloque de propiedades (`spring.ai.openai.*` → `spring.ai.anthropic.*`). El
código que inyecta `ChatClient` puede quedarse igual — justo es reconocerlo — pero
sigue siendo una recompilación de la aplicación, la abstracción solo existe dentro
de Spring, y hay una única implementación de ella, sin especificación ni TCK que
garanticen un comportamiento portable.

**Con Jakarta Agentic AI**, el agente inyecta la interfaz de la **plataforma** y no
menciona proveedor alguno:

```java
@Agent
public class QuestionAgent {

    @Inject
    LargeLanguageModel model;   // jakarta.ai.agent — aquí no hay proveedor

    @Action
    void generate(Question question, AnswerStore answers) {
        String answer = model.query("Answer concisely: {}", question.text());
        answers.put(question.text(), answer);
    }
}
```

Todo el cambio es **un único archivo de configuración** (en Payara, MicroProfile
Config):

```properties
# ANTES                                    # DESPUÉS
payara.agentic.llm.provider=ollama         payara.agentic.llm.provider=anthropic
payara.agentic.llm.model=gemma3:4b         payara.agentic.llm.model=claude-opus-4-8
```

Cero cambios de código, cero cambios en el `pom.xml` (el backend HTTP del proveedor
vive en el **servidor**, no en el WAR), cero recompilación — como mucho un
redespliegue. Es el mismo salto que dio Jakarta Persistence sobre el JDBC artesanal:
el proveedor se convirtió en un detalle de configuración. Los tres samples del
capítulo 8 lo demuestran en vivo: sus agentes están escritos igual, uno corriendo
sobre Ollama local y los otros dos sobre Claude, y la única diferencia entre ellos
es `microprofile-config.properties`.

## El modelo mental: un workflow de fases

```
  evento CDI
       │
       ▼
  ┌─────────┐    ┌───────────┐    ┌─────────┐    ┌──────────┐
  │ @Trigger │──▶│ @Decision │──▶│ @Action │──▶│ @Outcome │
  └─────────┘    └───────────┘    └─────────┘    └──────────┘
   (obligatorio,  (0..N, puede    (0..N)         (0..1, void,
    exactamente 1) detener todo)                  cierra el contexto)

           @HandleException (0..N) captura excepciones de CUALQUIER fase
```

Puntos clave:

- **Trigger** es la única fase obligatoria — exactamente **un** método por agente
  en la versión 1.0 (una restricción que se espera relajar en el futuro).
- **Decisiones y acciones pueden intercalarse** en cualquier secuencia, cubriendo
  desde `Trigger + Action` (ejecución simple) hasta
  `Trigger + Decision + Action + Decision + Action` (ramificación compleja).
- Una **Decision puede terminar el workflow** (devolviendo `false`, `null` o
  `Result(false, ...)`) — las fases restantes y el Outcome **no** se ejecutan.
- **Outcome** marca el fin exitoso del workflow; después de él, el contenedor
  destruye el contexto de workflow.
- **Los datos fluyen entre fases por tipo**: lo que devuelve una fase queda
  disponible como parámetro de las fases posteriores (inyección basada en tipos,
  sin pasar parámetros a mano).

## La arquitectura del repositorio de la especificación

El proyecto es una build Maven multimódulo con cuatro módulos:

| Módulo | Contenido |
| --- | --- |
| `api/` | El paquete `jakarta.ai.agent`: 7 anotaciones, 1 interfaz (`LargeLanguageModel`), 1 record (`Result`), 1 excepción (`LLMException`). **Sin código de implementación.** |
| `spec/` | El documento de la especificación en AsciiDoc (`jakarta-agentic-ai.adoc`). |
| `tck/` | El Technology Compatibility Kit — las pruebas que cualquier implementación debe pasar para declararse compatible (capítulo 4). |
| `examples/` | Cinco ejemplos ejecutables: `quickstart`, `tutorial-generator`, `course-content-studio`, `fraud-detection`, `docs-agent` (capítulo 8). |

## Decisiones de diseño fundamentales

Estas son las decisiones que más preguntas generan — aquí va la justificación:

1. **CDI primero.** El agente es un bean CDI; el trigger se dispara con eventos CDI
   (`Event.fire(...)`). Versiones futuras podrán añadir otras fuentes (Jakarta
   Messaging, REST, invocación programática), pero la 1.0 es CDI puro. Esto te da
   gratis: inyección, interceptores, eventos, ámbitos.
2. **Jakarta JSON Binding (JSON-B) para la serialización** — no Jackson. Motivo:
   comportamiento **portable y consistente** entre implementaciones; JSON-B ya
   forma parte de la plataforma Jakarta EE.
3. **Baseline: Java 17, Jakarta EE 10 (por tanto CDI 4.0).** Declarado una sola vez
   en el `pom.xml` raíz (`maven.compiler.release`, `jakarta.ee.version`) y
   garantizado por el plugin Maven Enforcer.
4. **La fachada `LargeLanguageModel` es minimalista a propósito.** En la 1.0 cada
   implementación elige cómo configurar el proveedor. Versiones futuras
   estandarizarán la selección de proveedor y los ajustes comunes (temperature, max
   tokens) — el mismo camino evolutivo que recorrió Jakarta Persistence con sus
   providers.
5. **Estado conversacional por workflow.** Incluso con un agente
   `@ApplicationScoped`, la conversación con el LLM queda aislada por ejecución de
   workflow — dos ejecuciones concurrentes nunca mezclan su historial.

## Reparto de responsabilidades: especificación × implementación

Una sutileza importante (aparece en el TCK): **CDI puro puede invocar el
`@Trigger`** (no es más que un observador de eventos), pero las fases `@Decision`,
`@Action` y `@Outcome` requieren un **motor de orquestación** — que es justo lo que
aporta la implementación de Payara (`agentic-ai-core`). El TCK usa las condiciones
`@RequiresImplementation` / `@RequiresNoImplementation` para separar lo que es
verificable con CDI puro de lo que necesita una implementación compatible.

---

## Test — Capítulo 1

**1.** ¿Qué fase del workflow es obligatoria y cuántos métodos de esa fase puede
declarar un agente en la versión 1.0?

<details><summary>Ver respuesta</summary>

`@Trigger` es la única fase obligatoria, y el agente debe declarar **exactamente
un** método `@Trigger`. Más de uno (o ninguno) es un error de despliegue
(`DefinitionException` en la implementación de Payara). Se espera relajar la
restricción de un solo trigger en versiones futuras para admitir varios puntos de
entrada.
</details>

**2.** ¿Por qué la especificación exige Jakarta JSON Binding en lugar de dejar que
cada implementación elija su propia biblioteca de serialización (por ejemplo,
Jackson)?

<details><summary>Ver respuesta</summary>

Para garantizar un **comportamiento portable y consistente** entre
implementaciones: el mismo objeto de dominio debe producir la misma forma
serializada en el prompt en cualquier implementación compatible. Además, JSON-B ya
es una especificación de la plataforma Jakarta EE, así que no añade ninguna
dependencia externa.
</details>

**3.** ¿Qué le ocurre al resto del workflow cuando un método `@Decision` devuelve
`false`?

<details><summary>Ver respuesta</summary>

El workflow **termina inmediatamente**: las fases `@Decision`/`@Action` restantes y
el `@Outcome` **no** se ejecutan. Esto no es un error — es una terminación
anticipada normal. El contexto `@WorkflowScoped` se destruye con normalidad.
</details>

**4.** Un compañero pregunta: "si el agente es un bean CDI corriente, ¿para qué
necesito la implementación de Payara? ¿No puede Weld solo ejecutar el agente?" ¿Qué
le respondes?

<details><summary>Ver respuesta</summary>

CDI puro solo puede **disparar el `@Trigger`** — que es en esencia un observador de
eventos CDI. Pero orquestar las fases siguientes (`@Decision`, `@Action`,
`@Outcome`), propagar datos entre ellas por tipo, aplicar las reglas de terminación
y despachar excepciones a `@HandleException` requiere un **motor de orquestación**,
que es exactamente lo que aporta la implementación compatible (`agentic-ai-core` de
Payara) mediante una extensión CDI portable.
</details>

**5.** Nombra los cuatro módulos Maven del repositorio de la especificación y el
papel de cada uno.

<details><summary>Ver respuesta</summary>

- `api` — los tipos de `jakarta.ai.agent` (anotaciones + interfaces, sin
  implementación);
- `spec` — el documento de la especificación en AsciiDoc;
- `tck` — las pruebas de compatibilidad que las implementaciones deben pasar;
- `examples` — ejemplos de uso de la API.
</details>

---

➡️ Siguiente: [Capítulo 2 — El modelo de programación](02-modelo-de-programacion.md)
