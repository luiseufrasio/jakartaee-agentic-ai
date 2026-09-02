# Capítulo 2 — El modelo de programación (las anotaciones)

Este capítulo recorre cada tipo del paquete `jakarta.ai.agent` con las reglas
exactas de la especificación — incluidas las sutilezas que suelen hacer tropezar.

## `@Agent` — declarar el agente

```java
@Agent(name = "QuestionAgent", description = "Answers a question using the configured LLM.")
public class QuestionAgent { /* ... */ }
```

- Anotación a **nivel de clase** (`@Target(TYPE)`), retención en runtime.
- `name` por defecto: el nombre simple de la clase con la primera letra en
  minúscula (`MyAgent` → `myAgent`).
- `description`: para documentación y descubrimiento.
- **Ámbitos admitidos: solo dos** — `@WorkflowScoped` y `@ApplicationScoped`. Si no
  se declara ninguno, **el valor por defecto es `@WorkflowScoped`** (en Payara, la
  extensión CDI añade la anotación en `ProcessAnnotatedType`).

### Los dos ámbitos, lado a lado

| Aspecto | `@WorkflowScoped` (por defecto) | `@ApplicationScoped` |
| --- | --- | --- |
| Instancia del agente | **Una nueva por ejecución de workflow**; nace en el trigger y muere tras el outcome (o el fallo) | **Una sola** compartida por todas las ejecuciones, durante toda la vida de la aplicación |
| Campos de instancia | Privados de esa ejecución — acumula estado del workflow sin miedo | Compartidos entre workflows concurrentes — deben ser **thread-safe** y no pueden guardar estado de una ejecución concreta |
| Observadores CDI genéricos (`@Observes` sin `@Trigger`) | **Prohibidos** (error de despliegue) | Permitidos |
| Uso típico | Agente con estado por ejecución (el caso común) | Agente sin estado, recursos caros de inicializar, o un agente que además debe ser un observador CDI convencional |

Y el detalle fino que los iguala: incluso cuando el agente es
`@ApplicationScoped`, **se crea un contexto de workflow para cada ejecución**. El
estado conversacional del `LargeLanguageModel` inyectado sigue el ciclo de vida de
**ese contexto**, no el del bean — dos ejecuciones concurrentes de un agente
singleton siguen teniendo conversaciones aisladas. Dicho de otro modo: el ámbito
del agente decide dónde viven **los campos del agente**; la conversación con el LLM
es siempre por workflow.

¿Por qué la restricción del `@Observes` genérico aplica solo a `@WorkflowScoped`?
Un observador normal lo invoca el contenedor **fuera** de un workflow — y sin
workflow activo no hay contexto en el que crear/resolver la instancia
`@WorkflowScoped` del agente. Con `@ApplicationScoped` la instancia existe con
independencia de cualquier workflow, así que el observador normal simplemente
funciona.

### Ejemplo 1 — `@WorkflowScoped`: análisis de fraude con estado acumulado

El caso clásico del ámbito por defecto: el agente **acumula estado en campos de
instancia a lo largo de las fases** — cada ejecución recibe su propia instancia
fresca, de modo que los campos son un cuaderno privado del workflow, sin riesgo de
concurrencia.

```java
@Agent(description = "Analyzes suspicious transactions and builds a fraud dossier.")
public class FraudAnalysisAgent {          // sin ámbito declarado ⇒ @WorkflowScoped

    @Inject
    LargeLanguageModel llm;

    // Estado PRIVADO de esta ejecución — nace en el trigger, muere tras el outcome.
    private final List<String> findings = new ArrayList<>();  // ¡sin sincronización!
    private int riskScore;

    @Trigger
    void onTransaction(BankTransaction tx) {
        riskScore = tx.amount() > 10_000 ? 20 : 0;            // primera pista
    }

    @Decision
    boolean isSuspicious(BankTransaction tx) {
        String verdict = llm.query("Is this transaction suspicious? {}", tx);
        if (verdict.contains("yes")) {
            findings.add(verdict);                             // acumula en el campo
            riskScore += 50;
        }
        return riskScore > 40;                                 // si no, terminar
    }

    @Action
    void investigate(BankTransaction tx) {
        findings.add(llm.query("List the fraud indicators in: {}", tx));
        riskScore += findings.size() * 5;                      // afinar la puntuación
    }

    @Outcome
    void fileReport(BankTransaction tx, CaseService cases) {
        cases.open(tx, riskScore, findings);   // consolida TODO el estado acumulado
    }
}
```

Por qué `@WorkflowScoped` es la elección correcta aquí: `findings` y `riskScore`
crecen fase a fase y solo tienen sentido **para esta transacción**. Si este agente
fuera `@ApplicationScoped`, dos transacciones simultáneas mezclarían sus
expedientes. Y fíjate en que no hay `synchronized` ni colecciones concurrentes — no
hacen falta: nadie más puede ver esta instancia.

### Ejemplo 2 — `@ApplicationScoped`: triaje con un recurso caro compartido

