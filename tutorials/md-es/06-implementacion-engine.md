# Capítulo 6 — Implementación Payara: WorkflowEngine y el ámbito de workflow

## `WorkflowEngine.execute` — la columna vertebral

Una sola llamada a `execute(agentMetadata, triggerEvent)` ejecuta el workflow
completo para un evento. El flujo, con los detalles que importan:

```java
workflowScopeManager.activate();                    // 1. activa @WorkflowScoped en el hilo
WorkflowContext ctx = new WorkflowContext();
ctx.add(triggerEvent);                              // 2. siembra el evento en el contexto
try {
    agentInstance = resolveBean(agentClass);        // 3. resuelve el bean del agente
    llm = resolveBean(LargeLanguageModel.class);    //    y el LLM (uno por workflow)

    Object r = invokePhase(triggerMethod, ...);     // 4. @Trigger
    ctx.add(r);                                     //    el retorno entra en el contexto

    for (PhaseMethod phase : sortedPhases) {        // 5. @Decision/@Action preordenadas
        Object result = invokePhase(phase, ...);
        if (phase is DECISION) {
            if (!shouldContinue(result)) return;    //    terminación anticipada
            addDecisionResultToContext(result);     //    publica Result.details()
        } else {
            ctx.add(result);
        }
    }

    invokePhase(outcomeMethod, ...);                // 6. @Outcome (si existe)
} catch (Exception e) {
    // 7. despacho a @HandleException (ver abajo)
} finally {
    workflowScopeManager.deactivate();              // 8. SIEMPRE destruye el contexto
}
```

Puntos que merece la pena destacar:

- **El workflow se ejecuta en el hilo del llamador** — `Event.fire()` es síncrono,
  así que quien hizo el POST REST espera a que el workflow termine (por eso los
  samples pueden devolver la respuesta del LLM en la misma respuesta HTTP).
- El contexto se destruye **siempre** (`finally`) — éxito, terminación anticipada o
  fallo.
- El LLM se resuelve **una vez por ejecución** — como el bean es `@Dependent`, cada
  workflow obtiene su propia instancia, y de ahí viene el aislamiento conversacional
  que exige la especificación.

## Semántica de terminación (`shouldContinue`)

```java
return switch (result) {
    case null      -> false;   // objeto null ⇒ parar
    case Boolean b -> b;       // false ⇒ parar
    case Result r  -> r.success();
    default        -> true;    // cualquier objeto no nulo ⇒ continuar
};
```

Y la publicación de datos de la decisión: para un `Result`, el `details()` entra en
el contexto; un `Boolean` no lleva datos; cualquier otro objeto entra tal cual.

## `WorkflowContext` — propagación de datos por tipo

Una simple lista de los valores producidos, en orden de producción. `add(null)` se
ignora (las fases void no aportan nada). `getByType(Class)` recorre **del más
reciente al más antiguo** — si dos fases produjeron el mismo tipo, la fase siguiente
recibe el valor **más fresco**.

## `ParameterResolver` — el orden de resolución de parámetros

Para cada parámetro de un método de fase, en este orden:

1. Un tipo asignable a `LargeLanguageModel` → la instancia de LLM del workflow;
2. (solo para `@HandleException`) la excepción en vuelo, si el tipo del parámetro
   coincide;
3. Un valor del `WorkflowContext` por tipo (el más reciente primero);
4. Un bean CDI resuelto vía el `BeanManager`;
5. Nada encontrado → `null`.

Esto es lo que hace posibles firmas como
`@Action void handle(Fraud fraud, BankTransaction tx, AuditService audit)` — dos
objetos que vienen de fases anteriores más un bean CDI, todos resueltos de forma
transparente.

## Despacho de excepciones — el camino menos obvio

Cuando cualquier fase lanza:

1. La excepción se **desenvuelve** de la `InvocationTargetException` reflexiva (el
   handler ve la causa original, no el envoltorio).
2. `dispatchException` busca, entre los handlers cuyo parámetro de excepción es
   compatible (`isInstance`), el tipo **más específico** (más derivado).
3. **Ningún handler** → la excepción se relanza al contenedor (una RuntimeException
   directamente; una checked envuelta en una RuntimeException).
4. **El handler lanza** → esa excepción se propaga al contenedor — **sin
   tratamiento recursivo** (un handler nunca trata el fallo de otro handler).
5. **El handler retorna normalmente** → recuperación. Y aquí el detalle fino: el
   motor ejecuta entonces el `@Outcome` como **fase de cierre de la recuperación** —
   pero **solo si el `@Outcome` no fue la fase que lanzó la excepción original** (la
   bandera `outcomeAttempted` evita reinvocar un outcome que acaba de fallar).

## Bean Validation en las fases

Antes de invocar cualquier fase, el motor valida los argumentos resueltos con el
`ExecutableValidator` (cuando está disponible): restricciones como `@Valid` y
`@NotNull` en los parámetros de los métodos de fase. Una violación ⇒
`ConstraintViolationException`, que se enruta a los métodos `@HandleException`
**como cualquier otro fallo**.

