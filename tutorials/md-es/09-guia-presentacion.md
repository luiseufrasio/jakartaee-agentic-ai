# Capítulo 9 — Cierre: ejecutar los samples y FAQ

## Repaso: la historia completa, de principio a fin

Las piezas que has visto encajan así:

1. **El problema.** Todo el mundo quiere agentes de IA; en Java, cada framework tiene
   su propio modelo propietario. La pregunta guía: "¿cómo sería el *Jakarta
   Persistence* de los agentes?"
2. **La especificación.** El modelo de fases (`Trigger → Decision* → Action* →
   Outcome` + `HandleException`); un agente completo cabe en una clase (el
   `QuestionAgent`); los tres patrones de retorno de `@Decision`; la fachada
   `LargeLanguageModel` con placeholders `{}`; `@WorkflowScoped`.
3. **El quickstart.** POST de una pregunta real → `[TRIGGER] → [DECISION] → [ACTION] →
   [OUTCOME]` en `server.log`; POST de una pregunta vacía → terminación anticipada.
   Corre sobre **Ollama local** (sin red, sin coste).
4. **Por dentro de la implementación.** El pipeline de la extensión CDI (la
   eliminación del `@Observes` + el observador sintético es la clave); el
   `WorkflowEngine`; el ámbito con `ThreadLocal`; el LLM que se auto-veta (que hace
   posible probar con stubs).
5. **El tutorial generator.** Generar la guía de un formulario con Claude; refinarla
   por chat; refinar un solo campo. Cambiar Ollama↔Claude es **un archivo de
   propiedades**.
6. **El Course Content Studio.** Dos agentes encadenados por eventos CDI con una
   compuerta de aprobación humana, fases ordenadas, memoria conversacional del
   workflow y corrección por LLM.
7. **El TCK y el camino por delante.** Cómo se demuestra la compatibilidad; el opt-in
   `jakarta.ai.agent.tck.implementation.present`, que separa las aserciones de
   baseline en CDI puro de las de comportamiento; lo que puede venir después (varios
   triggers, otras fuentes de eventos, configuración estandarizada del LLM).

## Ejecutar los samples — checklist

- [ ] Ollama instalado, `ollama pull gemma3:4b` hecho, el servicio respondiendo en
      `http://localhost:11434` (prueba: `ollama run gemma3:4b "hi"`).
- [ ] El `agentic-ai-core.jar` actual copiado en `glassfish/modules/` de la
      distribución + dominio reiniciado **limpiando la caché OSGi** (trampa clásica:
      JAR nuevo con caché vieja = clase vieja).
- [ ] Las credenciales del proveedor en la nube presentes **en el mismo
      shell/entorno que arranca el dominio** (el proceso del servidor hereda el
      entorno de su padre), *antes* de `asadmin restart-domain`. Para la
      configuración Vertex por defecto: `gcloud auth application-default login` más
      `ANTHROPIC_VERTEX_PROJECT_ID` / `CLOUD_ML_REGION`. Si cambias a
      `provider=anthropic`: `$env:ANTHROPIC_API_KEY = "sk-ant-..."`.
- [ ] Los tres WAR desplegados y probados (`quickstart.war`,
      `tutorial-generator.war`, `course.war`).
- [ ] `server.log` abierto en una terminal (`Get-Content -Wait -Tail 0`).
- [ ] Opción 100 % local: el quickstart corre sobre Ollama; el tutorial generator
      también puede caer a `provider=ollama` / `model=gemma3:12b` (descarga el modelo
      antes). El Course Content Studio **no** degrada bien — el timeout de 120 s por
      llamada del backend de Ollama es menor que el paso del cuestionario con un 12B
      en un portátil.
- [ ] Peticiones listas (sin escribir JSON a mano): un script o archivo `.http` con el
      POST válido, el POST vacío y los refinamientos.

## Preguntas frecuentes