El ámbito de aplicación compensa cuando el agente carga un **recurso caro que debe
inicializarse una sola vez** y/o necesita además ser un **observador CDI normal** —
las dos capacidades que `@WorkflowScoped` no ofrece:

```java
@Agent(description = "Classifies support tickets against the knowledge base.")
@ApplicationScoped                          // UNA instancia para toda la aplicación
public class TicketTriageAgent {

    @Inject
    LargeLanguageModel llm;

    // Recurso caro: se carga UNA vez y lo reutiliza cada workflow.
    private volatile KnowledgeBase kb;
    // El estado compartido exige tipos thread-safe:
    private final AtomicLong triaged = new AtomicLong();

    @PostConstruct
    void init() {
        kb = KnowledgeBase.loadFromDisk();   // minutos de carga — solo al arrancar
    }

    // Observador CDI NORMAL (sin @Trigger): permitido por ser @ApplicationScoped.
    // Se ejecuta FUERA de cualquier workflow — p. ej. una recarga publicada por un admin.
    void onKnowledgeBaseUpdated(@Observes KbUpdatedEvent event) {
        kb = KnowledgeBase.loadFromDisk();
    }

    @Trigger
    void onTicket(SupportTicket ticket) {
        triaged.incrementAndGet();           // métrica global — AtomicLong
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

Por qué `@ApplicationScoped` es la elección correcta aquí: la `KnowledgeBase` es
cara de cargar y es **de solo lectura durante los workflows** — recargarla por cada
ticket (que es lo que haría `@WorkflowScoped`, vía un `@PostConstruct` por
ejecución) sería prohibitivo. El observador `onKnowledgeBaseUpdated` es el bonus
exclusivo del ámbito: un evento administrativo que **no arranca ningún workflow**,
solo refresca el recurso. El precio se ve en el código: `volatile`, `AtomicLong` —
todos los campos son compartidos y la disciplina de concurrencia es tuya. Y
recuerda: incluso aquí, cada ticket recibe **su propio contexto de workflow** — la
conversación `llm` de un ticket en `classify` nunca contamina la de otro.

**Regla práctica:** estado del *caso en curso* en campos → `@WorkflowScoped` (el
valor por defecto existe para esto); recursos *caros y compartidos* + necesidad de
observadores normales → `@ApplicationScoped`, con la seguridad de hilos a tu cargo.

### ¿Por qué solo dos ámbitos?

La especificación no admite `@RequestScoped`, `@SessionScoped`,
`@ConversationScoped` ni `@Dependent` para agentes, y el razonamiento es sólido:

1. **El ciclo de vida natural del agente es el workflow, no la petición.** Un
   trigger puede dispararse desde cualquier sitio — un timer, un batch, un mensaje,
   otro agente — donde una petición/sesión HTTP **ni siquiera existe**. Atar el
   agente a los ámbitos web haría que su comportamiento dependiera de quién disparó
   el evento.
2. **`@Dependent` no tiene sentido para algo que nunca se inyecta.** El ámbito
   dependiente sigue el ciclo de vida de quien inyecta el bean — pero a los agentes
   no los inyecta nadie: los dirige el motor **por eventos**. No hay "dueño" al que
   seguir.
3. **Los dos ámbitos cubren las dos únicas respuestas a la pregunta que importa:**
   ¿el estado de los campos del agente es *por ejecución* (`@WorkflowScoped`) o
   *compartido* (`@ApplicationScoped`)? Cualquier otro ámbito sería una respuesta
   confusa a esa pregunta.
4. **Simplicidad de la 1.0.** Menos combinaciones = especificación más ligera, TCK
   más pequeño, implementaciones más fáciles de verificar. Si aparecen casos de uso
   reales para otros ámbitos, añadirlos después es más fácil que quitarlos.

## `@Trigger` — el punto de entrada

```java
@Trigger
void onQuestion(@Valid Question question) {
    logger.info("workflow started for: " + question.text());
}
```

Reglas:

- **Exactamente uno** `@Trigger` por agente (en la 1.0).
- Se invoca cuando se dispara un **evento CDI** compatible con el parámetro. El
  `@Observes` sobre el parámetro es **opcional** — el contenedor entiende la
  intención solo con `@Trigger`.
- El evento que dispara se añade automáticamente al **contexto de workflow**, de
  modo que las fases posteriores pueden recibirlo como parámetro.
- **Restricción importante de ámbito:** los agentes `@WorkflowScoped` solo pueden
  observar eventos vía `@Trigger`. Un método con `@Observes` "suelto" (sin
  `@Trigger`) en un agente `@WorkflowScoped` es un **error de despliegue**. Los
  agentes `@ApplicationScoped` pueden tener ambos (triggers y observadores CDI
  normales).
- Parámetros aceptados: el evento, `LargeLanguageModel` y cualquier dependencia CDI
  inyectable. Pueden llevar restricciones de Bean Validation (`@Valid`,
  `@NotNull`…) — la validación ocurre **antes** de la invocación y una violación se
  convierte en `ConstraintViolationException`, tratable con `@HandleException`.
- Retorno: `void` (solo efectos secundarios) **o** un objeto de dominio, que entra
  en el contexto de workflow y pasa a ser inyectable en las fases posteriores.

### Un trigger que devuelve un objeto de dominio — enriquecer el contexto

El ejemplo anterior es el patrón `void`. El segundo patrón de retorno hace que el
trigger **produzca datos** para el resto del workflow — típicamente un preanálisis
del evento, a menudo usando ya el LLM:

```java
@Agent
public class ClaimAgent {

