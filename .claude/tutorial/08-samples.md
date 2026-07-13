# Capítulo 8 — Os samples

Dois samples, dois papéis na apresentação: o **quickstart** ensina o modelo de
programação em 5 classes; o **tutorial generator** mostra um caso de uso real com
loop de refinamento via chat.

---

## Sample 1 — `agentic-ai-quickstart`

**O menor agente possível que exercita as quatro fases.** Um POST REST dispara um
evento CDI; o agente responde a pergunta com o LLM configurado.

```
POST /agentic-ai-quickstart/api/ask  { "question": "..." }  →  { "question", "answer" }
```

### O fluxo completo, classe por classe

**`Question`** — um record simples, o **evento CDI** que dispara o workflow.
*Intencionalmente sem constraints de validação*, para que uma pergunta em branco
chegue à `@Decision` e demonstre a terminação antecipada.

**`AskResource`** (JAX-RS, `@RequestScoped`):

```java
@Inject Event<Question> trigger;
@Inject AnswerStore answers;

trigger.fire(question);          // roda o workflow INTEIRO sincronamente
String answer = answers.get(text); // lê o resultado produzido pela @Action
```

O comentário no código é a alma da demo: `Event.fire` é síncrono, então o workflow
completo (incluindo a chamada ao LLM) termina **antes** do `fire` retornar.

**`QuestionAgent`** — as quatro fases, cada uma logando seu prefixo para o
`server.log` contar a história:

```java
@Agent(name = "QuestionAgent", description = "Answers a question using the configured LLM backend.")
public class QuestionAgent {
    @Inject LargeLanguageModel model;
    @Inject AnswerStore answers;

    @Trigger  void onQuestion(@Valid Question question) { ... }        // [TRIGGER]
    @Decision Result hasContent(Question question) {                    // [DECISION]
        boolean proceed = question.text() != null && !question.text().isBlank();
        return new Result(proceed, question);
    }
    @Action   void generate(Question question) {                        // [ACTION]
        String answer = model.query("Answer concisely in one short paragraph: {}",
                                    question.text());
        answers.put(question.text(), answer);
    }
    @Outcome  void complete(Question question) { ... }                   // [OUTCOME]
}
```

Repare nos detalhes didáticos:

- **Nenhuma anotação de escopo** → o runtime aplica `@WorkflowScoped` (default da
  spec, via extensão).
- A `@Decision` usa o padrão **`Result`**: `Result(false, ...)` quando a pergunta é
  vazia → `@Action` e `@Outcome` **não executam** (a demo de early termination).
- A `@Action` usa **placeholder `{}`** com parâmetro posicional.
- **`AnswerStore`** é `@ApplicationScoped` com `ConcurrentHashMap` — a ponte entre
  o agente e a resposta HTTP síncrona.

### Configuração (Ollama local — demo sem custo)

```properties
payara.agentic.llm.provider=ollama
payara.agentic.llm.model=gemma3:4b
payara.agentic.llm.ollama.base-url=http://localhost:11434
```

### Roteiro de execução manual

1. `winget install Ollama.Ollama` e `ollama pull gemma3:4b`;
2. Garantir que a distribuição tem o `agentic-ai-core` atual (package + copiar o
   JAR para `glassfish/modules/` + restart limpando cache OSGi);
3. `mvn package` do sample e `asadmin deploy .../agentic-ai-quickstart.war`;
4. POST em `/agentic-ai-quickstart/api/ask` e acompanhar
   `[TRIGGER] → [DECISION] → [ACTION] → [OUTCOME]` no `server.log`;
5. Repetir com `question` vazia → `[DECISION] proceed=false` e a resposta
   "(no answer — workflow terminated...)".

### Teste de integração

`AgenticQuickstartIT` (Arquillian) **não precisa de LLM vivo**: o deployment inclui
`StubLargeLanguageModel` e, pela regra do **LLM auto-vetado** (capítulo 5), o LLM
da aplicação vence o default do runtime. O teste assevera a resposta roteirizada e
a terminação antecipada com pergunta em branco.

---

## Sample 2 — `agentic-ai` (Tutorial Generator)

**Caso de uso real:** um agente escreve um **guia campo-a-campo** de um formulário
web (cadastro de cliente para contratar o Azul Payara Server) e permite **refinar o
guia por chat**. A página mostra o formulário à esquerda, o guia gerado à direita e
um chat de refinamento embaixo.

```
GET  /agentic-ai/                        UI lado a lado
GET  /agentic-ai/api/form                metadados do formulário (FormSpec)
POST /agentic-ai/api/tutorial/generate   gera um guia novo
POST /agentic-ai/api/tutorial/refine     { "instruction": "..." } refina o guia todo
POST /agentic-ai/api/tutorial/refine-field  refina UM campo e mescla de volta
```

### As ideias fortes do design

1. **Fonte única de verdade:** `CustomerFormSpec` define o formulário; a página
   renderiza o form vivo a partir dele **e** o agente explica os mesmos campos —
   impossível divergirem.
2. **O evento carrega o modo:** `TutorialRequest(formSpec, instruction, currentHtml)`.
   `currentHtml` nulo/vazio → **gerar** do zero; preenchido → **refinar** aplicando
   `instruction`. Um único agente, dois comportamentos, decididos na `@Action`.
3. **Refinamento passa o artefato real:** a cada turno de chat, o guia **atual** +
   a instrução vão no prompt — o modelo edita o artefato de verdade em vez de
   confiar só na memória conversacional. (Padrão importante de engenharia de
   agentes para citar em palestra.)
4. **Refinamento por campo com merge:** `refine-field` extrai só a descrição do
   campo (JSON-P), roda o workflow sobre esse fragmento e **mescla** o resultado de
   volta no guia completo — preservando os outros campos e economizando tokens.
