# Capítulo 8 — Los samples

Tres samples, tres niveles: el **quickstart** enseña el modelo de programación en 5
clases; el **tutorial generator** muestra un caso de uso real con un bucle de
refinamiento por chat; y el **Course Content Studio** sube el listón hasta **dos
agentes encadenados por eventos CDI**, con una compuerta de aprobación humana en
medio y una vista final para el alumno.

### Dónde vive el código

Los tres viven ahora **dentro del repositorio de la especificación**, bajo
`examples/` — neutrales respecto al proveedor, dependiendo solo de
`jakarta.agentic-ai-api` y del paraguas de la plataforma Jakarta EE:

| Sample | Módulo | WAR / context root |
| --- | --- | --- |
| Quickstart | `examples/quickstart` | `quickstart.war` → `/quickstart` |
| Tutorial Generator | `examples/tutorial-generator` | `tutorial-generator.war` → `/tutorial-generator` |
| Course Content Studio | `examples/course-content-studio` | `course.war` → `/course` |

(`examples/` incluye además dos ilustraciones menores, no desplegables:
`fraud-detection` y `docs-agent`.)

Un **gemelo con sabor Payara** de los dos primeros vive en el árbol de Payara, en
`appserver/tests/payara-samples/samples/agentic-ai-quickstart` y
`.../samples/agentic-ai`. Los mismos agentes, distinto empaquetado: son los que
llevan las **pruebas de integración** Arquillian que se comentan más abajo, y
despliegan en `/agentic-ai-quickstart` y `/agentic-ai`. Todo lo de este capítulo vale
para ambos; las rutas citadas son las de `examples/`.

---

## Sample 1 — `examples/quickstart`

**El agente más pequeño posible que ejercita las cuatro fases.** Un POST REST
dispara un evento CDI; el agente responde la pregunta con el LLM configurado.

```
POST /quickstart/api/ask  { "question": "..." }  →  { "question", "answer" }
```

### El flujo completo, clase a clase

**`Question`** — un simple record, el **evento CDI** que dispara el workflow.
*Deliberadamente sin restricciones de validación*, para que una pregunta en blanco
llegue a la `@Decision` y demuestre la terminación anticipada.

**`AskResource`** (JAX-RS, `@RequestScoped`):

```java
@Inject Event<Question> trigger;
@Inject AnswerStore answers;

trigger.fire(question);            // ejecuta TODO el workflow de forma síncrona
String answer = answers.get(text); // lee el resultado producido por la @Action
```

El comentario del código es el alma de la demo: `Event.fire` es síncrono, así que el
workflow completo (incluida la llamada al LLM) termina **antes** de que `fire`
retorne.

**`QuestionAgent`** — las cuatro fases, cada una registrando su prefijo para que
`server.log` cuente la historia:

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

Fíjate en los detalles didácticos:

- **Sin anotación de ámbito** → el runtime aplica `@WorkflowScoped` (el valor por
  defecto de la especificación, vía la extensión).
- La `@Decision` usa el patrón **`Result`**: `Result(false, ...)` cuando la pregunta
  está en blanco → `@Action` y `@Outcome` **no se ejecutan** (la demo de terminación
  anticipada).
- La `@Action` usa un **placeholder `{}`** con un parámetro posicional.
- **`AnswerStore`** es `@ApplicationScoped` con un `ConcurrentHashMap` — el puente
  entre el agente y la respuesta HTTP síncrona.

### Configuración (Ollama local — demo sin coste)

```properties
payara.agentic.llm.provider=ollama
payara.agentic.llm.model=gemma3:4b
payara.agentic.llm.ollama.base-url=http://localhost:11434
```

### Guion de ejecución manual

1. `winget install Ollama.Ollama` y `ollama pull gemma3:4b`;
2. Asegúrate de que la distribución tiene el `agentic-ai-core` actual (empaquetar +
   copiar el JAR a `glassfish/modules/` + reiniciar limpiando la caché OSGi);
