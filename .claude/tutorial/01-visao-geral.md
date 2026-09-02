# Capítulo 1 — Visão geral e motivação

## O que é o Jakarta Agentic AI

O **Jakarta Agentic AI** é uma especificação Jakarta EE (pacote `jakarta.ai.agent`)
para construir **agentes de IA** de forma vendor-neutral. Um agente é um **bean CDI**
que encapsula comportamento autônomo orientado a objetivos: ele **percebe** um evento,
**raciocina** (tipicamente consultando um LLM), **decide** se e como prosseguir e
**age** — tudo dentro de um workflow com fases bem definidas.

A analogia que funciona bem em palestra: assim como o Jakarta Persistence padronizou
o acesso a bancos relacionais (você programa contra `EntityManager`, e Hibernate ou
EclipseLink implementam), o Jakarta Agentic AI padroniza a construção de agentes —
você programa contra anotações e a interface `LargeLanguageModel`, e o servidor de
aplicação (Payara, no nosso caso) fornece o motor de orquestração e a integração com
o provedor de LLM.

## Por que uma spec para agentes?

Hoje cada framework de IA em Java (LangChain4j, Spring AI, etc.) tem seu próprio
modelo de programação. Problemas que a spec ataca:

1. **Lock-in de vendor** — trocar de provedor de LLM ou de framework exige reescrever
   o código do agente. Com a spec, a seleção do provedor é configuração do servidor
   (na Payara, via MicroProfile Config), não código.
2. **Falta de integração com o container** — agentes precisam de injeção de
   dependências, escopos, eventos, validação, transações. Em vez de reinventar isso,
   a spec **se apoia no CDI**: o trigger é um observer de evento CDI, o agente é um
   bean, o escopo do workflow é um escopo CDI customizado.
3. **Workflows ad-hoc** — sem um modelo de fases, cada aplicação inventa sua própria
   máquina de estados. A spec define um ciclo de vida padrão:
   `Trigger → Decision* → Action* → Outcome`, com `HandleException` transversal.

### Exemplo prático: trocando GPT (OpenAI) por Claude (Anthropic)

O mesmo requisito — "a partir de hoje usamos Claude" — resolvido nos três mundos.

**Com LangChain4j**, a escolha do provedor está *compilada* na aplicação: a classe
de wiring e a dependência Maven são específicas do vendor.

```java
// ANTES — pom.xml: dev.langchain4j:langchain4j-open-ai
ChatModel model = OpenAiChatModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-4o")
        .build();
```

```java
// DEPOIS — trocar a dependência no pom.xml para dev.langchain4j:langchain4j-anthropic,
// reescrever o wiring e recompilar/reempacotar a aplicação
ChatModel model = AnthropicChatModel.builder()
        .apiKey(System.getenv("ANTHROPIC_API_KEY"))
        .modelName("claude-opus-4-8")
        .build();
```

O código que *usa* a interface `ChatModel` até sobrevive — mas a troca exige mudar
dependência + código de construção, rebuild e redeploy. E a abstração pertence a
uma biblioteca de um único fornecedor, não a um padrão.

**Com Spring AI**, é preciso trocar o starter no `pom.xml`
(`spring-ai-starter-model-openai` → `spring-ai-starter-model-anthropic`) e migrar o
bloco de propriedades (`spring.ai.openai.*` → `spring.ai.anthropic.*`). O código
que injeta `ChatClient` pode ficar igual — justo reconhecer —, mas continua sendo
rebuild da aplicação, a abstração só existe dentro do Spring, e há uma única
implementação dela, sem spec nem TCK que garanta comportamento portável.

**Com Jakarta Agentic AI**, o agente injeta a interface **da plataforma** e não
menciona provedor nenhum:

```java
@Agent
public class QuestionAgent {

    @Inject
    LargeLanguageModel model;   // jakarta.ai.agent — nenhum vendor aqui

    @Action
    void generate(Question question, AnswerStore answers) {
        String answer = model.query("Answer concisely: {}", question.text());
        answers.put(question.text(), answer);
    }
}
```

A troca inteira é **um arquivo de configuração** (na Payara, MicroProfile Config):

```properties
# ANTES                                    # DEPOIS
payara.agentic.llm.provider=ollama         payara.agentic.llm.provider=anthropic
payara.agentic.llm.model=gemma3:4b         payara.agentic.llm.model=claude-opus-4-8
```

Zero mudança de código, zero mudança no `pom.xml` (o backend HTTP do provedor vive
no **servidor**, não no WAR), zero recompilação — no máximo um redeploy. É o mesmo
salto que o Jakarta Persistence deu sobre o JDBC artesanal: o provider virou
detalhe de configuração. Os três samples do capítulo 8 provam isso ao vivo: têm
agentes escritos da mesma forma, um rodando em Ollama local e outro em Claude, e a
diferença entre eles é só o `microprofile-config.properties`.

## O modelo mental: workflow de fases

```
   evento CDI
       │
       ▼
   │ @Trigger │──▶│ @Decision │──▶ │ @Action │──▶ │ @Outcome │
   (obrigatório,   (0..N, pode       (0..N)          (0..1, void,
    exatamente 1)  parar o fluxo)                  encerra o contexto)

              @HandleException (0..N) captura exceções de QUALQUER fase
```

Pontos-chave:

- **Trigger** é a única fase obrigatória — exatamente **um** método por agente na
  versão 1.0 (restrição que deve ser relaxada no futuro).
- **Decisions e Actions podem ser intercalados** em qualquer sequência, permitindo
  desde `Trigger + Action` (execução simples) até
  `Trigger + Decision + Action + Decision + Action` (branching complexo).
- Uma **Decision pode encerrar o workflow** (retornando `false`, `null` ou
  `Result(false, ...)`) — as fases seguintes e o Outcome **não** executam.
