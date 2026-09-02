# Tutorial — Jakarta Agentic AI (Spec + Implementación Payara + Samples)

Un tutorial práctico de Jakarta Agentic AI: la especificación, la implementación de
Payara y los samples. Cubre tres capas:

| Capa | Ubicación | Qué es |
| --- | --- | --- |
| **Especificación** | repositorio `jakartaee-agentic-ai` | La API `jakarta.ai.agent`, el documento de la especificación y el TCK |
| **Implementación** | `Payara\appserver\agentic-ai\agentic-ai-core` | El runtime de Payara: extensión CDI, motor de workflow, backends LLM |
| **Samples** | `examples/` en este repositorio | `quickstart` (pregunta/respuesta), `tutorial-generator` (generación + refinamiento por chat) y `course-content-studio` (dos agentes + compuerta de aprobación). Los gemelos con sabor Payara de los dos primeros, que llevan las pruebas Arquillian, viven en `payara-samples\samples\`. |

## Cómo usarlo

- **Modo lectura:** lee los capítulos en orden. Las respuestas de los tests están
  ocultas tras bloques `▸ Ver respuesta` (haz clic para expandir).
- **Modo interactivo:** abre la versión HTML (`tutorials/html/index.html`) para
  navegar por capítulos, resolver los tests y llevar el progreso.

## Capítulos

1. [Visión general y motivación](01-vision-general.md) — qué es la especificación, por qué existe, arquitectura de módulos
2. [El modelo de programación](02-modelo-de-programacion.md) — `@Agent`, `@Trigger`, `@Decision`, `@Action`, `@Outcome`, `@HandleException`, `@WorkflowScoped`
3. [LargeLanguageModel y errores](03-largelanguagemodel.md) — la fachada del LLM, placeholders `{}`, JSON-B, `LLMException`
4. [El TCK](04-tck.md) — cómo se verifica la especificación: `@Standalone`, `@Deployed`, `@Assertion`, stub y trace recorder
5. [Implementación Payara: la extensión CDI](05-implementacion-extension-cdi.md) — descubrimiento de agentes, observador sintético, LLM por defecto que se auto-veta
6. [Implementación Payara: WorkflowEngine y ámbito](06-implementacion-engine.md) — orquestación de fases, `WorkflowContext`, `ParameterResolver`, `@WorkflowScoped`
7. [Backends LLM y configuración](07-backends-llm.md) — MicroProfile Config, Ollama, Anthropic (Claude), Vertex, NoOp, prompt caching
8. [Los samples](08-samples.md) — quickstart, tutorial generator y course content studio, línea a línea
9. [Cierre: ejecutar los samples y FAQ](09-guia-presentacion.md) — repaso, cómo ejecutar los samples, FAQ + test final

Cada capítulo termina con un **test** para comprobar lo aprendido — revela la
respuesta y puntúate.
