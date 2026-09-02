# Capítulo 4 — El TCK (Technology Compatibility Kit)

## Para qué sirve

El TCK es la batería de pruebas que una implementación debe pasar para declararse
**compatible** con la especificación. Es el contrato ejecutable: cada prueba está
atada a un requisito de la especificación vía `@Assertion(id, section, strategy)`.

Una peculiaridad estructural: **las pruebas del TCK viven en `src/main/java`**, no
en `src/test/java`. Motivo: se **compilan y empaquetan** para que quienes
implementan puedan ejecutarlas contra su propia implementación. Solo las pruebas
unitarias del framework interno del TCK viven en `src/test/java`.

## Las anotaciones del framework de pruebas

| Anotación | Nivel | Efecto |
| --- | --- | --- |
| `@Standalone` | clase | Pruebas estructurales basadas en reflexión; **sin contenedor**. Añade solo la `AssertionExtension`. |
| `@Deployed` | clase | Pruebas de integración con **Arquillian**; requieren un contenedor CDI completo (weld-embedded en CI). Añade `ArquillianExtension` + `AssertionExtension`. |
| `@Assertion(id, section, strategy)` | método | Meta-anotación que envuelve `@Test` y mapea la prueba a un requisito de la especificación (p. ej. `id = "AGENTICAI-ORCHESTRATION-BHV-002"`). |
| `@RequiresImplementation` | método/clase | Omite la prueba cuando **no** hay una implementación compatible. |
| `@RequiresNoImplementation` | método/clase | Omite la prueba cuando **sí** hay implementación — se usa para las aserciones de baseline en "CDI puro" (solo trigger). |

## Detección de la implementación — el opt-in explícito

¿Cómo sabe el TCK si se ejecuta sobre una implementación compatible (Payara) o
sobre CDI puro (Weld sin el motor)? La `ImplementationPresentCondition` (una
`ExecutionCondition` de JUnit 5) comprueba **en tiempo de ejecución, dentro del
contenedor**, una única system property:

```java
public static final String IMPLEMENTATION_PRESENT_PROPERTY =
        "jakarta.ai.agent.tck.implementation.present";

// ...
return Boolean.getBoolean(IMPLEMENTATION_PRESENT_PROPERTY);
```

Una implementación que ejecuta el TCK la pone a `true` (normalmente con
`-Djakarta.ai.agent.tck.implementation.present=true` en el `argLine` de
Surefire/Failsafe). **Que esté ausente significa "sin implementación compatible"** —
justo lo que necesita una ejecución en CDI puro (Weld/OpenWebBeans) de las
aserciones de baseline, de modo que el valor por defecto no exige configuración
alguna.

> **¿Por qué una property y no un sondeo del contenedor?** Una versión anterior
> identificaba la implementación preguntando al `BeanManager` si había un `Context`
> registrado para `@WorkflowScoped` — toda implementación compatible registra uno,
> y CDI puro no. Elegante, pero **no portable sobre el baseline Jakarta EE 10**: el
> `BeanManager` de CDI 4.0 no expone forma de enumerar los contextos registrados. El
> opt-in explícito funciona en cualquier contenedor CDI 4.0.

Un detalle sutil: con Arquillian, las condiciones se evalúan **dos veces** — en la
JVM cliente (fuera del contenedor) y dentro del contenedor. Fuera del contenedor no
hay forma de saberlo; la condición entonces **deja la prueba habilitada y aplaza**
la decisión real a la evaluación dentro del contenedor (el detector devuelve `null`
y la condición responde "enabled" con la razón "deferring").

Esto sustituyó al antiguo `@Disabled` sobre `AgentSmokeTest`: en lugar de una prueba
permanentemente apagada, `fullLifecycleRequiresCompatibleImplementation` se ejecuta
automáticamente cuando hay implementación presente y se omite (con una razón clara)
cuando no la hay.

## Infraestructura de pruebas (para quienes implementan)

Dos clases `@ApplicationScoped` que no son pruebas, sino herramientas:

- **`LargeLanguageModelStub`** — implementa `LargeLanguageModel` con respuestas
  guionizadas: la prueba llama a `enqueueResponse("...")` antes de disparar el
  workflow, y el stub devuelve las respuestas en orden, registrando cada llamada
  para las aserciones. `reset()` lo limpia entre pruebas. Es también la demostración
  de que **el LLM de la aplicación gana al del runtime** (Payara se auto-veta su LLM
  por defecto cuando la aplicación aporta uno — capítulo 5).
- **`ExecutionTraceRecorder`** — registra las fases ejecutadas (`TRIGGER`,
  `DECISION`, `ACTION`, `OUTCOME`, `HANDLE_EXCEPTION`) y habilita aserciones de
  orden: `trace.assertOrder(TRIGGER, DECISION, ACTION, OUTCOME)`.
  ⚠️ Trampa conocida: en las pruebas `@Deployed`, `@BeforeEach` no se ejecuta entre
  métodos como esperarías — llama a `trace.reset()` en línea al inicio de la prueba.

## Qué cubre el TCK (mapa de paquetes)

