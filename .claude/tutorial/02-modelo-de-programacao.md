# Capítulo 2 — O modelo de programação (as anotações)

Este capítulo cobre cada tipo do pacote `jakarta.ai.agent` com as regras exatas da
spec — incluindo as sutilezas que costumam cair em Q&A.

## `@Agent` — declarando o agente

```java
@Agent(name = "QuestionAgent", description = "Answers a question using the configured LLM.")
public class QuestionAgent { /* ... */ }
```

- Anotação de **classe** (`@Target(TYPE)`), retenção em runtime.
- `name` default: nome simples da classe com a primeira letra minúscula
  (`MyAgent` → `myAgent`).
- `description`: para documentação e descoberta.
- **Escopos suportados: apenas dois** — `@WorkflowScoped` e `@ApplicationScoped`.
  Se nenhum for declarado, **o default é `@WorkflowScoped`** (na Payara, a extensão
  CDI adiciona a anotação em `ProcessAnnotatedType`).

### Os dois escopos lado a lado

| Aspecto | `@WorkflowScoped` (default) | `@ApplicationScoped` |
| --- | --- | --- |
| Instância do agente | **Uma nova por execução** de workflow; nasce no trigger, morre após o outcome (ou falha) | **Uma única** compartilhada por todas as execuções, pelo tempo de vida da aplicação |
| Campos de instância | Privados daquela execução — pode acumular estado do workflow à vontade | Compartilhados entre workflows concorrentes — precisam ser **thread-safe** e não podem guardar estado de uma execução específica |
| Observers CDI genéricos (`@Observes` sem `@Trigger`) | **Proibidos** (erro de deployment) | Permitidos |
| Uso típico | Agente com estado por execução (o caso comum) | Agente sem estado próprio, com recursos caros de inicializar, ou que também precisa ser um observer CDI convencional |

E o detalhe fino que iguala os dois: mesmo quando o agente é `@ApplicationScoped`,
**um contexto de workflow é criado para cada execução**. O estado conversacional do
`LargeLanguageModel` injetado segue o ciclo de vida **desse contexto**, não o do
bean — duas execuções concorrentes de um agente singleton continuam com conversas
isoladas. Ou seja: o escopo do agente decide onde vivem **os campos do agente**; a
conversa com o LLM é sempre por workflow.

Por que a restrição de `@Observes` genérico só vale para `@WorkflowScoped`? Um
observer comum é invocado pelo container **fora** de um workflow — e sem workflow
ativo não existe contexto para criar/resolver a instância `@WorkflowScoped` do
agente. No `@ApplicationScoped` a instância existe independente de workflow, então
o observer comum funciona normalmente.

### Exemplo 1 — `@WorkflowScoped`: análise de fraude com estado acumulado

O caso clássico do escopo default: o agente **acumula estado nos campos de
instância ao longo das fases** — cada execução ganha sua instância nova, então os
campos são um bloco de rascunho privado do workflow, sem risco de concorrência.

```java
@Agent(description = "Analisa transações suspeitas e monta um dossiê de fraude.")
public class FraudAnalysisAgent {          // sem escopo declarado ⇒ @WorkflowScoped

    @Inject
    LargeLanguageModel llm;

    // Estado PRIVADO desta execução — nasce no trigger, morre após o outcome.
    private final List<String> findings = new ArrayList<>();  // sem sincronização!
    private int riskScore;

    @Trigger
    void onTransaction(BankTransaction tx) {
        riskScore = tx.amount() > 10_000 ? 20 : 0;            // primeiro indício
    }

    @Decision
    boolean isSuspicious(BankTransaction tx) {
        String verdict = llm.query("Is this transaction suspicious? {}", tx);
        if (verdict.contains("yes")) {
            findings.add(verdict);                             // acumula no campo
            riskScore += 50;
        }
        return riskScore > 40;                                 // senão, encerra
    }

    @Action
    void investigate(BankTransaction tx) {
        findings.add(llm.query("List the fraud indicators in: {}", tx));
        riskScore += findings.size() * 5;                      // refina o score
    }

    @Outcome
    void fileReport(BankTransaction tx, CaseService cases) {
        cases.open(tx, riskScore, findings);   // consolida TODO o estado acumulado
    }
}
```

Por que `@WorkflowScoped` é a escolha certa aqui: `findings` e `riskScore` crescem
fase a fase e só fazem sentido **para esta transação**. Se esse agente fosse
`@ApplicationScoped`, duas transações simultâneas misturariam os dossiês uma da
outra. E note que não há `synchronized` nem coleções concorrentes — não precisa:
ninguém mais enxerga esta instância.

### Exemplo 2 — `@ApplicationScoped`: triagem com recurso caro compartilhado

