# Capítulo 5 — Implementación Payara: la extensión CDI

A partir de aquí dejamos la especificación y entramos en el runtime de Payara
(`fish.payara.ai.agent.*`, el módulo `agentic-ai-core`). La puerta de entrada es la
**extensión CDI portable** `AgenticAIExtension` — convierte las clases `@Agent` en
workflows ejecutables usando únicamente mecanismos estándar de CDI (el SPI de
extensiones).

## Visión general del arranque

```
Despliegue de la aplicación
  │
  ├─ ProcessAnnotatedType (por clase) ──► processAgent()
  │     • aplica el @WorkflowScoped por defecto cuando no hay ámbito
  │     • ELIMINA el @Observes del método @Trigger
  │     • recopila la clase del agente
  │
  ├─ ProcessManagedBean (por bean) ──► watchForLlm()
  │     • marca si la aplicación aporta su propio LargeLanguageModel
  │
  └─ AfterBeanDiscovery ──► afterBeanDiscovery()
        • registra el WorkflowScopeContext (el Context de @WorkflowScoped)
        • crea el WorkflowEngine
        • por agente: valida los metadatos + registra un OBSERVADOR SINTÉTICO
        • si la app no trajo LLM: registra el LLM por defecto (backend por config)
```

## `processAgent` — preparar cada agente

Para cada tipo anotado con `@Agent`:

1. **Ámbito por defecto.** Si la clase no tiene ni `@WorkflowScoped` ni
   `@ApplicationScoped`, la extensión añade `WorkflowScoped.Literal.INSTANCE` vía
   `configureAnnotatedType()` — así se implementa en la práctica el "por defecto es
   WorkflowScoped" de la especificación.
2. **Eliminar el `@Observes` del trigger.** Este es el truco central de la
   implementación: si quien desarrolla escribió `@Trigger void on(@Observes MyEvent
   e)`, CDI invocaría el método **directamente** como observador normal — fuera del
   motor, sin contexto de workflow activo y sin las fases siguientes. La extensión
   **elimina la anotación `@Observes`** del parámetro, y el **observador sintético**
   que se registra después pasa a ser el único punto de entrada. Esto evita la
   **doble invocación** del trigger y garantiza que el contexto de workflow envuelva
   toda la ejecución.

## `watchForLlm` — el LLM por defecto que se auto-veta

La extensión observa cada `ProcessManagedBean` y levanta la bandera
`appProvidesLlm` si algún bean **de la aplicación** tiene `LargeLanguageModel` entre
sus tipos.

En `afterBeanDiscovery`, **solo si la aplicación no trajo su propio LLM**, el
runtime registra el suyo: un bean `@Dependent` creado con
`new LargeLanguageModelImpl(backend)`, donde el backend viene de
`LlmBackendFactory.create(config)` (capítulo 7).

¿Por qué este baile? Si el runtime registrase su LLM incondicionalmente y la
aplicación también aportase uno (el stub del TCK, o un bean real respaldado por
LangChain4j), inyectar `LargeLanguageModel` provocaría una
**`AmbiguousResolutionException`**. El auto-veto garantiza: **el LLM de la
aplicación siempre gana; el del runtime es solo un fallback**. Fíjate en que los
beans sintéticos no pasan por `ProcessManagedBean`, así que el LLM por defecto no
puede engañar a la detección.

## `afterBeanDiscovery` — contexto, motor y observadores sintéticos

```java
afterBeanDiscovery.addContext(workflowScopeContext);            // registra @WorkflowScoped
// ...
afterBeanDiscovery.addObserverMethod()
        .beanClass(agentClass)
        .observedType(eventType)                                 // el tipo de evento del @Trigger
        .notifyWith(ctx -> workflowEngine.execute(agentMetadata, ctx.getEvent()));
```

Por cada agente se registra **un observador sintético** para el tipo de evento del
trigger. Cuando alguien llama a `event.fire(new Question(...))`, este observador lo
recibe — y delega en `WorkflowEngine.execute(...)`, que ejecuta el workflow
completo. Los agentes cuyo trigger no declara tipo de evento se omiten (reservado
para el disparo programático futuro).

El **tipo de evento** se extrae del trigger con esta precedencia: un parámetro
anotado explícitamente con `@Observes` (aunque la anotación se vaya a eliminar,
declara la intención); en su defecto, el primer parámetro que **no** sea un
`LargeLanguageModel`.

## `buildMetadata` — validación en tiempo de despliegue

Los metadatos de cada agente (`AgentMetadata`) se construyen por reflexión y se
**validan en el despliegue** — la filosofía es fallar rápido: un error estructural
mata el despliegue con una `DefinitionException` en lugar de reventar en tiempo de
ejecución. Los casos:

| Violación | Resultado |
| --- | --- |
| Más de un `@Trigger` | `DefinitionException` |
| Ningún `@Trigger` | `DefinitionException` |
| Más de un `@Outcome` | `DefinitionException` |
| Un agente `@WorkflowScoped` con `@Observes` fuera del `@Trigger` | `DefinitionException` |
| Mezclar fases con y sin orden explícito | `DefinitionException` ("Inconsistent order") |

Tras la validación:

- **Fases ordenadas:** si alguna fase lleva orden explícito (`@Priority` o
  `order != 0` — encapsulado en `PhaseMethod.isExplicitlyOrdered()`), se ordena por
  `sortKey`; si no, se ordena por **orden de declaración en el fuente**, obtenido vía
  `ClassMethodOrder`, que lee la **tabla de métodos del archivo `.class`** — más
  fiable que `getDeclaredMethods()`, cuyo orden la JVM no garantiza.
- **Handlers ordenados de más específico a más genérico** (comparación con
  `isAssignableFrom` entre los tipos de excepción de los parámetros), preparando la
  selección de handler del motor.

## Bean Validation opcional

La extensión intenta construir un `ExecutableValidator`
(`Validation.buildDefaultValidatorFactory()`); si no hay ningún proveedor de Bean
Validation en el classpath, devuelve `null` y el motor simplemente **omite** la
validación de parámetros — integración elegante, no obligatoria.

---

## Test — Capítulo 5

**1.** ¿Por qué la extensión **elimina** el `@Observes` del método `@Trigger`
durante `ProcessAnnotatedType`?

<details><summary>Ver respuesta</summary>

Si el `@Observes` se quedara, el contenedor CDI invocaría el método del trigger
**directamente** como observador normal — sin pasar por el `WorkflowEngine`, sin
contexto `@WorkflowScoped` activo y sin las fases siguientes; y como el motor también
registra un observador sintético para el mismo evento, el trigger se invocaría **dos
veces**. Al eliminar la anotación, el observador sintético se convierte en el
**único punto de entrada** del workflow.
</details>

**2.** La aplicación se despliega con su propio bean que implementa
`LargeLanguageModel` (p. ej. el stub del TCK). ¿Qué hace el runtime de Payara con su
LLM por defecto, y qué pasaría sin ese mecanismo?

<details><summary>Ver respuesta</summary>

El runtime **no registra** su LLM por defecto (la bandera `appProvidesLlm` la levantó
`ProcessManagedBean`). Sin este "auto-veto" habría dos beans elegibles para el mismo
punto de inyección y el despliegue fallaría con una
**`AmbiguousResolutionException`**. Regla práctica: el LLM de la aplicación siempre
gana; el del runtime es un fallback.
</details>

**3.** Nombra tres estructuras de agente que matan el despliegue con una
`DefinitionException`.

<details><summary>Ver respuesta</summary>

Cualesquiera tres de estas: (a) dos métodos `@Trigger`; (b) ningún `@Trigger`;
(c) dos métodos `@Outcome`; (d) un agente `@WorkflowScoped` con un método `@Observes`
fuera del trigger; (e) mezclar `@Decision`/`@Action` con orden explícito e implícito
("Inconsistent order").
</details>

**4.** Cuando ninguna fase declara `@Priority`/`order`, ¿cómo obtiene la
implementación el orden de declaración de los métodos, dado que
`getDeclaredMethods()` no garantiza ningún orden?

<details><summary>Ver respuesta</summary>

Mediante `ClassMethodOrder`, que lee la **tabla de métodos directamente del bytecode
del archivo `.class`**, donde los métodos aparecen en el orden en que se declararon
en el fuente. Así se implementa de forma determinista el requisito de la
especificación (el orden de declaración como fallback).
</details>

**5.** ¿Qué hace exactamente el observador sintético registrado en
`afterBeanDiscovery` cuando se dispara el evento del trigger?

<details><summary>Ver respuesta</summary>

Llama a `workflowEngine.execute(agentMetadata, eventContext.getEvent())` — es decir,
entrega el evento al motor, que activa el contexto `@WorkflowScoped`, resuelve el
bean del agente y el LLM, y ejecuta todas las fases en orden (trigger → decisiones/
acciones → outcome), con despacho de excepciones. El observador es el enlace entre el
mundo CDI (`Event.fire`) y el motor de orquestación.
</details>

---

➡️ Siguiente: [Capítulo 6 — WorkflowEngine y el ámbito](06-implementacion-engine.md)