3. `mvn -pl examples/quickstart -am package` y
   `asadmin deploy examples/quickstart/target/quickstart.war`;
4. POST a `/quickstart/api/ask` y observa
   `[TRIGGER] → [DECISION] → [ACTION] → [OUTCOME]` en `server.log`;
5. Repite con `question` vacía → `[DECISION] proceed=false` y la respuesta
   "(no answer — workflow terminated...)".

### Prueba de integración

El gemelo Payara añade `AgenticQuickstartIT` (Arquillian), que **no necesita un LLM
vivo**: el despliegue incluye `StubLargeLanguageModel` y, por la regla del **LLM que
se auto-veta** (capítulo 5), el LLM de la aplicación gana al del runtime. La prueba
comprueba la respuesta guionizada y la terminación anticipada con una pregunta en
blanco. El módulo de `examples/` no trae pruebas — está orientado al despliegue.

---

## Sample 2 — `examples/tutorial-generator`

**Un caso de uso real:** un agente escribe una **guía campo a campo** de un
formulario web (un formulario de alta de cliente para contratar Azul Payara Server) y
permite **refinar la guía por chat**. La página muestra el formulario a la izquierda,
la guía generada a la derecha y un chat de refinamiento debajo.

```
GET  /tutorial-generator/                           la UI lado a lado
GET  /tutorial-generator/api/form                   los metadatos del formulario (FormSpec)
GET  /tutorial-generator/api/tutorial               la guía actual
POST /tutorial-generator/api/tutorial/generate      genera una guía nueva
POST /tutorial-generator/api/tutorial/refine        { "instruction": "..." } refina la guía entera
POST /tutorial-generator/api/tutorial/refine-field  refina UN campo y lo mezcla de vuelta
```

### Las ideas fuertes del diseño

1. **Fuente única de verdad:** `CustomerFormSpec` define el formulario; la página
   renderiza el formulario vivo a partir de él **y** el agente explica esos mismos
   campos — no pueden divergir.
2. **El evento lleva el modo:** `TutorialRequest(formSpec, instruction, currentHtml)`.
   `currentHtml` nulo/vacío → **generar** desde cero; relleno → **refinar** aplicando
   `instruction`. Un solo agente, dos comportamientos, decididos en la `@Action`.
3. **El refinamiento pasa el artefacto real:** en cada turno de chat, la guía
   **actual** + la instrucción van en el prompt — el modelo edita el artefacto de
   verdad en lugar de confiar solo en la memoria conversacional. (Un patrón importante
   de ingeniería de agentes que conviene recordar.)
4. **Refinamiento por campo con merge:** `refine-field` extrae solo la descripción del
   campo objetivo (JSON-P), ejecuta el workflow sobre ese fragmento y **mezcla** el
   resultado de vuelta en la guía completa — preservando los demás campos y ahorrando
   tokens.