O escopo de aplicação compensa quando o agente carrega um **recurso caro que deve
ser inicializado uma vez** e/ou precisa ser também um **observer CDI comum** —
as duas capacidades que o `@WorkflowScoped` não dá:

```java
@Agent(description = "Classifica tickets de suporte contra a base de conhecimento.")
@ApplicationScoped                          // UMA instância para toda a aplicação
public class TicketTriageAgent {

    @Inject
    LargeLanguageModel llm;

    // Recurso caro: carregado UMA vez, reutilizado por todos os workflows.
    private volatile KnowledgeBase kb;
    // Estado compartilhado exige tipos thread-safe:
    private final AtomicLong triaged = new AtomicLong();

    @PostConstruct
    void init() {
        kb = KnowledgeBase.loadFromDisk();   // minutos de carga — só no startup
    }

    // Observer CDI COMUM (sem @Trigger): permitido porque é @ApplicationScoped.
    // Roda FORA de qualquer workflow — ex.: recarga da base publicada pelo admin.
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

Por que `@ApplicationScoped` é a escolha certa aqui: a `KnowledgeBase` custa caro
para carregar e é **somente leitura durante os workflows** — recarregá-la a cada
ticket (o que o `@WorkflowScoped` faria, via `@PostConstruct` por execução) seria
proibitivo. O observer `onKnowledgeBaseUpdated` é o bônus exclusivo do escopo: um
evento administrativo que **não inicia workflow nenhum**, apenas atualiza o recurso.
O preço a pagar está visível no código: `volatile`, `AtomicLong` — todo campo é
compartilhado e a disciplina de concorrência é sua. E lembre: mesmo aqui, cada
ticket ganha **seu próprio contexto de workflow** — a conversa do `llm` na
`classify` de um ticket nunca contamina a de outro.

**Regra de bolso para a palestra:** estado *do caso em andamento* nos campos →
`@WorkflowScoped` (o default existe por isso); recurso *caro e compartilhado* +
necessidade de observers comuns → `@ApplicationScoped`, com thread-safety por sua
conta.

### Por que só dois escopos?

A spec não suporta `@RequestScoped`, `@SessionScoped`, `@ConversationScoped` nem
`@Dependent` para agentes, e a justificativa é defensável em palestra:

1. **O ciclo de vida natural do agente é o workflow, não a requisição.** Um trigger
   pode ser disparado de qualquer lugar — um timer, um batch, uma mensagem, outro
   agente — onde request/session HTTP **nem existem**. Amarrar o agente a escopos
   web tornaria seu comportamento dependente de quem disparou o evento.
2. **`@Dependent` não faz sentido para quem não é injetado.** O escopo dependente
   segue o ciclo de vida de quem injeta o bean — mas agentes não são injetados por
   ninguém: são **dirigidos por eventos** pelo engine. Não há "dono" para o
   dependente seguir.
3. **Os dois escopos cobrem as duas únicas respostas à pergunta que importa:**
   o estado dos campos do agente é *por execução* (`@WorkflowScoped`) ou
   *compartilhado* (`@ApplicationScoped`)? Qualquer outro escopo seria uma resposta
   confusa a essa pergunta.
4. **Simplicidade da 1.0.** Menos combinações = spec mais enxuta, TCK menor e
   implementações mais fáceis de verificar. Se surgirem casos de uso reais para
   outros escopos, é mais fácil adicionar depois do que remover.

## `@Trigger` — o ponto de entrada

```java
@Trigger
void onQuestion(@Valid Question question) {
    logger.info("workflow iniciado para: " + question.text());
}
```

Regras:

- **Exatamente um** `@Trigger` por agente (na 1.0).
- Invocado quando um **evento CDI** compatível com o parâmetro é disparado. O
  `@Observes` no parâmetro é **opcional** — o container entende a intenção só pelo
  `@Trigger`.
- O evento disparador é automaticamente adicionado ao **contexto do workflow**, então
  fases seguintes podem recebê-lo como parâmetro.
- **Restrição de escopo importante:** agentes `@WorkflowScoped` só podem observar
  eventos via `@Trigger`. Um método com `@Observes` "solto" (sem `@Trigger`) num
  agente `@WorkflowScoped` é **erro de deployment**. Agentes `@ApplicationScoped`
  podem ter ambos (triggers e observers CDI comuns).
- Parâmetros aceitos: o evento, `LargeLanguageModel`, e qualquer dependência CDI
  injetável. Podem carregar constraints de Bean Validation (`@Valid`, `@NotNull`…) —
  a validação ocorre **antes** da invocação e uma violação vira
  `ConstraintViolationException`, tratável por `@HandleException`.
- Retorno: `void` (só efeitos colaterais) **ou** um objeto de domínio, que entra no
  contexto do workflow e fica injetável nas fases seguintes.

### Trigger que retorna objeto de domínio — enriquecendo o contexto

O exemplo acima é o padrão `void`. O segundo padrão de retorno faz o trigger
**produzir dados** para o resto do workflow — tipicamente uma pré-análise do
evento, muitas vezes já usando o LLM:

```java
@Agent
public class ClaimAgent {