- `core/agent` — estructura de las anotaciones `@Agent`, el contrato de
  `LargeLanguageModel`, `LLMException` (standalone/reflexión).
- `core/lifecycle` — estructura de `@Trigger`, `@Decision`, `@Action`, `@Outcome`,
  `@HandleException`.
- `core/cdi` — metadatos CDI del agente y de `@WorkflowScoped`.
- `core/integration` — `AgentSmokeTest`: la comprobación de extremo a extremo sobre
  un `GreetingAgent`.
- `core/behavior` — las clases de prueba de comportamiento desplegadas
  (`OrchestrationTests`, `TerminationTests`, `DataPropagationTests`,
  `PhaseOrderingTests`, `HandleExceptionTests`, `CdiIntegrationTests`,
  `ContextInjectionTests`, `LlmContractTests`, `VoidPhasesTests`,
  `TopologyFlexTests`, `WorkflowScopeLifecycleTests`). Cada clase despliega sus
  propios agentes de fixture, que viven un paquete más abajo, bajo
  `core/behavior/agents/<tema>`:
  - `orchestration` — topologías: minimalista, lineal, intercalada, branching,
    outcome-only, anchored;
  - `termination` — los tres patrones de terminación de decisión (boolean, `Result`,
    objeto/null);
  - `datapropagation` — propagación por tipo entre fases;
  - `phaseordering` — `@Priority`/`order`/orden de declaración;
  - `errorhandling` — recuperación, propagación, jerarquía de handlers, guarda
    anti-recursión, ausencia de handler;
  - `cdi` — interceptores, inyección por constructor, callbacks de ciclo de vida,
    ámbito por defecto, agentes singleton;
  - `contextinjection` — qué puede recibir una fase del contexto de workflow;
  - `voidphases`, `topologyflex`, `llm` — fases void, fases opcionales, el contrato
    del LLM dentro de un workflow real.
- `framework/signature` — pruebas de firma de la API (compatibilidad binaria), con
  la superficie registrada en
  `src/main/resources/.../signature/jakarta.ai.agent.sig_1.0`.

## Ejemplos concretos

Para poner cara a cada bloque, cuatro muestras reales del TCK — una por "sabor" de
prueba.

### Standalone / reflexión (`core/agent`)

Verifica la **forma** de la anotación sin arrancar un contenedor. Barato, corre en
cualquier JVM.

```java
@Standalone
public class AgentAnnotationTests {

    @Assertion(id = "AGENTICAI-AGENT-003",
               strategy = "Verify @Agent annotation targets TYPE elements")
    public void testAgentAnnotationTarget() {
        Target target = Agent.class.getAnnotation(Target.class);
        assertNotNull(target, "@Agent must have @Target annotation");
        ElementType[] targets = target.value();
        assertEquals(1, targets.length);
        assertEquals(ElementType.TYPE, targets[0]);
    }
}
```

### Orquestación (`core/behavior/orchestration`)

El clásico: disparar un evento y comprobar la **secuencia de fases** registrada por
`ExecutionTraceRecorder`. El `AnchoredAgent` demuestra que el orden de ejecución
viene del **orden de declaración en el fuente**, no de la posición de
`@Trigger`/`@Outcome`.

```java
@RequiresImplementation
@Assertion(id = "AGENTICAI-ORCHESTRATION-BHV-002",
           strategy = "@Decision and @Action execute in source-file declaration order; AnchoredAgent "
                    + "declares @Action BEFORE @Decision so the impl must invoke act() before decide()")
public void methodsExecuteInDeclarationOrder() {
    trace.reset();
    anchoredEvents.fire(new AnchoredEvent("test"));
    assertThat(trace.phases())
            .containsExactly(Phase.TRIGGER, Phase.ACTION, Phase.DECISION, Phase.OUTCOME);
}
```

### Terminación (`core/behavior/termination`)

Los tres patrones de terminación de `@Decision` — cada uno se convierte en una
prueba con la **misma forma** y la misma aserción (`TRIGGER, DECISION` — el pipeline
se detiene ahí):

```java
@RequiresImplementation
@Assertion(id = "AGENTICAI-TERM-004",
           strategy = "Boolean false from @Decision halts all downstream phases")
public void booleanFalseTerminatesWorkflow() {
    trace.reset();
    booleanEvents.fire(new BooleanTerminationEvent("x"));
    assertThat(trace.phases()).containsExactly(Phase.TRIGGER, Phase.DECISION);
}
// lo mismo para Result(success=false) y para devolver un objeto null
```

### Propagación de datos (`core/behavior/datapropagation`)

Comprueba que el valor devuelto por una fase llega como **parámetro tipado** a la
siguiente. `trace.entries()` guarda los argumentos que recibió cada método:

```java
@RequiresImplementation
@Assertion(id = "AGENTICAI-DATA-002",
           strategy = "TriggerOutput returned by @Trigger is injectable as a parameter in @Decision")
public void triggerOutputIsInjectableInDecision() {
    llm.enqueueResponse("ok");
    events.fire(new DataPropagationEvent("input"));
    assertThat(trace.entries().get(1).args()[1]).isInstanceOf(TriggerOutput.class);
}
```