5. **Robustez frente al LLM:** `stripCodeFences` elimina las vallas ``` que los
   modelos a veces se empeñan en añadir — un recordatorio honesto de que la salida de
   un LLM requiere posprocesado defensivo.

### El agente

```java
@Trigger  void onRequest(TutorialRequest request)      // registra generate|refine
@Decision Result hasFields(TutorialRequest request)    // ¿hay campos? si no, parar
@Action   void render(TutorialRequest request) {
    if (generar)  content = model.query("Generate the field-guide JSON ... : {}", request.formSpec());
    else          content = model.query("Current field-guide JSON:\n{}\n\nApply this change...: {}\n\n...",
                                        request.currentHtml(), request.instruction());
    store.put(stripCodeFences(content));
}
@Outcome  void complete(TutorialRequest request)       // registra el tamaño de la guía
```

Nota: el prompt de refinamiento usa **dos placeholders `{}`** — la guía actual y la
instrucción, sustituidos posicionalmente.

### Configuración (Vertex/Claude — calidad de la salida)

```properties
payara.agentic.llm.provider=vertex
payara.agentic.llm.model=claude-sonnet-4-6
payara.agentic.llm.max-tokens=8192
payara.agentic.llm.system=You are a senior technical writer...
```

El system prompt viene de la **configuración** (no del código) y se convierte en el
**prefijo del prompt caching** (capítulo 7). Para ejecutarlo 100 % en local: cambia a
`provider=ollama` / `model=gemma3:12b` (se recomienda un modelo de clase 12B para la
calidad de la salida). Para usar la API directa de Anthropic en vez de Vertex:
`provider=anthropic` + `payara.agentic.llm.anthropic.api-key` (o la variable de
entorno `ANTHROPIC_API_KEY`).

⚠️ **Trampa operativa:** la credencial que necesite el proveedor elegido debe estar en
el entorno **antes** de `asadmin restart-domain` — el proceso del servidor hereda el
entorno de quien lo arranca. Para Vertex eso significa Application Default Credentials
(`gcloud auth application-default login`) más `ANTHROPIC_VERTEX_PROJECT_ID` /
`CLOUD_ML_REGION`; para Anthropic, `ANTHROPIC_API_KEY`.

### Prueba de integración

El gemelo Payara añade `AgenticTutorialIT` — el mismo patrón que el quickstart:
`StubLargeLanguageModel` en el despliegue, sin LLM vivo. Comprueba que el formulario
se expone, que la guía se genera y que un refinamiento por chat produce un resultado
distinto.

---

## Sample 3 — `examples/course-content-studio` (dominio educativo)

**Un caso de uso avanzado:** el profesor pega el contenido de un capítulo y elige una
materia (matemáticas, física, inglés); un agente genera una **introducción, un
cuestionario y una conclusión**; el profesor **refina** por sección y, al **aprobar**,
un **segundo agente** construye la **lección publicada** que ve el alumno. Es el
sample que ejercita casi toda la especificación y muestra el diferencial
arquitectónico: **composición de agentes mediante eventos CDI**.

```
GET  /course/                           el studio (capítulo a la izquierda, paquete a la derecha, chat de refinado)
GET  /course/student.html               la lección publicada, tal como la ve un alumno
GET  /course/api/subjects               las materias disponibles (matemáticas, física, inglés)
GET  /course/api/packet                 el paquete actualmente en el studio
POST /course/api/packet/generate        genera intro + cuestionario + conclusión
POST /course/api/packet/refine          refina el paquete completo
POST /course/api/packet/refine-section  { section: intro|quiz|conclusion|all, instruction }
POST /course/api/packet/approve         aprueba + dispara el PublishAgent
GET  /course/api/lesson                 la lección publicada (vista del alumno)
POST /course/api/quiz/grade             corrige una respuesta abierta vía LLM (similitud)
GET  /course/api/progress/{runId}       progreso de fases en vivo (Server-Sent Events)
```

### Las ideas fuertes del diseño

1. **Dos agentes, encadenados solo por eventos CDI.** `CourseContentAgent`
   genera/refina el paquete; al aprobar, la capa REST dispara el evento
   `LessonApproved`, que es el **`@Trigger`** de un segundo `@Agent` (`PublishAgent`).
   No hay orquestador: la **aprobación humana** es la compuerta entre ambos. Cada
   agente tiene su propio workflow y su propia conversación con el LLM.
2. **Fases realmente ordenadas.** Las fases llevan un `order` explícito
   (`@Decision(order = 1)`, `@Action(order = 2/3/4)`), garantizando intro →
   cuestionario → conclusión. Recordatorio del cap. 5: si **una** fase va ordenada,
   **todas** deben ir, o el despliegue falla con "Inconsistent order".
3. **Estado por workflow en el propio agente.** Sin anotación de ámbito → el runtime
   aplica `@WorkflowScoped`, de modo que el campo de instancia `draft` acumula el
   paquete entre fases con seguridad (una instancia por ejecución).
4. **Memoria conversacional del workflow.** La conclusión **no** reenvía el capítulo:
   se apoya en los turnos anteriores (intro y cuestionario) de la conversación del
   mismo workflow.
5. **Resultado tipado vía JSON-B, con parseo defensivo.** El cuestionario se convierte
   en un record `Quiz`. Como los modelos pequeños envuelven el JSON en vallas ```,
   `parseQuiz` **quita las vallas y extrae el `{…}`** antes de hacer el binding, con un
   cuestionario de reserva como último recurso — el workflow nunca aborta por un JSON
   malo.