    @Trigger
    ClaimAnalysis analyzeClaim(InsuranceClaim claim, LargeLanguageModel llm) {
        // Preanálisis en el punto de entrada: clasificar el siniestro ya en el trigger.
        // El valor devuelto (no void) entra en el contexto de workflow.
        return llm.query(
            "Classify this insurance claim (severity, category) as JSON: {}",
            ClaimAnalysis.class, claim);
    }

    @Decision
    boolean needsAdjuster(ClaimAnalysis analysis) {      // ← el retorno del trigger
        return analysis.severity() > 3;
    }

    @Action
    void assign(ClaimAnalysis analysis, InsuranceClaim claim, AdjusterPool pool) {
        pool.assign(claim, analysis.category());  // evento Y análisis, por tipo
    }
}
```

Lo que hace el motor entre bastidores (esto es el `WorkflowContext` del capítulo 6):
después del trigger, el contexto contiene **dos** objetos —

```
WorkflowContext
├── InsuranceClaim   ← el evento CDI (añadido automáticamente, siempre)
└── ClaimAnalysis    ← el valor DEVUELTO por el trigger (añadido por no ser void/null)
```

— y cada fase posterior declara en sus parámetros **el que quiera de los dos**, por
tipo: `needsAdjuster` pide solo el análisis; `assign` pide ambos. Ningún parámetro
se pasa a mano — la resolución es tarea del contenedor. Es el mismo mecanismo que
después recibe los retornos de `@Decision` (el `details()` del `Result`) y de
`@Action`, apilándose en el contexto para las fases siguientes.

Cuándo usar cada patrón: `void` cuando el trigger solo inicializa o registra (el
evento en sí basta para las fases siguientes); un retorno de dominio cuando hay una
**transformación o análisis del evento** que las fases posteriores van a consumir —
evita repetir el análisis en cada fase y mantiene el trigger como el único sitio
que "traduce" el evento crudo.

## `@Decision` — puntos de decisión

```java
@Decision
Result hasContent(Question question) {
    boolean proceed = question.text() != null && !question.text().isBlank();
    return new Result(proceed, question);
}
```

- 0..N por agente; pueden **intercalarse con acciones**.
- Típicamente consultan al LLM para decidir la dirección del workflow.
- **Tres patrones de retorno** (memorízalos):

| Retorno | Continúa si... | Datos propagados |
| --- | --- | --- |
| `boolean` | `true` | nada |
| `Result` | `result.success() == true` | el `details()` entra en el contexto |
| Objeto de dominio | no nulo | el propio objeto entra en el contexto |

- Devolver `false`, `Result(false, ...)` o `null` **termina el workflow** sin
  ejecutar las fases restantes ni el `@Outcome`.

### Varias decisiones en el mismo workflow — cómo hablan entre sí

Varias decisiones forman una **cadena de compuertas en serie** (un AND lógico):
cada una debe aprobar para que la fase siguiente se ejecute, y se comunican **a
través del contexto de workflow** — el dato que publica una se convierte en
parámetro de la siguiente. Un pipeline de crédito muestra los tres patrones de
retorno cooperando:

```java
@Agent
public class LoanAgent {

    // COMPUERTA 1 — barata, sin LLM: cortar pronto lo que ni siquiera merece análisis.
    // Result(true, policy) publica el PolicyCheck en el contexto.
    @Decision
    Result withinPolicy(LoanApplication app, PolicyService policies) {
        PolicyCheck policy = policies.check(app);
        return new Result(policy.approved(), policy);
    }

    // COMPUERTA 2 — cara, con LLM. CONSUME el PolicyCheck publicado por la compuerta 1.
    // Retorno de objeto: no nulo ⇒ continuar (y publicar); null ⇒ parar.
    @Decision
    RiskAssessment assessRisk(LoanApplication app, PolicyCheck policy,
                              LargeLanguageModel llm) {
        RiskAssessment risk = llm.query(
            "Assess the risk of this application: {} given policy limits: {}",
            RiskAssessment.class, app, policy);
        return risk.score() < 700 ? null : risk;
    }

    // Una acción entre decisiones: construye la oferta a partir del análisis de riesgo.
    @Action
    LoanOffer prepareOffer(RiskAssessment risk, LoanApplication app) {
        return new LoanOffer(app, risk.suggestedRate());
    }