**"¿Cómo se compara esto con LangChain4j / Spring AI?"**
No compite — estandariza. LangChain4j es una biblioteca (excelente) de un solo
proveedor; Jakarta Agentic AI es una **especificación** con TCK: programas contra
`jakarta.ai.agent` y cambias de implementación/proveedor sin reescribir. Una
implementación incluso puede usar LangChain4j por debajo — y `unwrap()` existe justo
para llegar a lo que la fachada no expone.

**"¿Y si el LLM alucina o falla a mitad del workflow?"**
Dos capas: `LLMException` (unchecked) para fallos del servicio, capturable con
`@HandleException` con una semántica de recuperación clara (retorno normal =
continuar; relanzar = parar); y respuestas tipadas vía JSON-B — si el modelo no
devuelve el JSON esperado, obtienes una `LLMException`, no datos corrompidos en
silencio. El tutorial generator muestra además defensas aplicadas (quitar vallas de
código, merge por campo).

**"¿Es asíncrono? ¿Escala?"**
En la 1.0 el workflow se ejecuta de forma síncrona en el hilo de `Event.fire` — lo que
mantiene simple el modelo de programación y deja el resultado disponible en la misma
petición. Nada impide que quien llama dispare el evento desde un executor o un hilo
virtual. El aislamiento es por contexto de workflow (un ThreadLocal en Payara). La
orquestación asíncrona es candidata para versiones futuras.

**"¿Por qué eventos CDI como trigger, y no un método que yo llame directamente?"**
Desacoplamiento (quien dispara no conoce al agente), es infraestructura que todo
servidor Jakarta EE ya tiene, y permite un fan-out natural (un evento, varios
agentes). La especificación ya prevé otras fuentes en el futuro (Messaging, REST,
programática).

**"¿Pueden colaborar varios agentes?"**
Sí, mediante eventos: la `@Action`/`@Outcome` de un agente puede inyectar un
`Event<X>` y disparar el trigger de otro agente. La orquestación multiagente de
primera clase es tema para versiones futuras.

**"¿Cuándo sale? ¿Es oficial?"**
Es una propuesta de especificación en desarrollo dentro del ecosistema Jakarta EE, con
una API, un documento de especificación, un TCK y una implementación funcional en
Payara. La hoja de ruta (varios triggers, configuración estandarizada del LLM) ya está
documentada en el Javadoc de la API.

**"¿Funciona en local? ¿Cuánto cuesta?"**
Quickstart: Ollama + gemma3:4b, coste cero, red cero. Tutorial generator: Claude en
Vertex por la calidad de la salida, con prompt caching para recortar coste — pero
también corre sobre Ollama. El Course Content Studio necesita la nube: el timeout de
120 s por llamada del backend de Ollama se queda corto para su paso del cuestionario en
un portátil.

## Mensajes clave

1. **Agentes como beans CDI** — el modelo de fases
   (`@Trigger/@Decision/@Action/@Outcome/@HandleException`) convierte "llamar a un
   LLM" en un workflow gestionado por el contenedor, con ámbitos, inyección,
   validación y tratamiento de errores estandarizados.
2. **Neutralidad de proveedor real** — el código del agente no sabe qué LLM lo sirve;
   cambiar Ollama↔Claude↔Vertex es configuración (MicroProfile Config en Payara).
3. **Una especificación de verdad** — con un TCK ejecutable (cada prueba atada a un
   requisito vía `@Assertion`) y una implementación completa dentro de un servidor de
   producción (una extensión CDI portable + motor), no un papel.

---

## Test final — atando cabos

**1.** Desde `trigger.fire(new Question("..."))` hasta el JSON de respuesta: describe
el camino, citando al menos la extensión, el observador, el motor, el contexto, la
resolución de parámetros y el papel del ámbito.

<details><summary>Ver respuesta</summary>