6. **HITL por sección.** El evento lleva el modo (`currentDraftJson` vacío → generar;
   relleno → refinar) y la `section`, de modo que `refine-section` reescribe **solo el
   cuestionario** (o solo la intro), preservando el resto.
7. **Progreso en vivo (SSE).** Como `Event.fire` es síncrono, cada fase reporta a un
   `ProgressTracker` que transmite por Server-Sent Events; el popup del navegador
   evoluciona con los pasos reales del agente.
8. **Cuestionario polimórfico + corrección por LLM.** `QuizQuestion` tiene un `type`
   (`multiple_choice` | `open`). En una pregunta **abierta** el alumno responde en un
   `<textarea>` y el endpoint `quiz/grade` pide al LLM una puntuación de **similitud
   semántica 0–100** frente a la `sampleAnswer`, mapeada en el servidor a un veredicto
   (≥70 correcta, 50–69 parcial, <50 incorrecta). Un detalle de la especificación que
   vale la pena citar: este endpoint **inyecta el `LargeLanguageModel` directamente**
   en un recurso `@RequestScoped` — funciona porque el LLM del runtime es
   `@Dependent` (no necesita un workflow activo), así que no todo uso del modelo tiene
   que ocurrir dentro de un agente.

### Los dos agentes

```java
// Agente 1 — autoría (ordenado, memoria de workflow, parseo defensivo)
@Agent(name = "CourseContentAgent")
class CourseContentAgent {
    private CoursePacket draft;                                   // estado @WorkflowScoped
    @Trigger  void onRequest(@Valid CoursePacketRequest r)        // generate | refine
    @Decision(order = 1)  boolean hasTeachableContent(...)        // compuerta
    @Action(order = 2)    void writeIntro(...)                    // prosa (rúbrica por materia)
    @Action(order = 3)    void writeQuiz(...)  { draft.setQuiz(parseQuiz(model.query(...))); }
    @Action(order = 4)    void writeConclusion(...)               // usa la memoria del workflow
    @Outcome  void publish(...)                                   // escribe en PacketStore
    @HandleException void onLlmFailure(LLMException e)            // resiliencia
    @HandleException void onInvalidRequest(ConstraintViolationException e)
}

// Agente 2 — publicación, disparado por LessonApproved (encadenado por evento)
@Agent(name = "PublishAgent")
class PublishAgent {
    @Trigger  void onApproved(LessonApproved e)                  // recibe el paquete aprobado
    @Decision boolean hasApprovedContent(...)
    @Action   void writeObjectives(...)                          // LLM: "qué vas a aprender"
    @Outcome  void publish(...)                                  // escribe en PublishedLessonStore
    @HandleException void onLlmFailure(LLMException e)
}
```

Los **dos** handlers de `CourseContentAgent` son la regla de selección de handler del
capítulo 2 llevada a producción: una caída del LLM y un fallo de Bean Validation en el
trigger son tipos de fallo distintos, cada uno con su handler más específico.

### Configuración (Vertex/Claude — nube, a prueba de demo)

```properties
payara.agentic.llm.provider=vertex
payara.agentic.llm.model=claude-sonnet-4-6
payara.agentic.llm.max-tokens=8192
payara.agentic.llm.system=You are an expert curriculum designer and teacher...
```