    // COMPUERTA 3 — DESPUÉS de una acción: valida lo que la acción produjo.
    // Boolean: solo decide, no publica nada.
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

El flujo de datos por el contexto, compuerta a compuerta:

```
Trigger                     ctx: [LoanApplication]
withinPolicy  ✔ Result ──►  ctx: [LoanApplication, PolicyCheck]
assessRisk    ✔ objeto ──►  ctx: [LoanApplication, PolicyCheck, RiskAssessment]
prepareOffer  (acción) ──►  ctx: [..., LoanOffer]
offerViable   ✔ boolean ─►  ctx sin cambios (un Boolean no publica datos)
send          (outcome)     consume LoanOffer
```

Las reglas de la "conversación":

1. **El orden importa** — aquí es el orden de declaración; con `@Priority`/`order`
   la cadena se puede reordenar sin mover código (respetando el requisito de
   consistencia).
2. **La comunicación siempre pasa por el contexto, por tipo** — `assessRisk` recibe
   el `PolicyCheck` porque la compuerta 1 lo publicó vía `Result.details()`. No hay
   llamada directa entre decisiones ni variable compartida obligatoria (aunque un
   agente `@WorkflowScoped` también puede usar campos, como hace
   `FraudAnalysisAgent`).
3. **Cada compuerta corta el resto del workflow** — si `assessRisk` devuelve `null`,
   entonces `prepareOffer`, `offerViable` y `send` no se ejecutan. No hay "else": en
   la 1.0 la ramificación es un **filtro en serie**, no un árbol if/else. Las ramas
   alternativas se modelan con el patrón compuerta + acción condicionada al dato
   publicado (o con otro agente escuchando otro evento).
4. **Una decisión después de una acción es válida y útil** — `offerViable` valida
   el *producto* de `prepareOffer`. Este es el patrón "intercalado" que el TCK cubre
   con los fixtures `IntermixedAgent`/`BranchingAgent`.
5. **Elige el tipo de retorno según lo que necesites comunicar**: `boolean` para una
   compuerta pura, un objeto cuando el veredicto *es* el dato, `Result` cuando
   quieras separar el veredicto (`success`) del dato (`details`) — incluido publicar
   datos con veredicto negativo tratado por otra vía.

⚠️ Matiz: la terminación anticipada **no deshace los efectos secundarios** de las
fases que ya se ejecutaron. Si `prepareOffer` hubiera persistido la oferta y
`offerViable` devolviera `false`, la fila seguiría en la base de datos — el workflow
se detiene, no revierte (salvo que lo integres con una transacción propia). Cómo
hacerlo es el tema siguiente.

#### Deshacer efectos secundarios con Jakarta Transactions

Primero, la advertencia honesta: **la especificación 1.0 no define semántica
transaccional para los workflows**. Pero dos hechos que ya vimos hacen natural la
integración: el workflow se ejecuta **de forma síncrona en el hilo que llamó a
`Event.fire`**, y los observadores CDI síncronos se ejecutan, por defecto, **dentro
del contexto transaccional del llamador**. Por tanto, un `@Transactional` en el
llamador envuelve todo el workflow:

```java
@Path("loans")
@RequestScoped
public class LoanResource {

    @Inject Event<LoanApplication> trigger;

    @POST
    @Transactional              // JTA: UNA transacción envuelve TODO el workflow
    public Response apply(LoanApplication app) {
        trigger.fire(app);      // trigger→decisiones→acciones→outcome, en esta transacción
        return Response.ok().build();
    }
}
```

Pero hay una trampa central: **la terminación anticipada es una finalización
normal** — la decisión devuelve `false`, `fire` retorna sin error y la transacción
**hace commit**, incluida la persistencia de `prepareOffer`. En JTA, el rollback
exige una **excepción**. Así que la compuerta que deba deshacer lo anterior tiene que
**lanzar** en lugar de devolver `false`:

```java
@Decision
boolean offerViable(LoanOffer offer) {
    if (offer.rate() >= MAX_LEGAL_RATE) {
        // NO "return false": eso terminaría el workflow y la transacción haría COMMIT.
        throw new OfferRejectedException(offer);   // ⇒ rollback de TODO
    }
    return true;
}
```

La vía de la excepción cierra el círculo con lo ya estudiado: sin un
`@HandleException` que coincida (o con un handler que **relance**), atraviesa el
motor, sale por `fire()` y estalla dentro del método `@Transactional` → la
transacción se marca para rollback → el `INSERT` de `prepareOffer` se deshace con
ella. Tres consecuencias que conviene entender bien:

1. **Un `@HandleException` que se recupera hace commit.** Si un handler captura la
   `OfferRejectedException` y retorna normalmente, la excepción nunca llega a la
   transacción — recuperarse significa "el workflow tuvo éxito", y lo que se
   persistió se queda. Handler y transacción deben diseñarse **juntos**: recuperar =
   conservar efectos; relanzar = deshacer.
2. **`@Transactional` sobre una sola fase tiene otro efecto**: anotar solo
   `prepareOffer` crea una transacción que hace commit **al retornar la fase** — que
   falle una compuerta posterior ya no la deshace. Compra atomicidad *dentro* de la
   fase, no protección para la cadena.
3. **A menudo un rediseño gana a la transacción**: si la validación no depende del
   efecto secundario, mueve la compuerta para que se ejecute **antes** de la acción
   (`offerViable` comprobando la tasa *antes* de persistir) o deja la persistencia
   al `@Outcome`, que solo se ejecuta cuando todas las compuertas han aprobado. La
   transacción es la herramienta para cuando el efecto y la validación son
   inseparables (p. ej. tienes que insertar para obtener un ID que la validación
   usa).

## `@Action` — el trabajo de verdad

```java
@Action
void generate(Question question) {
    String answer = model.query("Answer concisely: {}", question.text());
    answers.put(question.text(), answer);
}
```

- 0..N por agente; realizan operaciones (persistir, llamar a servicios, actualizar
  estado).
- Retorno: `void` o un objeto de dominio (que entra en el contexto para las fases
  posteriores).
- Reciben como parámetros: resultados de decisiones anteriores, el evento del
  trigger, `LargeLanguageModel`, beans CDI.

## Orden de ejecución de `@Decision`/`@Action`

Precedencia, aplicada en este orden:

1. **`@Priority` en el método** — los valores menores van primero; **gana** a
   `order()`.
2. **El atributo `order()` de la propia anotación** — se usa cuando no hay
   `@Priority`.
3. **Orden de declaración en el código fuente** — se usa cuando **ningún** método
   declara orden explícito. Cuidado: la reflexión de Java SE **no** garantiza el
   orden de declaración, aunque las JVM mayoritarias lo preservan en la práctica;
   las aplicaciones portables que necesiten un orden estricto deben usar
   `@Priority`/`order`.

**Requisito de consistencia:** si **cualquier** `@Decision`/`@Action` del agente
declara un `order` explícito o `@Priority`, **todos** los demás deben hacerlo
también. Mezclar métodos ordenados y no ordenados es un **error de despliegue**.

Antes de los ejemplos, dos recordatorios: el orden aplica **solo a la cadena
`@Decision`/`@Action`** (`@Trigger` siempre abre y `@Outcome` siempre cierra, fuera
de la competición), y decisiones y acciones se ordenan **juntas, en una única
cola** — no como dos listas separadas.

### Caso 1 — Sin orden: manda la declaración

```java
@Agent
public class ReportAgent {

    @Trigger  void onRequest(ReportRequest req) { }

    @Decision boolean hasData(ReportRequest req)   { /* ... */ }   // 1.º
    @Action   Draft   buildDraft(ReportRequest req){ /* ... */ }   // 2.º
    @Decision boolean draftOk(Draft draft)         { /* ... */ }   // 3.º
    @Action   void    publish(Draft draft)         { /* ... */ }   // 4.º

    @Outcome  void done(Draft draft) { }
}
```

Ejecución: `hasData → buildDraft → draftOk → publish` — exactamente el orden en que
aparecen en el fuente. Simple y legible... hasta que alguien **reordena los métodos
en una refactorización** y cambia el comportamiento en silencio: ningún compilador
te avisa de que el orden de los métodos era semántico. Ese riesgo (además de la
falta de garantía formal de la reflexión) es lo que elimina el orden explícito.

### Caso 2 — `order()`: la posición sale del texto y se vuelve contrato

El mismo agente, con el orden declarado — los métodos pueden estar ahora en
**cualquier posición del archivo** (aquí, deliberadamente barajados) sin afectar a
la ejecución:

```java
@Agent
public class ReportAgent {

    @Trigger  void onRequest(ReportRequest req) { }

    @Action(order = 40)   void    publish(Draft draft)          { /* ... */ }  // 4.º
    @Decision(order = 10) boolean hasData(ReportRequest req)    { /* ... */ }  // 1.º
    @Decision(order = 30) boolean draftOk(Draft draft)          { /* ... */ }  // 3.º
    @Action(order = 20)   Draft   buildDraft(ReportRequest req) { /* ... */ }  // 2.º

    @Outcome  void done(Draft draft) { }
}
```

Ejecución: `hasData(10) → buildDraft(20) → draftOk(30) → publish(40)`. Consejos
prácticos: usa **incrementos de 10** (insertar un paso nuevo entre 20 y 30 pasa a
ser `order = 25`, sin renumerar) y **evita `order = 0`** — el cero es el valor por
defecto de la anotación, así que no cuenta como orden explícito; usa valores
positivos.

### Caso 3 — `@Priority` gana a `order()` en el mismo método

```java
@Agent
public class MixedAgent {

    @Trigger void on(StartEvent e) { }

    @Priority(1)
    @Action(order = 99)          // order se IGNORA: @Priority está presente en el método
    void runsFirst() { /* ... */ }     // clave de orden = 1

    @Action(order = 2)
    void runsSecond() { /* ... */ }    // sin @Priority ⇒ aplica order = 2
}
```

Ejecución: `runsFirst (clave 1) → runsSecond (clave 2)` — a pesar del `order = 99`.
La regla es **por método**: en cada uno, `@Priority` (si está) aporta la clave de
orden; si no, lo hace `order()`. Las claves resultantes se comparan después entre
todos los métodos de la cadena. Conviene elegir **un** estilo por agente
(`@Priority` O `order`) y mezclarlos solo durante migraciones.

### Caso 4 — Mezcla inválida: error de despliegue

```java
@Agent
public class BrokenAgent {

    @Trigger void on(StartEvent e) { }

    @Decision(order = 10) boolean gate(StartEvent e) { /* ... */ }  // explícito
    @Action               void step1() { /* ... */ }                // ✘ ¡implícito!
}
```

El despliegue falla (en Payara: `DefinitionException: Inconsistent order at @Agent
... all @Decision/@Action should declare @Priority or order or nothing.`). El
razonamiento tras la regla: si `step1` no tuviera orden, ¿cuál sería su posición
respecto a `gate(10)`? Cualquier respuesta (¿antes? ¿después? ¿orden de declaración
solo para él?) sería una convención oscura — la especificación prefiere forzar la
intención explícita a adivinar.

## `@Outcome` — la fase terminal

- **0 o 1** por agente (dos es un error de despliegue); opcional.
- Se ejecuta después de que las demás fases terminen **con éxito**.
- **Debe devolver `void`** en la 1.0 (finalización y efectos secundarios, no
  producción de datos).
- Cuando termina, **el contenedor destruye el contexto de workflow**.

## `@HandleException` — recuperación de errores

```java
@HandleException
void handleLlmFailure(LLMException ex, Question question) {
    logger.warn("LLM unavailable, using fallback", ex);
    // retorno normal ⇒ el workflow CONTINÚA
}
```

Semántica (la más rica de la especificación — estúdiala bien):

- 0..N por agente; capturan excepciones de **cualquier fase** (trigger, decision,
  action u outcome).
- **Selección del handler:** el del tipo de excepción **más específico** compatible
  con la excepción lanzada (sigue la jerarquía de Java). El parámetro de excepción
  es obligatorio.
- **Control del workflow:**
  - El handler **retorna normalmente** ⇒ recuperación exitosa, el workflow continúa.
  - El handler **relanza o lanza una nueva excepción** ⇒ el workflow se detiene; la
    excepción se propaga al contenedor.
- Sin handler compatible ⇒ la excepción se propaga al contenedor.
- **Sin tratamiento recursivo:** una excepción lanzada por un handler no se redirige
  a otro handler — va directa al contenedor.
- El retorno **debe ser `void`**.

### Los escenarios, uno a uno

Un único agente de pagos ilustra todos los caminos posibles. La jerarquía de
excepciones usada: `PaymentException` (base) ← `GatewayTimeoutException` (derivada).

```java
@Agent
public class PaymentAgent {

    @Trigger
    void onPayment(PaymentRequest req) { /* ... */ }

    @Decision
    boolean authorized(PaymentRequest req, LargeLanguageModel llm) { /* ... */ }

    @Action
    void charge(PaymentRequest req, GatewayClient gateway) {
        gateway.charge(req);   // puede lanzar GatewayTimeoutException, LLMException...
    }

    @Outcome
    void confirm(PaymentRequest req, Receipts receipts) { /* ... */ }

    // ── ESCENARIO 1: recuperación — retorna normalmente, el workflow "tiene éxito" ──
    // Nota: recibe la excepción Y estado del workflow (req viene del contexto).
    @HandleException
    void onTimeout(GatewayTimeoutException ex, PaymentRequest req,
                   RetryQueue retries) {
        retries.enqueue(req);              // recuperación: reprocesar más tarde
        // retorno normal ⇒ el motor considera el workflow RECUPERADO
        // y ejecuta igualmente el @Outcome confirm() como cierre
    }

    // ── ESCENARIO 2: fatal — relanza, el workflow se detiene ──
    @HandleException
    void onPaymentError(PaymentException ex, PaymentRequest req, AuditLog audit) {
        audit.paymentFailed(req, ex);      // registrar ANTES de rendirse
        throw ex;                          // se propaga al contenedor; el @Outcome NO corre
    }

    // ── ESCENARIO 3: recuperación condicional — decide en tiempo de ejecución ──
    @HandleException
    void onLlmFailure(LLMException ex, PaymentRequest req) {
        if (req.amount() < 100) {
            return;                        // importe bajo: aprobar sin LLM, continuar
        }
        throw new ManualReviewException(req, ex);   // importe alto: parar y escalar
    }

    // ── ESCENARIO 4: red de seguridad — el tipo más genérico ──
    @HandleException
    void onAnyError(Exception ex, AlertService alerts) {
        alerts.notifyOps(ex);
        throw new IllegalStateException("Unexpected payment failure", ex);
    }
}
```

Ahora, qué ocurre en cada situación:

| `charge` lanza... | Handler elegido | Por qué | Resultado |
| --- | --- | --- | --- |
| `GatewayTimeoutException` | `onTimeout` | Gana la coincidencia **más específica** — `onPaymentError(PaymentException)` también encajaría, pero es más genérico | Retorna normalmente ⇒ workflow recuperado, `confirm()` (`@Outcome`) **se ejecuta** |
| `PaymentException` (distinta de un timeout) | `onPaymentError` | La única coincidencia específica | Relanza ⇒ el workflow **se detiene**, `confirm()` no corre, la excepción llega al contenedor (y al `@Transactional` del llamador, si lo hay) |
| `LLMException` | `onLlmFailure` | Coincidencia exacta | Depende del importe: retorna (continuar + outcome) **o** lanza `ManualReviewException` — que **no** vuelve a ser tratada por los otros handlers (sin recursión): va directa al contenedor |
| `NullPointerException` | `onAnyError` | Solo encaja el `Exception` genérico | Alerta y relanza envuelta ⇒ el workflow se detiene |
| Un `Error` (p. ej. `OutOfMemoryError`) | ninguno | Un `Error` no es una `Exception` — ningún parámetro encaja | Se propaga directo al contenedor |

Cuatro detalles finos escondidos en el ejemplo:

1. **La selección sigue la jerarquía, no el orden de declaración** — que `onAnyError`
   esté el último en el archivo da igual; solo se elige cuando ningún tipo más
   específico encaja (Payara preordena los handlers de más específico a más genérico
   en el despliegue).
2. **Los handlers reciben estado del workflow** — `onTimeout` declara
   `PaymentRequest` y `RetryQueue` además de la excepción: la resolución de
   parámetros es la misma que en las otras fases (excepción en vuelo → contexto →
   CDI).
3. **Recuperarse ejecuta el `@Outcome`** — el escenario 1 termina con `confirm()`
   ejecutándose (una regla del motor, capítulo 6: el outcome como fase de cierre de
   la recuperación — salvo cuando el propio outcome fue lo que falló).
4. **La `ManualReviewException` del escenario 3 no cae en la red de seguridad** — esa
   es la regla de "sin tratamiento recursivo": una excepción lanzada *por un handler*
   nunca se despacha a otro handler, aunque el agente tenga uno genérico de
   `Exception`. Sin esto, un handler con un fallo podría crear un bucle infinito de
   tratamiento.

Y el enlace con la sección anterior: si el llamador envolvió el `fire` en un
`@Transactional`, **el escenario 1 hace commit** (recuperación = éxito) y **los
escenarios 2 y 4 hacen rollback** (la excepción atraviesa) — el diseño del handler
decide el destino de la transacción.

## `@WorkflowScoped` — el ámbito de workflow

- Un **ámbito normal** de CDI (`@NormalScope`): un contexto por ejecución de
  workflow, que abarca de trigger a outcome. Los beans nacen cuando arranca el
  workflow y mueren cuando termina.
- Uso típico: compartir estado entre fases sin pasar parámetros (p. ej. una caché
  de análisis).
- Trae un `Literal` (`WorkflowScoped.Literal.INSTANCE`) para instanciación en línea
  — es lo que usa la extensión de Payara para aplicar el ámbito por defecto de forma
  programática.

## `Result` y la propagación de datos

```java
public record Result(boolean success, Object details) {}
```

El mecanismo general de **propagación de datos basada en tipos**:

1. El evento del trigger entra en el contexto.
2. Todo retorno de fase no nulo entra en el contexto (para un `Result`, entra el
   `details()`; el `Boolean` de una decisión no lleva datos).
3. Al invocar una fase, cada parámetro se resuelve **por tipo**, prefiriendo el
   valor producido **más recientemente** (si dos fases produjeron el mismo tipo,
   gana el último).
4. Lo que no está en el contexto se resuelve como bean CDI.

---

## Test — Capítulo 2

**1.** Un agente `@WorkflowScoped` declara, además del `@Trigger`, un método
`void onAudit(@Observes AuditEvent e)` sin `@Trigger`. ¿Qué ocurre en el despliegue?

<details><summary>Ver respuesta</summary>

**Error de despliegue** (`DefinitionException`). Los agentes `@WorkflowScoped` solo
pueden observar eventos CDI mediante métodos `@Trigger`. Los observadores CDI
genéricos solo se permiten en agentes `@ApplicationScoped`.
</details>

**2.** Una `@Decision` devuelve `new Result(true, new Plan("x"))`. ¿Qué queda
exactamente disponible para las fases posteriores, y cómo lo recibe una `@Action`?

<details><summary>Ver respuesta</summary>

El workflow continúa (`success == true`) y el **`details()`** — el objeto
`Plan("x")` — se publica en el contexto de workflow. Una `@Action` lo recibe
simplemente declarando un parámetro de tipo `Plan`:
`@Action void execute(Plan plan) {...}`. La resolución es por tipo, con el valor más
reciente primero.
</details>

**3.** En un agente, el método A tiene `@Action(order = 5)` y el método B solo
`@Action`. ¿Es válido?

<details><summary>Ver respuesta</summary>

**No** — viola el requisito de consistencia: si alguna `@Decision`/`@Action` declara
un `order` explícito o `@Priority`, todas las demás deben declararlo también.
Mezclar métodos ordenados explícitamente con métodos sin orden es un error de
despliegue.
</details>

**4.** Un método tiene `@Action(order = 10)` y también `@Priority(1)`. ¿Qué valor
determina su posición de ejecución?

<details><summary>Ver respuesta</summary>

El `@Priority(1)` — cuando está presente en el método, `@Priority` **tiene
precedencia** y `order()` se ignora. Los valores menores se ejecutan primero.
</details>

**5.** Un `@HandleException` captura una `IOException`, la registra y retorna
normalmente. La excepción ocurrió en una `@Action` intermedia. ¿Se ejecuta el
`@Outcome`?

<details><summary>Ver respuesta</summary>

**Sí.** Que un handler retorne normalmente significa recuperación: el workflow
continúa, y la fase `@Outcome` (si existe y aún no se intentó) se ejecuta como
cierre. Si el handler hubiera relanzado la excepción, el workflow se detendría y la
excepción se propagaría al contenedor.
</details>

**6.** ¿Por qué es arriesgado apoyarse solo en el orden de declaración del fuente
para ordenar las fases, según la propia especificación?

<details><summary>Ver respuesta</summary>

Porque Java SE **no** garantiza que la reflexión devuelva los métodos en orden de
declaración — las JVM mayoritarias lo preservan en la práctica, pero no es un
contrato. Las aplicaciones portables que necesiten un orden estricto deben declarar
`@Priority` u `order` explícitamente.
</details>

**7.** En el `LoanAgent`, ¿cómo llega el `PolicyCheck` producido por la primera
decisión hasta la segunda (`assessRisk`)? ¿Y si `assessRisk` devuelve `null` después
de que `withinPolicy` aprobara, qué se ejecuta y qué no?

<details><summary>Ver respuesta</summary>

`withinPolicy` devuelve `Result(true, policy)` — el `details()` (el `PolicyCheck`)
se **publica en el contexto de workflow**, y `assessRisk` lo recibe declarando un
parámetro de tipo `PolicyCheck` (resolución por tipo; las decisiones nunca se llaman
entre sí directamente). Si `assessRisk` devuelve `null`, el workflow **termina justo
ahí**: `prepareOffer`, `offerViable` y el `@Outcome` no se ejecutan. Y cuidado: lo
que ya se ejecutó **no se deshace** — la terminación anticipada no es un rollback.
</details>

**8.** El `LoanResource` envuelve el `fire` en un `@Transactional`. `prepareOffer`
persistió la oferta y después `offerViable` devuelve `false`. ¿Se deshace el
`INSERT`? ¿Y si, en lugar de devolver `false`, la decisión lanzara una excepción que
un `@HandleException` captura y trata retornando normalmente?

<details><summary>Ver respuesta</summary>

En ambos casos el `INSERT` **se queda en la base de datos**. Devolver `false` es una
terminación anticipada **normal**: `fire` retorna sin error y la transacción hace
**commit**. Y si la excepción la captura un handler que retorna normalmente, nunca
llega al método `@Transactional` — recuperarse significa workflow exitoso, luego
commit. El rollback solo ocurre cuando la excepción **atraviesa** el motor (sin
handler, o con un handler que relanza) y estalla dentro de la transacción del
llamador. Por eso handler y transacción deben diseñarse juntos: recuperar =
conservar efectos; relanzar = deshacer.
</details>

**9.** Un agente `@ApplicationScoped` guarda el resultado parcial del workflow actual
en un campo de instancia. ¿Cuál es el problema y dónde debería vivir ese estado? Y la
conversación con el LLM, ¿también se filtra entre ejecuciones?

<details><summary>Ver respuesta</summary>

El bean es **uno solo para toda la aplicación**: workflows concurrentes se pisan el
campo (condición de carrera y estado filtrándose entre ejecuciones). El estado por
ejecución debería vivir en el **contexto de workflow** — retornos de fase propagados
por tipo, un bean auxiliar `@WorkflowScoped`, o simplemente usar el agente en el
ámbito `@WorkflowScoped` por defecto. La conversación con el LLM, en cambio, **no**
se filtra: la especificación exige estado conversacional aislado **por contexto de
workflow**, incluso con un agente `@ApplicationScoped` — el ámbito del agente
gobierna los campos del bean, no el historial del LLM.
</details>

**10.** En el `PaymentAgent`, `charge` lanza una `GatewayTimeoutException`. El agente
tiene handlers para `GatewayTimeoutException`, `PaymentException` (supertipo) y
`Exception`. ¿Cuál se invoca y por qué? Y si ese handler lanza a su vez una nueva
excepción, ¿la captura el handler de `Exception`?

<details><summary>Ver respuesta</summary>

`onTimeout(GatewayTimeoutException)` — la selección sigue la jerarquía de Java y
elige el tipo compatible **más específico**, sin importar el orden de declaración en
el archivo. Si después lanza una nueva excepción, **no se consulta ningún otro
handler**: aplica la regla de "sin tratamiento recursivo" — una excepción lanzada por
un handler va directa al contenedor, aunque exista un `@HandleException(Exception)`
genérico. Esto evita bucles infinitos de tratamiento (un handler tratando el fallo de
otro handler).
</details>

---

➡️ Siguiente: [Capítulo 3 — LargeLanguageModel y errores](03-largelanguagemodel.md)