En el despliegue, la `AgenticAIExtension` eliminó el `@Observes` del trigger, aplicó el
`@WorkflowScoped` por defecto, validó los metadatos y registró el **observador
sintético** para `Question` + el `WorkflowScopeContext`. En el `fire`: el observador
llama a `WorkflowEngine.execute` → `activate()` pone el contexto en el hilo → se
resuelven el bean del agente y el LLM (`@Dependent`, uno por workflow) → se ejecuta el
`@Trigger` (con el evento ya sembrado en el `WorkflowContext`) → la `@Decision`
devuelve `Result(true, question)` (el details entra en el contexto) → la `@Action`
recibe el `Question` por tipo vía el `ParameterResolver`, consulta al LLM y escribe en
el `AnswerStore` → se ejecuta el `@Outcome` → el `finally` destruye los beans del
ámbito (`@PreDestroy`) y limpia el ThreadLocal → `fire` retorna y el recurso REST lee
el `AnswerStore`.
</details>

**2.** Nombra las tres `DefinitionException` más probables al escribir un agente
descuidado, y explica por qué la especificación prefiere fallar en el despliegue.

<details><summary>Ver respuesta</summary>

Dos `@Trigger`; dos `@Outcome`; mezclar fases con y sin orden explícito (o bien: un
`@Observes` genérico en un agente `@WorkflowScoped`; un agente sin `@Trigger`). Fallar
en el despliegue (fail fast) convierte un error estructural en feedback inmediato y
determinista, en lugar de comportamiento indefinido en la primera ejecución en
producción — la misma filosofía que CDI aplica a los beans malformados.
</details>

**3.** Cambiaste el tutorial generator a `provider=anthropic`, devuelve una guía vacía
y el log muestra `IllegalStateException: ... no API key found`. ¿Cuál fue el error
operativo y cuál es el arreglo?

<details><summary>Ver respuesta</summary>

La `ANTHROPIC_API_KEY` no estaba en el entorno **del proceso del servidor** —
probablemente exportada en otro shell o después de arrancar el dominio. Arreglo:
`$env:ANTHROPIC_API_KEY = "sk-ant-..."` y **después** `asadmin restart-domain` en el
mismo shell (el servidor hereda el entorno de quien lo arranca). La misma trampa aplica
a la configuración Vertex que viene por defecto, donde lo que falta son las ADC o
`ANTHROPIC_VERTEX_PROJECT_ID`. Plan B: cambiar a `provider=ollama` en el
microprofile-config.
</details>

**4.** Alguien afirma: "esto es solo un envoltorio de anotaciones alrededor de una
llamada HTTP al LLM". Rebátelo con tres capacidades concretas del contenedor que el
envoltorio manual no tendría.

<details><summary>Ver respuesta</summary>

(1) **Ciclo de vida gestionado**: un contexto `@WorkflowScoped` con creación y
destrucción automática de beans y `@PreDestroy`, aislado entre ejecuciones
concurrentes; (2) **orquestación declarativa**: orden de fases
(`@Priority`/`order`/declaración), terminación anticipada estandarizada, propagación de
datos por tipo entre fases y despacho de excepciones al handler más específico con
semántica de continuar/parar; (3) **integración con la plataforma**: Bean Validation en
los parámetros de fase, interceptores CDI, y neutralidad de proveedor con estado
conversacional por workflow garantizado por la especificación (más el TCK que lo
demuestra todo).
</details>

**5.** En una frase cada uno, ¿cuál es el papel de: la API de la especificación, el
TCK, `agentic-ai-core`, el quickstart y el tutorial generator en este tutorial?

<details><summary>Ver respuesta</summary>

**API**: el contrato neutral respecto al proveedor (`jakarta.ai.agent`) contra el que
programa quien desarrolla. **TCK**: la prueba ejecutable de que una implementación
respeta el contrato. **agentic-ai-core**: la implementación de Payara — extensión CDI +
motor + backends LLM. **Quickstart**: el "hola mundo" que enseña las cuatro fases en
cinco clases. **Tutorial generator**: el caso de uso real que muestra el refinamiento
iterativo por chat y las prácticas defensivas de producción.
</details>

---

🏁 Fin del tutorial. Repasa los tests que fallaste y ejecuta los samples tú mismo.