Vertex autentica vía ADC (`gcloud auth application-default login`) o
`GOOGLE_ACCESS_TOKEN`; el proyecto y la región vienen de
`ANTHROPIC_VERTEX_PROJECT_ID` / `CLOUD_ML_REGION`. **Por qué Vertex y no Ollama
aquí:** el backend de Ollama tiene un **timeout de 120 s por llamada**; un modelo 12B
en un portátil tarda ~2 min por llamada y el paso del cuestionario revienta el
límite. En la nube responde en segundos.

⚠️ Las fórmulas de matemáticas y física se renderizan con **MathJax autoalojado**
(`webapp/vendor/mathjax/tex-svg.js`) — la rúbrica pide LaTeX (`$...$`, `$$...$$`) y el
renderizado funciona **offline**.

### Detalles que merece la pena destacar

- La vista del alumno (`student.html`) muestra la lección con un **cuestionario
  interactivo** (responder y *Check answers* marca correcto/incorrecto + explicación
  en las preguntas de opción múltiple; en la pregunta abierta corrige por similitud vía
  LLM y revela la respuesta modelo) — el "producto final" que cierra la narrativa
  generar → revisar → **publicar** → consumir.
- Todo es de autor único (stores de una sola ranura), coherente con una demo en vivo.

---

## Test — Capítulo 8

**1.** En el quickstart, ¿por qué el record `Question` **no** tiene restricciones de
Bean Validation, aunque el `@Trigger` use `@Valid`?

<details><summary>Ver respuesta</summary>

Es intencionado y didáctico: sin restricciones, una **pregunta en blanco pasa el
trigger** y llega a la `@Decision`, que devuelve `Result(false, ...)` — demostrando la
**terminación anticipada** del workflow (la `@Action` nunca se ejecuta y la API
responde "(no answer ...)"). Con un `@NotBlank`, la violación se convertiría en una
`ConstraintViolationException` antes de la decisión y la demo mostraría otra
funcionalidad.
</details>

**2.** Traza el camino completo de un `POST /api/ask` con una pregunta válida hasta la
respuesta JSON, nombrando las clases implicadas.

<details><summary>Ver respuesta</summary>

`AskResource.ask` crea un `Question` y llama a `trigger.fire(question)` → el
**observador sintético** (registrado por la `AgenticAIExtension`) recibe el evento →
el `WorkflowEngine` activa el contexto, ejecuta `QuestionAgent.onQuestion`
(`@Trigger`), `hasContent` (`@Decision`, `Result(true, question)`), `generate`
(`@Action`, llama a `model.query(...)` sobre el backend de Ollama y escribe en el
`AnswerStore`), `complete` (`@Outcome`) y destruye el contexto → `fire` retorna →
`AskResource` lee `answers.get(text)` y devuelve `AskResponse(question, answer)`.
</details>

**3.** ¿Cómo decide el mismo `TutorialAgent` entre generar una guía nueva y refinar la
existente, sin dos agentes ni dos fases separadas?

<details><summary>Ver respuesta</summary>

**El evento lleva el modo**: un `TutorialRequest.currentHtml()` nulo/vacío significa
"generar desde cero"; uno relleno significa "refinar aplicando `instruction()`". La
`@Action render` lo inspecciona y construye el prompt apropiado — el de refinamiento
envía la guía actual y la instrucción con dos placeholders `{}`.
</details>

**4.** ¿Para qué sirve el endpoint `refine-field` y cómo evita que refinar un campo
arruine los demás?

<details><summary>Ver respuesta</summary>

Refina **un único campo**: extrae de la guía completa (JSON-P) solo la descripción del
campo solicitado, dispara el workflow sobre ese fragmento (menos tokens, más foco) y
después **mezcla** el valor actualizado de vuelta en el JSON completo (`mergeField`),
manteniendo intactas las descripciones de los demás campos.
</details>

**5.** Ambas pruebas de integración (quickstart y tutorial) se ejecutan sin ningún LLM
real. ¿Qué mecanismo de la implementación lo hace posible sin tocar el código de los
agentes?

<details><summary>Ver respuesta</summary>