    @Trigger
    ClaimAnalysis analyzeClaim(InsuranceClaim claim, LargeLanguageModel llm) {
        // Pré-análise no ponto de entrada: classifica o sinistro já no trigger.
        // O retorno (não-void) entra no contexto do workflow.
        return llm.query(
            "Classify this insurance claim (severity, category) as JSON: {}",
            ClaimAnalysis.class, claim);
    }

    @Decision
    boolean needsAdjuster(ClaimAnalysis analysis) {      // ← retorno do trigger
        return analysis.severity() > 3;
    }

    @Action
    void assign(ClaimAnalysis analysis, InsuranceClaim claim, AdjusterPool pool) {
        pool.assign(claim, analysis.category());  // evento E análise, por tipo
    }
}
```

O que o engine faz nos bastidores (é o `WorkflowContext` do capítulo 6): após o
trigger, o contexto contém **dois** objetos —

```
WorkflowContext
├── InsuranceClaim   ← o evento CDI (adicionado automaticamente, sempre)
└── ClaimAnalysis    ← o RETORNO do trigger (adicionado porque não é void/null)
```

— e cada fase seguinte declara nos parâmetros **o que quiser desses dois**, por
tipo: `needsAdjuster` pede só a análise; `assign` pede as duas coisas. Nenhum
parâmetro é passado manualmente — a resolução é do container. É o mesmo mecanismo
que depois recebe os retornos de `@Decision` (o `details()` do `Result`) e de
`@Action`, cada um empilhando no contexto para as fases posteriores.

Quando usar cada padrão: `void` quando o trigger só inicializa/loga (o evento em si
já basta para as próximas fases); retorno de domínio quando há **transformação ou
análise do evento** que as fases seguintes vão consumir — evita repetir a análise
em cada fase e mantém o trigger como o único lugar que "traduz" o evento bruto.

## `@Decision` — pontos de decisão

```java
@Decision
Result hasContent(Question question) {
    boolean proceed = question.text() != null && !question.text().isBlank();
    return new Result(proceed, question);
}
```

- 0..N por agente; podem ser **intercaladas com actions**.
- Tipicamente consultam o LLM para decidir o rumo do workflow.
- **Três padrões de retorno** (decore isto):

| Retorno | Prossegue se... | Dado propagado |
| --- | --- | --- |
| `boolean` | `true` | nada |
| `Result` | `result.success() == true` | o `details()` entra no contexto |
| Objeto de domínio | não-nulo | o próprio objeto entra no contexto |

- Retornar `false`, `Result(false, ...)` ou `null` **encerra o workflow** sem
  executar as fases restantes nem o `@Outcome`.

### Várias decisions no mesmo workflow — como elas conversam

Decisions múltiplas formam uma **cadeia de portões em série** ("E" lógico): cada
uma precisa aprovar para a próxima fase rodar, e elas se comunicam **pelo contexto
do workflow** — o dado que uma publica vira parâmetro da seguinte. Um pipeline de
crédito mostra os três padrões de retorno cooperando:

```java
@Agent
public class LoanAgent {

    // PORTÃO 1 — barato, sem LLM: corta cedo o que nem merece análise.
    // Result(true, policy) publica o PolicyCheck no contexto.
    @Decision
    Result withinPolicy(LoanApplication app, PolicyService policies) {
        PolicyCheck policy = policies.check(app);
        return new Result(policy.approved(), policy);
    }

    // PORTÃO 2 — caro, com LLM. CONSOME o PolicyCheck publicado pelo portão 1.
    // Retorno de objeto: não-nulo ⇒ segue (e publica); null ⇒ para.
    @Decision
    RiskAssessment assessRisk(LoanApplication app, PolicyCheck policy,
                              LargeLanguageModel llm) {
        RiskAssessment risk = llm.query(
            "Assess the risk of this application: {} given policy limits: {}",
            RiskAssessment.class, app, policy);
        return risk.score() < 700 ? null : risk;
    }

    // Ação entre decisions: produz a oferta a partir da análise de risco.
    @Action
    LoanOffer prepareOffer(RiskAssessment risk, LoanApplication app) {
        return new LoanOffer(app, risk.suggestedRate());
    }