### Contrato del LLM (`core/behavior/llm`)

Cubre el **contrato de error** de `LargeLanguageModel` — validación de argumentos,
mapeo de placeholders `{}`, serialización JSON-B y la garantía de **aislamiento por
workflow** (el estado conversacional no se filtra entre ejecuciones). Un ejemplo
típico:

```java
@Assertion(id = "AGENTICAI-LLM-BHV-002",
           section = "LLM Interface, Positional Parameters",
           strategy = "more parameters than placeholders must throw IllegalArgumentException")
public void arityMoreParamsThanPlaceholdersThrows() {
    stub.reset();
    stub.enqueueResponse("ok");
    assertThatThrownBy(() -> llm.query("one {} here", "a", "b"))
            .isInstanceOf(IllegalArgumentException.class);
}
```

Fíjate en que el `id` es **obligatorio** en `@Assertion` (`section` y `strategy`
tienen como valor por defecto la cadena vacía): toda prueba del TCK debe nombrar el
requisito que verifica.

## Comandos de build

```bash
# Build completa (CI) — activa el contenedor Arquillian weld-embedded
mvn clean install -Pweld-embedded

# Solo el TCK y sus módulos upstream
mvn --projects tck --also-make verify

# Una clase standalone concreta (Failsafe, pruebas en src/main/java)
mvn -pl tck verify -Dgroups=standalone -Dit.test=AgentAnnotationTests

# Una clase deployed (requiere el perfil del contenedor)
mvn -pl tck verify -Pweld-embedded -Dit.test=AgentSmokeTest

# Generar los archivos de firma de la API
mvn -pl tck verify -Psignature-generation
```

Sin el perfil `weld-embedded`, las pruebas `@Deployed` se **excluyen por defecto** en
la configuración Maven del TCK.

---

## Test — Capítulo 4

**1.** ¿Por qué las pruebas del TCK viven en `src/main/java` y no en
`src/test/java`?

<details><summary>Ver respuesta</summary>

Porque son el **producto** del módulo: se compilan y empaquetan en un artefacto que
**quienes implementan** descargan y ejecutan contra su propia implementación. Las
pruebas de `src/test/java` no se empaquetan en el JAR. Solo las pruebas unitarias del
framework interno del TCK viven en `src/test/java`.
</details>

**2.** ¿Cómo decide la `ImplementationPresentCondition` que hay una implementación
compatible presente, y por qué es un opt-in explícito en lugar de un sondeo del
contenedor?

<details><summary>Ver respuesta</summary>

Lee, dentro del contenedor, la system property
**`jakarta.ai.agent.tck.implementation.present`** — las implementaciones la ponen a
`true` al ejecutar el TCK; ausente significa "baseline en CDI puro", así que el valor
por defecto no necesita configuración. Es un opt-in en lugar de un sondeo del
contenedor porque el enfoque anterior (comprobar si había un `Context` registrado
para `@WorkflowScoped`) no es portable sobre el baseline Jakarta EE 10: **el
`BeanManager` de CDI 4.0 no puede enumerar los contextos registrados**.
</details>

**3.** ¿Qué ocurre cuando la condición se evalúa en la JVM **cliente** de
Arquillian, fuera del contenedor?

<details><summary>Ver respuesta</summary>

Fuera del contenedor no se puede determinar la presencia de la implementación
(`CDI.current()` falla), así que la condición devuelve **enabled** y **aplaza** la
decisión real a la segunda evaluación, que ocurre dentro del contenedor, donde la
detección sí es fiable.
</details>

**4.** ¿Cuál es la diferencia de propósito entre `@RequiresImplementation` y
`@RequiresNoImplementation`?

<details><summary>Ver respuesta</summary>

`@RequiresImplementation` protege las pruebas que **necesitan el motor** (las fases
`@Decision`/`@Action`/`@Outcome` despachadas) — se omiten sobre CDI puro.
`@RequiresNoImplementation` protege las pruebas de **baseline en CDI puro** (p. ej.
que el trigger es invocable solo con CDI) — se omiten cuando hay implementación
presente, porque la aserción de comportamiento completa de la implementación las
sustituye.
</details>

**5.** ¿Para qué sirven `LargeLanguageModelStub.enqueueResponse(...)` y
`ExecutionTraceRecorder.assertOrder(...)` en una prueba de comportamiento típica?

<details><summary>Ver respuesta</summary>

`enqueueResponse` guioniza las respuestas del LLM (la prueba controla exactamente
qué contesta el "modelo", sin ninguna llamada a un servicio real), y el stub registra
cada llamada para las aserciones. `assertOrder(TRIGGER, DECISION, ACTION, OUTCOME)`
verifica que el motor ejecutó las fases en el orden que exige la especificación.
Juntos hacen la orquestación verificable de forma determinista.
</details>

---

➡️ Siguiente: [Capítulo 5 — Implementación Payara: la extensión CDI](05-implementacion-extension-cdi.md)