## `WorkflowScopeContext` — `@WorkflowScoped` por dentro

Implementa `AlterableContext` con almacenamiento en **`ThreadLocal`**:

```java
private static final ThreadLocal<Map<Contextual<?>, BeanInstance<?>>> STORE = ...;
```

- `activate()` pone un mapa vacío en el hilo → contexto activo;
- `get(contextual, creationalContext)` crea la instancia del bean en el primer
  acceso y la memoiza (una instancia por bean y por workflow);
- `deactivate()` **destruye todos los beans** (disparando `@PreDestroy`) y elimina el
  `ThreadLocal`;
- acceder con el contexto inactivo ⇒ `ContextNotActiveException`.

Como cada workflow se ejecuta en el hilo de `Event.fire()`, el `ThreadLocal`
proporciona **aislamiento entre workflows concurrentes** de forma gratuita: dos
peticiones REST simultáneas activan contextos independientes en hilos distintos.

Registrar este `Context` es también lo que convierte a Payara en una implementación
*compatible* en el sentido de la especificación — aunque el TCK no lo sondee: sobre
el baseline Jakarta EE 10, el `BeanManager` de CDI 4.0 no puede enumerar los
contextos registrados, así que el TCK pide a la implementación que se declare
mediante la system property `jakarta.ai.agent.tck.implementation.present`
(capítulo 4).

---

## Test — Capítulo 6

**1.** ¿Por qué puede el sample REST devolver la respuesta del LLM en la **misma**
respuesta HTTP que disparó el agente?

<details><summary>Ver respuesta</summary>

Porque `Event.fire(...)` es **síncrono** y el `WorkflowEngine` ejecuta todo el
workflow **en el hilo del llamador**. Cuando `fire` retorna, todas las fases
(incluida la llamada al LLM en la `@Action`) ya se han ejecutado, y el recurso REST
puede leer el resultado (del `AnswerStore`/`TutorialStore`) y devolverlo en la misma
petición.
</details>

**2.** Una `@Decision` devuelve un objeto `Plan` y, más adelante, una `@Action`
devuelve también un `Plan`. Un `@Outcome` declara un parámetro `Plan`. ¿Qué instancia
recibe, y por qué?

<details><summary>Ver respuesta</summary>

La de la **`@Action`** — la más reciente. `WorkflowContext.getByType` recorre los
valores producidos **del más reciente al más antiguo**, garantizando que las fases
posteriores siempre vean el valor más fresco cuando varios objetos producidos
comparten tipo.
</details>

**3.** Enumera el orden de precedencia que usa el `ParameterResolver` para rellenar
cada parámetro de un método de fase.

<details><summary>Ver respuesta</summary>

1. `LargeLanguageModel` (la instancia del workflow); 2. la excepción en vuelo (solo
para `@HandleException`); 3. un valor del `WorkflowContext` por tipo (el más reciente
primero); 4. un bean CDI vía el `BeanManager`; 5. `null` si nada coincide.
</details>

**4.** Una `@Action` lanza una `LLMException`; un `@HandleException(LLMException)`
la registra y retorna normalmente. El agente tiene un `@Outcome`. Describe qué hace
el motor, y qué cambiaría si la excepción la hubiera lanzado el propio `@Outcome`.

<details><summary>Ver respuesta</summary>

Con el fallo en la `@Action`: el motor selecciona el handler más específico, este
retorna normalmente (recuperación) y el motor **ejecuta entonces el `@Outcome`** como
fase de cierre de la recuperación. Si quien hubiera lanzado fuera el propio
`@Outcome`, la bandera `outcomeAttempted` impediría **reinvocar** el outcome tras la
recuperación — el handler se ejecuta, pero el outcome no se intenta de nuevo.
</details>

**5.** ¿Cómo garantiza el `WorkflowScopeContext` el aislamiento entre dos workflows
que corren a la vez, y qué les ocurre a los beans `@WorkflowScoped` al final?

<details><summary>Ver respuesta</summary>

El almacenamiento es un mapa bean→instancia en **`ThreadLocal`**, y cada workflow se
ejecuta en su propio hilo (el de `Event.fire`), así que los contextos nunca se ven
entre sí. En el `finally` del motor, `deactivate()` **destruye todas las instancias**
(invocando `@PreDestroy`) y elimina el `ThreadLocal` — el contexto muere con el
workflow, tanto en éxito como en fallo.
</details>

**6.** Un parámetro `@NotNull` de una `@Decision` llega nulo. ¿Qué ocurre, y dónde se
puede tratar?

<details><summary>Ver respuesta</summary>

El motor valida los argumentos con el `ExecutableValidator` **antes** de invocar la
fase; la violación se convierte en una `ConstraintViolationException`, que se enruta
a los métodos `@HandleException` del agente como cualquier otra excepción de fase (el
handler puede recuperarse o dejarla propagar). Si no hay ningún proveedor de Bean
Validation en el classpath, la validación simplemente se omite.
</details>

---

➡️ Siguiente: [Capítulo 7 — Backends LLM y configuración](07-backends-llm.md)