5. **Robustez com LLM:** `stripCodeFences` remove os ```` ``` ```` que os modelos
   às vezes teimam em adicionar — lembrete honesto de que saída de LLM requer
   pós-processamento defensivo.

### O agente

```java
@Trigger  void onRequest(TutorialRequest request)      // loga generate|refine
@Decision Result hasFields(TutorialRequest request)    // tem campos? senão, para
@Action   void render(TutorialRequest request) {
    if (gerar)   content = model.query("Generate the field-guide JSON ... : {}", request.formSpec());
    else         content = model.query("Current field-guide JSON:\n{}\n\nApply this change...: {}\n\n...",
                                       request.currentHtml(), request.instruction());
    store.put(stripCodeFences(content));
}
@Outcome  void complete(TutorialRequest request)       // loga o tamanho do guia
```

Repare: o prompt de refinamento usa **dois placeholders `{}`** — guia atual e
instrução, substituídos posicionalmente.

### Configuração (Anthropic/Claude — qualidade de HTML)

```properties
payara.agentic.llm.provider=anthropic
payara.agentic.llm.model=claude-opus-4-8
payara.agentic.llm.max-tokens=8192
payara.agentic.llm.system=You are a senior technical writer...
```

O prompt de sistema vem da **configuração** (não do código) e vira o **prefixo de
prompt caching** (capítulo 7). Para rodar 100% local: trocar para
`provider=ollama` / `model=gemma3:12b` (12B recomendado para HTML de qualidade).

⚠️ **Pegadinha operacional:** a `ANTHROPIC_API_KEY` precisa estar no ambiente
**antes** do `asadmin restart-domain`, para o processo do servidor herdá-la.

### Teste de integração

`AgenticTutorialIT` — mesmo padrão do quickstart: `StubLargeLanguageModel` no
deployment, sem LLM vivo. Assevera que o form é exposto, o guia é gerado e um
refinamento via chat produz resultado diferente.

---

## Quiz — Capítulo 8

**1.** No quickstart, por que o record `Question` **não** tem constraints de Bean
Validation, sendo que o `@Trigger` até usa `@Valid`?

<details><summary>Ver resposta</summary>

É intencional e didático: sem constraints, uma pergunta **em branco passa pelo
trigger** e chega à `@Decision`, que retorna `Result(false, ...)` — demonstrando a
**terminação antecipada** do workflow (a `@Action` nunca roda e a API devolve
"(no answer ...)"). Com um `@NotBlank`, a violação viraria
`ConstraintViolationException` antes da decisão e a demo mostraria outro recurso.
</details>

**2.** Trace o caminho completo de um `POST /api/ask` com pergunta válida até a
resposta JSON, citando as classes envolvidas.

<details><summary>Ver resposta</summary>

`AskResource.ask` cria `Question` e chama `trigger.fire(question)` → o **observer
sintético** (registrado pela `AgenticAIExtension`) recebe o evento → o
`WorkflowEngine` ativa o contexto, roda `QuestionAgent.onQuestion` (`@Trigger`),
`hasContent` (`@Decision`, `Result(true, question)`), `generate` (`@Action`, chama
`model.query(...)` no backend Ollama e grava em `AnswerStore`), `complete`
(`@Outcome`) e destrói o contexto → o `fire` retorna → `AskResource` lê
`answers.get(text)` e devolve `AskResponse(question, answer)`.
</details>

**3.** Como o mesmo `TutorialAgent` decide entre gerar um guia novo e refinar o
existente, sem dois agentes ou duas fases distintas?

<details><summary>Ver resposta</summary>

O **evento carrega o modo**: `TutorialRequest.currentHtml()` nulo/em branco
significa "gerar do zero"; preenchido significa "refinar aplicando
`instruction()`". A `@Action render` inspeciona isso e monta o prompt adequado —
o de refinamento envia o guia atual e a instrução com dois placeholders `{}`.
</details>

**4.** Qual o objetivo do endpoint `refine-field` e como ele evita que o refinamento
de um campo estrague os demais?

<details><summary>Ver resposta</summary>

Ele refina **um único campo**: extrai do guia completo (JSON-P) apenas a descrição
do campo pedido, dispara o workflow sobre esse fragmento (menos tokens, mais foco)
e depois **mescla** o valor atualizado de volta no JSON completo
(`mergeField`), preservando intactas as descrições dos outros campos.
</details>

**5.** Os dois ITs (quickstart e tutorial) rodam sem nenhum LLM de verdade. Qual
mecanismo da implementação torna isso possível sem tocar no código dos agentes?

<details><summary>Ver resposta</summary>

O **LLM default auto-vetado** da `AgenticAIExtension`: os deployments de teste
incluem `StubLargeLanguageModel` (um bean da aplicação que implementa
`LargeLanguageModel`); o `watchForLlm` detecta isso e o runtime **não registra** o
LLM default dele. A injeção `@Inject LargeLanguageModel` nos agentes resolve para o
stub — mesmo código, respostas roteirizadas, zero rede.
</details>

**6.** Cite duas medidas defensivas do tutorial generator contra comportamentos
imprevisíveis do LLM.

<details><summary>Ver resposta</summary>

(a) `stripCodeFences` — remove cercas de código (```` ``` ````) que modelos
adicionam mesmo instruídos a não fazê-lo; (b) o **refinamento passa o artefato
atual explicitamente** no prompt em vez de confiar na memória conversacional — o
modelo edita o estado real. (Bônus: o merge por campo em `refine-field` limita o
raio de dano de uma resposta ruim a um único campo.)
</details>

---

➡️ Próximo: [Capítulo 9 — Roteiro da apresentação](09-roteiro-apresentacao.md)