- **Outcome** marca o fim do workflow com sucesso; depois dele o container destrói o
  contexto do workflow.
- Os **dados fluem entre as fases por tipo**: o que uma fase retorna fica disponível
  como parâmetro das fases seguintes (injeção posicional por tipo, sem passar
  parâmetro manualmente).

## Arquitetura do repositório da spec

O projeto é um build Maven multi-módulo com quatro módulos:

| Módulo | Conteúdo |
| --- | --- |
| `api/` | O pacote `jakarta.ai.agent`: 7 anotações, 1 interface (`LargeLanguageModel`), 1 record (`Result`), 1 exceção (`LLMException`). **Nenhum código de implementação.** |
| `spec/` | O documento da especificação em AsciiDoc (`jakarta-agentic-ai.adoc`). |
| `tck/` | O Technology Compatibility Kit — os testes que qualquer implementação precisa passar para se declarar compatível (capítulo 4). |
| `examples/` | Cinco exemplos executáveis: `quickstart`, `tutorial-generator`, `course-content-studio`, `fraud-detection`, `docs-agent` (capítulo 8). |

## Decisões de design fundamentais

Estas são as decisões que mais geram perguntas — memorize as justificativas:

1. **CDI-first.** O agente é um bean CDI; o trigger é disparado por eventos CDI
   (`Event.fire(...)`). Versões futuras podem adicionar outras fontes (Jakarta
   Messaging, REST, invocação programática), mas a 1.0 é CDI puro. Isso dá de graça:
   injeção, interceptors, eventos, escopos.
2. **Jakarta JSON Binding (JSON-B) para serialização** — não Jackson. Motivo:
   comportamento **portável e consistente** entre implementações; JSON-B já é parte
   da plataforma Jakarta EE.
3. **Baseline: Java 17, Jakarta EE 10 (portanto CDI 4.0).** Declarado uma única
   vez no `pom.xml` raiz (`maven.compiler.release`, `jakarta.ee.version`) e
   garantido pelo Maven Enforcer plugin.
4. **A fachada `LargeLanguageModel` é minimalista de propósito.** Na 1.0, cada
   implementação escolhe como configurar o provedor. Versões futuras padronizarão a
   seleção de provedor e configurações comuns (temperature, max tokens) — o mesmo
   caminho evolutivo do Jakarta Persistence com seus providers.
5. **Estado conversacional por workflow.** Mesmo com agente `@ApplicationScoped`, a
   conversa com o LLM é isolada por execução de workflow — duas execuções
   concorrentes nunca misturam histórico.

## Divisão de responsabilidades spec × implementação

Uma sutileza importante (aparece no TCK): **CDI puro consegue invocar o `@Trigger`**
(é só um observer de evento), mas as fases `@Decision`, `@Action` e `@Outcome`
exigem um **motor de orquestração** — é isso que a implementação da Payara fornece
(`agentic-ai-core`). O TCK usa as condições `@RequiresImplementation` /
`@RequiresNoImplementation` para separar o que é testável com CDI puro do que
precisa de uma implementação compatível.

---

## Quiz — Capítulo 1

**1.** Qual das fases do workflow é obrigatória, e quantos métodos dessa fase um
agente pode declarar na versão 1.0?

<details><summary>Ver resposta</summary>

`@Trigger` é a única fase obrigatória, e o agente deve declarar **exatamente um**
método `@Trigger`. Mais de um (ou nenhum) é erro de deployment
(`DefinitionException` na implementação Payara). A restrição de um único trigger
deve ser relaxada em versões futuras para suportar múltiplos pontos de entrada.
</details>

**2.** Por que a spec exige Jakarta JSON Binding em vez de deixar cada implementação
escolher sua biblioteca de serialização (por exemplo, Jackson)?

<details><summary>Ver resposta</summary>

Para garantir **comportamento portável e consistente** entre implementações: o
mesmo objeto de domínio deve produzir a mesma serialização no prompt em qualquer
implementação compatível. Além disso, JSON-B já é uma spec da plataforma Jakarta EE,
então não adiciona dependência externa.
</details>

**3.** O que acontece com o restante do workflow quando um método `@Decision`
retorna `false`?

<details><summary>Ver resposta</summary>

O workflow **termina imediatamente**: as fases `@Decision`/`@Action` restantes e o
`@Outcome` **não** são executados. Isso não é um erro — é terminação antecipada
normal (early termination). O contexto `@WorkflowScoped` é destruído normalmente.
</details>

**4.** Um colega pergunta: "se o agente é um bean CDI comum, por que preciso da
implementação da Payara? O Weld sozinho não roda o agente?" O que você responde?

<details><summary>Ver resposta</summary>

CDI puro consegue apenas **disparar o `@Trigger`** — ele é, na essência, um observer
de evento CDI. Mas orquestrar as fases seguintes (`@Decision`, `@Action`,
`@Outcome`), propagar dados entre elas por tipo, aplicar as regras de terminação e
despachar exceções para `@HandleException` exige um **motor de orquestração**, que é
exatamente o que a implementação compatível (o `agentic-ai-core` da Payara) fornece
via extensão CDI portável.
</details>

**5.** Cite os quatro módulos Maven do repositório da spec e o papel de cada um.

<details><summary>Ver resposta</summary>

- `api` — os tipos do pacote `jakarta.ai.agent` (anotações + interfaces, sem
  implementação);
- `spec` — o documento da especificação em AsciiDoc;
- `tck` — os testes de compatibilidade que implementações devem passar;
- `examples` — exemplos de uso da API.
</details>

---

➡️ Próximo: [Capítulo 2 — O modelo de programação](02-modelo-de-programacao.md)
