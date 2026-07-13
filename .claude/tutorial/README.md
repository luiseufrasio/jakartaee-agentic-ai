# Tutorial — Jakarta Agentic AI (Spec + Implementação Payara + Samples)

Material de estudo para a apresentação na conferência da Payara (agosto/2026).
Cobre as três camadas que você vai apresentar:

| Camada | Onde está | O que é |
| --- | --- | --- |
| **Especificação** | `C:\Users\luise\git\ai\jakartaee-agentic-ai` | API `jakarta.ai.agent`, documento da spec e TCK |
| **Implementação** | `C:\Users\luise\git\Payara\appserver\agentic-ai\agentic-ai-core` | Runtime da Payara: extensão CDI, engine de workflow, backends LLM |
| **Samples** | `...\payara-samples\samples\agentic-ai-quickstart` e `...\samples\agentic-ai` | Quickstart (pergunta/resposta) e Tutorial Generator (geração + refinamento via chat) |

## Como usar

- **Modo interativo (recomendado):** digite `/tutorial` no Claude Code. O Claude
  apresenta capítulo por capítulo, responde dúvidas e aplica o quiz no final de
  cada um, corrigindo suas respostas.
- **Modo leitura:** leia os capítulos em ordem. As respostas dos quizzes ficam
  escondidas em blocos `▸ Ver resposta` (clique para expandir).

## Capítulos

1. [Visão geral e motivação](01-visao-geral.md) — o que é a spec, por que existe, arquitetura dos módulos
2. [O modelo de programação](02-modelo-de-programacao.md) — `@Agent`, `@Trigger`, `@Decision`, `@Action`, `@Outcome`, `@HandleException`, `@WorkflowScoped`
3. [LargeLanguageModel e erros](03-largelanguagemodel.md) — a fachada LLM, placeholders `{}`, JSON-B, `LLMException`
4. [O TCK](04-tck.md) — como a spec é verificada: `@Standalone`, `@Deployed`, `@Assertion`, stub e trace recorder
5. [Implementação Payara: a extensão CDI](05-implementacao-extensao-cdi.md) — descoberta de agentes, observer sintético, LLM default auto-vetado
6. [Implementação Payara: WorkflowEngine e escopo](06-implementacao-engine.md) — orquestração das fases, `WorkflowContext`, `ParameterResolver`, `@WorkflowScoped`
7. [Backends LLM e configuração](07-backends-llm.md) — MicroProfile Config, Ollama, Anthropic (Claude), Vertex, NoOp, prompt caching
8. [Os samples](08-samples.md) — quickstart e tutorial generator, linha a linha
9. [Roteiro da apresentação](09-roteiro-apresentacao.md) — narrativa sugerida, checklist de demo, perguntas prováveis da plateia + quiz final

Cada capítulo termina com um **quiz** — na apresentação você vai receber
perguntas parecidas da plateia, então trate o quiz como ensaio de Q&A.