    // PORTÃO 3 — DEPOIS de uma action: valida o que a action produziu.
    // Boolean: só decide, não publica nada.
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

O fluxo dos dados pelo contexto, portão a portão:

```
Trigger                    ctx: [LoanApplication]
withinPolicy  ✔ Result ──► ctx: [LoanApplication, PolicyCheck]
assessRisk    ✔ objeto ──► ctx: [LoanApplication, PolicyCheck, RiskAssessment]
prepareOffer  (action) ──► ctx: [..., LoanOffer]
offerViable   ✔ boolean ─► ctx inalterado (Boolean não publica dado)
send          (outcome)    consome LoanOffer
```

As regras da "conversa":

1. **A ordem importa** — aqui é a ordem de declaração; com `@Priority`/`order` a
   cadeia pode ser rearranjada sem mover código (respeitando o requisito de
   consistência).
2. **Comunicação é sempre via contexto, por tipo** — `assessRisk` recebe o
   `PolicyCheck` porque o portão 1 o publicou via `Result.details()`. Não existe
   chamada direta entre decisions nem variável compartilhada obrigatória (embora um
   agente `@WorkflowScoped` também possa usar campos, como no `FraudAnalysisAgent`).
3. **Cada portão corta o resto do workflow** — se `assessRisk` retorna `null`,
   `prepareOffer`, `offerViable` e `send` não rodam. Não há "else": na 1.0 o
   branching é **filtro em série**, não árvore if/else. Ramificações alternativas
   se modelam com o padrão portão + action condicionada ao dado publicado (ou com
   outro agente ouvindo outro evento).
4. **Decision depois de action é válido e útil** — `offerViable` valida o *produto*
   de `prepareOffer`. É o padrão "intermixed" que o TCK cobre com os fixtures
   `IntermixedAgent`/`BranchingAgent`.
5. **Escolha o retorno pelo que precisa comunicar**: `boolean` para portão puro,
   objeto quando o veredito *é* o dado, `Result` quando quer separar o veredito
   (`success`) do dado (`details`) — inclusive para publicar dado num veredito
   negativo tratado por outra via.

⚠️ Nuance para Q&A: a terminação antecipada **não desfaz efeitos colaterais** de
fases que já rodaram. Se `prepareOffer` tivesse persistido a oferta e `offerViable`
retornasse `false`, a linha do banco continuaria lá — o workflow para, não faz
rollback (a menos que você o integre a uma transação sua). Como fazer isso é o
próximo tópico.

#### Desfazendo efeitos com Jakarta Transactions

Primeiro, o disclaimer honesto: **a spec 1.0 não define semântica transacional
para workflows**. Mas dois fatos já vistos tornam a integração natural: o workflow
roda **sincronamente na thread de quem fez o `Event.fire`**, e observers CDI
síncronos executam, por default, **dentro do contexto transacional do chamador**.
Logo, uma `@Transactional` no chamador envolve o workflow inteiro:

```java
@Path("loans")
@RequestScoped
public class LoanResource {

    @Inject Event<LoanApplication> trigger;

    @POST
    @Transactional              // JTA: UMA transação envolve o workflow INTEIRO
    public Response apply(LoanApplication app) {
        trigger.fire(app);      // trigger→decisions→actions→outcome, nesta transação
        return Response.ok().build();
    }
}
```

Só que há uma pegadinha central: **terminação antecipada é conclusão normal** — a
decision retorna `false`, o `fire` retorna sem erro, e a transação **comita**,
persistência da `prepareOffer` incluída. Rollback em JTA exige **exceção**. Então o
portão que deve desfazer o que veio antes precisa **lançar** em vez de retornar
`false`:

```java
@Decision
boolean offerViable(LoanOffer offer) {
    if (offer.rate() >= MAX_LEGAL_RATE) {
        // NÃO "return false": isso encerraria o workflow e a transação COMITARIA.
        throw new OfferRejectedException(offer);   // ⇒ rollback de TUDO
    }
    return true;
}
```

O caminho da exceção fecha o ciclo com o que já estudamos: sem `@HandleException`
compatível (ou com um handler que **relança**), ela atravessa o engine, sai pelo
`fire()` e estoura no método `@Transactional` → a transação marca rollback → o
`INSERT` da `prepareOffer` é desfeito junto. Três consequências para não errar:

1. **`@HandleException` que recupera, comita.** Se um handler capturar a
   `OfferRejectedException` e retornar normalmente, a exceção nunca chega à
   transação — recuperação significa "o workflow deu certo", e o que foi persistido
   fica. Handler e transação precisam ser desenhados **juntos**: recuperar = manter
   efeitos; relançar = desfazer.
2. **`@Transactional` numa fase isolada tem outro efeito**: anotar só a
   `prepareOffer` cria uma transação que comita **quando a fase retorna** — um
   portão posterior falhar não a desfaz mais. Serve para atomicidade *dentro* da
   fase, não para proteger a cadeia.
3. **O redesenho muitas vezes vence a transação**: se a validação não depende do
   efeito colateral, mova o portão para **antes** da action
   (`offerViable` avaliando a taxa *antes* de persistir) ou deixe a persistência
   para o `@Outcome`, que só roda quando todos os portões aprovaram. Transação é a
   ferramenta para quando o efeito e a validação são inseparáveis (ex.: precisa
   gravar para obter um ID que a validação usa).

## `@Action` — o trabalho de verdade

```java
@Action
void generate(Question question) {
    String answer = model.query("Answer concisely: {}", question.text());
    answers.put(question.text(), answer);
}
```

- 0..N por agente; executam operações (persistir, chamar serviços, atualizar estado).
- Retorno: `void` ou objeto de domínio (que entra no contexto para as próximas fases).
- Recebem por parâmetro: resultados de decisões anteriores, o evento do trigger,
  `LargeLanguageModel`, beans CDI.

## Ordem de execução de `@Decision`/`@Action`

Precedência, aplicada nesta ordem:

1. **`@Priority` no método** — valor menor executa primeiro; **vence** o `order()`.
2. **Atributo `order()`** da própria anotação — usado quando não há `@Priority`.
3. **Ordem de declaração no código-fonte** — usada quando **nenhum** método declara
   ordenação explícita. Atenção: a reflexão do Java SE **não garante** ordem de
   declaração, mas as JVMs principais a preservam na prática; aplicações portáveis
   que exigem ordem estrita devem usar `@Priority`/`order`.

**Requisito de consistência:** se **qualquer** `@Decision`/`@Action` do agente
declara `order` explícito ou `@Priority`, **todos** os outros devem declarar também.
Misturar métodos ordenados e não-ordenados é **erro de deployment**.

Antes dos exemplos, dois lembretes: a ordenação vale **só para a cadeia
`@Decision`/`@Action`** (`@Trigger` sempre abre e `@Outcome` sempre fecha, fora da
disputa), e decisions e actions são ordenadas **juntas, na mesma fila** — não são
duas listas separadas.

### Caso 1 — Nenhuma ordenação: vale a ordem de declaração

```java
@Agent
public class ReportAgent {

    @Trigger  void onRequest(ReportRequest req) { }

    @Decision boolean hasData(ReportRequest req)   { /* ... */ }   // 1º
    @Action   Draft   buildDraft(ReportRequest req){ /* ... */ }   // 2º
    @Decision boolean draftOk(Draft draft)         { /* ... */ }   // 3º
    @Action   void    publish(Draft draft)         { /* ... */ }   // 4º

    @Outcome  void done(Draft draft) { }
}
```

Execução: `hasData → buildDraft → draftOk → publish` — exatamente a ordem em que
aparecem no fonte. Simples e legível... até alguém **reordenar os métodos numa
refatoração** e mudar silenciosamente o comportamento: nenhum compilador avisa que
a ordem dos métodos era semântica. É esse o risco (além da falta de garantia formal
da reflexão) que a ordenação explícita elimina.

### Caso 2 — `order()`: a posição sai do texto e vira contrato

O mesmo agente, com a ordem declarada — agora os métodos podem estar em **qualquer
posição no arquivo** (aqui, propositalmente embaralhados) sem afetar a execução:

```java
@Agent
public class ReportAgent {

    @Trigger  void onRequest(ReportRequest req) { }

    @Action(order = 40)   void    publish(Draft draft)          { /* ... */ }  // 4º
    @Decision(order = 10) boolean hasData(ReportRequest req)    { /* ... */ }  // 1º
    @Decision(order = 30) boolean draftOk(Draft draft)          { /* ... */ }  // 3º
    @Action(order = 20)   Draft   buildDraft(ReportRequest req) { /* ... */ }  // 2º

    @Outcome  void done(Draft draft) { }
}
```

Execução: `hasData(10) → buildDraft(20) → draftOk(30) → publish(40)`. Dicas
práticas: use **espaçamento de 10 em 10** (inserir um passo novo entre 20 e 30
vira `order = 25`, sem renumerar tudo) e **evite `order = 0`** — zero é o valor
default da anotação, então não conta como ordenação explícita; use valores
positivos.

### Caso 3 — `@Priority` vence `order()` no mesmo método

```java
@Agent
public class MixedAgent {

    @Trigger void on(StartEvent e) { }

    @Priority(1)
    @Action(order = 99)          // order é IGNORADO: @Priority presente no método
    void runsFirst() { /* ... */ }     // sort key = 1

    @Action(order = 2)
    void runsSecond() { /* ... */ }    // sem @Priority ⇒ vale o order = 2
}
```

Execução: `runsFirst (chave 1) → runsSecond (chave 2)` — apesar do `order = 99`.
A regra é **por método**: em cada um, `@Priority` (se presente) fornece a chave de
ordenação; senão, o `order()`. As chaves resultantes são então comparadas entre
todos os métodos da cadeia. Vale escolher **um** estilo por agente (`@Priority` OU
`order`) e misturar só em migrações.

### Caso 4 — Mistura inválida: erro de deployment

```java
@Agent
public class BrokenAgent {

    @Trigger void on(StartEvent e) { }

    @Decision(order = 10) boolean gate(StartEvent e) { /* ... */ }  // explícito
    @Action               void step1() { /* ... */ }                // ✘ implícito!
}
```

Deploy falha (na Payara: `DefinitionException: Inconsistent order at @Agent ...
all @Decision/@Action should declare @Priority or order or nothing.`). O
raciocínio da regra: se `step1` ficasse sem ordem, qual seria a posição dele em
relação ao `gate(10)`? Qualquer resposta (antes? depois? ordem de declaração só
para ele?) seria uma convenção obscura — a spec prefere obrigar a intenção
explícita a adivinhar.

## `@Outcome` — a fase terminal

- **0 ou 1** por agente (dois é erro de deployment); opcional.
- Executa após as demais fases completarem **com sucesso**.
- **Deve retornar `void`** na 1.0 (finalização e efeitos colaterais, não produção de
  dados).
- Após sua conclusão, **o container destrói o contexto do workflow**.

## `@HandleException` — recuperação de erros

```java
@HandleException
void handleLlmFailure(LLMException ex, Question question) {
    logger.warn("LLM indisponível, usando fallback", ex);
    // retorno normal ⇒ workflow CONTINUA
}
```

Semântica (a mais rica da spec — estude bem):

- 0..N por agente; capturam exceções de **qualquer fase** (trigger, decision,
  action, outcome).
- **Seleção do handler:** o de tipo de exceção **mais específico** compatível com a
  exceção lançada (segue a hierarquia Java). O parâmetro de exceção é obrigatório.
- **Controle do workflow:**
  - Handler **retorna normalmente** ⇒ recuperação bem-sucedida, workflow continua.
  - Handler **relança ou lança nova exceção** ⇒ workflow para; a exceção propaga
    para o container.
- Sem handler compatível ⇒ a exceção propaga para o container.
- **Sem tratamento recursivo:** exceção lançada por um handler não é redirecionada
  para outro handler — vai direto para o container.
- Retorno **deve ser `void`**.

### Os cenários, um a um

Um único agente de pagamentos ilustra todos os caminhos possíveis. A hierarquia de
exceções usada: `PaymentException` (base) ← `GatewayTimeoutException` (derivada).

```java
@Agent
public class PaymentAgent {

    @Trigger
    void onPayment(PaymentRequest req) { /* ... */ }

    @Decision
    boolean authorized(PaymentRequest req, LargeLanguageModel llm) { /* ... */ }

    @Action
    void charge(PaymentRequest req, GatewayClient gateway) {
        gateway.charge(req);   // pode lançar GatewayTimeoutException, LLMException...
    }

    @Outcome
    void confirm(PaymentRequest req, Receipts receipts) { /* ... */ }

    // ── CENÁRIO 1: recuperação — retorna normalmente, workflow "dá certo" ──
    // Nota: recebe a exceção E o estado do workflow (req vem do contexto).
    @HandleException
    void onTimeout(GatewayTimeoutException ex, PaymentRequest req,
                   RetryQueue retries) {
        retries.enqueue(req);              // recuperação: reprocessar depois
        // retorno normal ⇒ o engine considera o workflow RECUPERADO
        // e ainda executa o @Outcome confirm() como fechamento
    }

    // ── CENÁRIO 2: fatal — relança, workflow para ──
    @HandleException
    void onPaymentError(PaymentException ex, PaymentRequest req, AuditLog audit) {
        audit.paymentFailed(req, ex);      // registra ANTES de desistir
        throw ex;                          // propaga ao container; @Outcome NÃO roda
    }

    // ── CENÁRIO 3: recuperação condicional — decide em runtime ──
    @HandleException
    void onLlmFailure(LLMException ex, PaymentRequest req) {
        if (req.amount() < 100) {
            return;                        // valor baixo: aprova sem LLM, continua
        }
        throw new ManualReviewException(req, ex);   // valor alto: para e escala
    }

    // ── CENÁRIO 4: rede de segurança — o tipo mais genérico ──
    @HandleException
    void onAnyError(Exception ex, AlertService alerts) {
        alerts.notifyOps(ex);
        throw new IllegalStateException("Unexpected payment failure", ex);
    }
}
```

Agora, o que acontece em cada situação:

| A `charge` lança... | Handler escolhido | Por quê | Desfecho |
| --- | --- | --- | --- |
| `GatewayTimeoutException` | `onTimeout` | Match **mais específico** vence — `onPaymentError(PaymentException)` também casaria, mas é mais genérico | Retorna normal ⇒ workflow recuperado, `confirm()` (`@Outcome`) **executa** |
| `PaymentException` (outra que não timeout) | `onPaymentError` | Único match específico | Relança ⇒ workflow **para**, `confirm()` não roda, exceção chega ao container (e a uma `@Transactional` do chamador, se houver) |
| `LLMException` | `onLlmFailure` | Match exato | Depende do valor: retorna (continua + outcome) **ou** lança `ManualReviewException` — que **não** é re-tratada pelos outros handlers (sem recursão): vai direto ao container |
| `NullPointerException` | `onAnyError` | Só o `Exception` genérico casa | Alerta e relança embrulhada ⇒ workflow para |
| Um `Error` (ex.: `OutOfMemoryError`) | nenhum | `Error` não é `Exception` — nenhum parâmetro casa | Propaga direto ao container |

Quatro detalhes finos escondidos no exemplo:

1. **A seleção é pela hierarquia, não pela ordem de declaração** — `onAnyError`
   estar por último no arquivo não importa; ele só é escolhido quando nenhum tipo
   mais específico casa (a Payara pré-ordena os handlers do mais específico para o
   mais genérico no deploy).
2. **Handlers recebem o estado do workflow** — `onTimeout` declara
   `PaymentRequest` e `RetryQueue` além da exceção: a resolução de parâmetros é a
   mesma das outras fases (exceção em voo → contexto → CDI).
3. **Recuperar executa o `@Outcome`** — o cenário 1 termina com `confirm()`
   rodando (regra do engine, capítulo 6: outcome como fechamento da recuperação —
   exceto quando foi o próprio outcome que falhou).
4. **A `ManualReviewException` do cenário 3 não volta para a rede de segurança** —
   é a regra "sem tratamento recursivo": exceção lançada *por um handler* nunca é
   despachada para outro handler, mesmo havendo um `Exception` genérico no agente.
   Sem isso, um handler bugado poderia criar um loop infinito de tratamento.

E o vínculo com a seção anterior: se o chamador envolveu o `fire` numa
`@Transactional`, **cenário 1 comita** (recuperação = sucesso) e **cenários 2 e 4
desfazem** (exceção atravessa) — o design do handler decide o destino da transação.

## `@WorkflowScoped` — o escopo do workflow

- Um **normal scope CDI** (`@NormalScope`): um contexto por execução de workflow,
  abrangendo trigger → outcome. Beans nascem quando o workflow começa e morrem
  quando ele termina.
- Uso típico: compartilhar estado entre fases sem passar parâmetros (ex.: um cache
  de análise).
- Traz um `Literal` (`WorkflowScoped.Literal.INSTANCE`) para instanciação inline —
  é o que a extensão da Payara usa para aplicar o escopo default programaticamente.

## `Result` e a propagação de dados

```java
public record Result(boolean success, Object details) {}
```

O mecanismo geral de **propagação de dados por tipo**:

1. O evento do trigger entra no contexto.
2. Cada retorno não-nulo de fase entra no contexto (para `Result`, entra o
   `details()`; um `Boolean` de decisão não carrega dado).
3. Ao invocar uma fase, cada parâmetro é resolvido **por tipo**, preferindo o valor
   **mais recente** produzido (se duas fases produziram o mesmo tipo, vale o último).
4. O que não estiver no contexto é resolvido como bean CDI.

---

## Quiz — Capítulo 2

**1.** Um agente `@WorkflowScoped` declara, além do `@Trigger`, um método
`void onAudit(@Observes AuditEvent e)` sem `@Trigger`. O que acontece no deploy?

<details><summary>Ver resposta</summary>

**Erro de deployment** (`DefinitionException`). Agentes `@WorkflowScoped` só podem
observar eventos CDI através de métodos `@Trigger`. Observers CDI genéricos só são
permitidos em agentes `@ApplicationScoped`.
</details>

**2.** Uma `@Decision` retorna `new Result(true, new Plan("x"))`. O que exatamente
fica disponível para as próximas fases, e como uma `@Action` o recebe?

<details><summary>Ver resposta</summary>

O workflow prossegue (`success == true`) e o **`details()`** — o objeto
`Plan("x")` — é publicado no contexto do workflow. Uma `@Action` o recebe
simplesmente declarando um parâmetro do tipo `Plan`:
`@Action void execute(Plan plan) {...}`. A resolução é por tipo, valor mais recente
primeiro.
</details>

**3.** Num agente, o método A tem `@Action(order = 5)` e o método B tem apenas
`@Action`. Isso é válido?

<details><summary>Ver resposta</summary>

**Não** — viola o requisito de consistência: se qualquer `@Decision`/`@Action`
declara `order` explícito ou `@Priority`, todos os demais devem declarar também.
Misturar métodos explicitamente ordenados com não-ordenados é erro de deployment.
</details>

**4.** Um método tem `@Action(order = 10)` e também `@Priority(1)`. Qual valor
determina a posição de execução?

<details><summary>Ver resposta</summary>

O `@Priority(1)` — quando presente no método, `@Priority` **tem precedência** e o
`order()` é ignorado. Valores menores executam primeiro.
</details>

**5.** Um `@HandleException` captura uma `IOException`, faz log e retorna
normalmente. A exceção ocorreu numa `@Action` intermediária. O `@Outcome` executa?

<details><summary>Ver resposta</summary>

**Sim.** Retorno normal do handler significa recuperação: o workflow continua, e a
fase de `@Outcome` (se existir e ainda não tiver sido tentada) executa como
fechamento. Se o handler tivesse relançado a exceção, o workflow pararia e a exceção
propagaria para o container.
</details>

**6.** Por que confiar apenas na ordem de declaração dos métodos no código-fonte é
arriscado para ordenar fases, segundo a própria spec?

<details><summary>Ver resposta</summary>

Porque o Java SE **não garante** que a reflexão retorne métodos na ordem de
declaração do fonte — as principais JVMs preservam essa ordem na prática, mas isso
não é contrato. Aplicações portáveis que exigem ordem estrita devem declarar
`@Priority` ou `order` explicitamente.
</details>

**7.** No `LoanAgent`, como o `PolicyCheck` produzido pela primeira decision chega
à segunda (`assessRisk`)? E se `assessRisk` retornar `null` depois de
`withinPolicy` ter aprovado, o que roda e o que não roda?

<details><summary>Ver resposta</summary>

`withinPolicy` retorna `Result(true, policy)` — o `details()` (o `PolicyCheck`) é
**publicado no contexto do workflow**, e `assessRisk` o recebe declarando um
parâmetro do tipo `PolicyCheck` (resolução por tipo; decisions nunca se chamam
diretamente). Se `assessRisk` retorna `null`, o workflow **termina ali**:
`prepareOffer`, `offerViable` e o `@Outcome` não executam. E atenção: o que já
rodou **não é desfeito** — terminação antecipada não é rollback.
</details>

**8.** O `LoanResource` envolve o `fire` numa `@Transactional`. A `prepareOffer`
persistiu a oferta e, em seguida, `offerViable` retorna `false`. O `INSERT` é
desfeito? E se em vez de retornar `false` a decision lançasse uma exceção que um
`@HandleException` captura e trata retornando normalmente?

<details><summary>Ver resposta</summary>

Nos dois casos o `INSERT` **fica no banco**. Retornar `false` é terminação
antecipada **normal**: o `fire` retorna sem erro e a transação **comita**. E se a
exceção for capturada por um handler que retorna normalmente, ela nunca chega ao
método `@Transactional` — recuperação significa workflow bem-sucedido, logo commit.
O rollback só acontece quando a exceção **atravessa** o engine (sem handler, ou com
handler que relança) e estoura dentro da transação do chamador. Por isso handler e
transação precisam ser desenhados juntos: recuperar = manter efeitos; relançar =
desfazer.
</details>

**9.** Um agente `@ApplicationScoped` guarda num campo de instância o resultado
parcial do workflow corrente. Qual é o problema, e onde esse estado deveria viver?
E a conversa com o LLM, também vaza entre execuções?

<details><summary>Ver resposta</summary>

O bean é **um só para a aplicação inteira**: workflows concorrentes sobrescrevem o
campo um do outro (race condition e vazamento de estado entre execuções). Estado
por execução deve viver no **contexto do workflow** — retornos de fase propagados
por tipo, ou um bean auxiliar `@WorkflowScoped`, ou simplesmente usar o agente no
escopo default `@WorkflowScoped`. Já a conversa com o LLM **não vaza**: a spec
exige estado conversacional isolado **por contexto de workflow**, mesmo com agente
`@ApplicationScoped` — o escopo do agente governa os campos do bean, não o
histórico do LLM.
</details>

**10.** No `PaymentAgent`, a `charge` lança `GatewayTimeoutException`. O agente tem
handlers para `GatewayTimeoutException`, `PaymentException` (supertipo) e
`Exception`. Qual é invocado e por quê? E se esse handler, por sua vez, lançar uma
exceção nova — o handler de `Exception` a captura?

<details><summary>Ver resposta</summary>

O `onTimeout(GatewayTimeoutException)` — a seleção segue a hierarquia Java e
escolhe o **tipo mais específico** compatível, independentemente da ordem de
declaração no arquivo. Se ele lançar uma nova exceção, **nenhum outro handler é
consultado**: vale a regra "sem tratamento recursivo" — exceção lançada por um
handler vai direto ao container, mesmo existindo um `@HandleException(Exception)`
genérico. Isso evita loops infinitos de tratamento (handler tratando falha de
handler).
</details>

---

➡️ Próximo: [Capítulo 3 — LargeLanguageModel e erros](03-largelanguagemodel.md)