El **LLM por defecto que se auto-veta** de la `AgenticAIExtension`: los despliegues de
prueba incluyen `StubLargeLanguageModel` (un bean de la aplicación que implementa
`LargeLanguageModel`); `watchForLlm` lo detecta y el runtime **no registra** su propio
LLM por defecto. El `@Inject LargeLanguageModel` de los agentes resuelve al stub —
mismo código, respuestas guionizadas, cero red.
</details>

**6.** Nombra dos medidas defensivas del tutorial generator frente al comportamiento
impredecible del LLM.

<details><summary>Ver respuesta</summary>

(a) `stripCodeFences` — elimina las vallas de código (```) que los modelos añaden
aunque se les diga que no; (b) el **refinamiento pasa el artefacto actual** de forma
explícita en el prompt, en lugar de confiar en la memoria conversacional — el modelo
edita el estado real. (Extra: el merge por campo de `refine-field` limita el radio de
daño de una mala respuesta a un solo campo.)
</details>

**7.** En el Course Content Studio, ¿cómo se comunican los **dos agentes** sin
orquestador, y qué dispara al segundo?

<details><summary>Ver respuesta</summary>

Mediante **eventos CDI**. `CourseContentAgent` escribe el paquete en su `@Outcome`;
cuando el profesor aprueba, `CourseResource` dispara el evento `LessonApproved`, que es
el **`@Trigger`** del segundo agente (`PublishAgent`). La **aprobación humana** es la
compuerta entre ambos — desacoplamiento total, sin código de orquestación. Como `fire`
es síncrono, la lección ya está publicada cuando `approve` retorna.
</details>

**8.** ¿Por qué `writeQuiz` hace **parseo defensivo** (quitar vallas y extraer el
`{…}`) en lugar de usar simplemente la sobrecarga tipada `query(prompt, Quiz.class)`?

<details><summary>Ver respuesta</summary>

Porque los modelos más pequeños tienden a envolver el JSON en vallas ```` ```json ````
o a añadir prosa alrededor; la sobrecarga tipada se lo pasa directamente a JSON-B, que
lanza una `LLMException` de deserialización. `parseQuiz` **quita las vallas, extrae el
objeto `{…}` y lo enlaza a `Quiz`**, con un cuestionario de reserva como último recurso
— así un cuestionario malo nunca aborta el workflow y las fases posteriores
(conclusión, outcome) siguen ejecutándose.
</details>

**9.** La `@Action` `writeConclusion` **no** reenvía el texto del capítulo en el
prompt. ¿Por qué produce igualmente una conclusión coherente?

<details><summary>Ver respuesta</summary>

Por la **memoria conversacional del workflow**: las llamadas `query()` anteriores
(intro y cuestionario) ocurrieron en la misma ejecución `@WorkflowScoped`, y la
implementación mantiene el estado conversacional aislado por workflow (cap. 6). La
conclusión se apoya en esos turnos previos — simplemente pide "atar la intro y el
cuestionario que acabas de producir".
</details>

**10.** Corregir una **pregunta abierta** llama al LLM fuera de cualquier agente, desde
un recurso `@RequestScoped`. ¿Por qué funciona, y cómo se decide el veredicto
(correcta/parcial/incorrecta)?

<details><summary>Ver respuesta</summary>

Funciona porque el `LargeLanguageModel` del runtime se registra como **`@Dependent`**
(el aislamiento por workflow proviene de la estructura `@Dependent` + el ámbito del
agente, no de un contexto de workflow activo). Inyectarlo en un recurso produce una
instancia nueva con la conversación vacía — perfecto para una corrección puntual. El
endpoint `quiz/grade` pide al LLM una puntuación de **similitud 0–100** entre la
respuesta del alumno y la `sampleAnswer`, y el **servidor** aplica los umbrales (≥70
correcta, 50–69 parcial, <50 incorrecta) — manteniendo la regla determinista fuera del
modelo.
</details>

---

➡️ Siguiente: [Capítulo 9 — Cierre: ejecutar los samples y FAQ](09-guia-presentacion.md)
