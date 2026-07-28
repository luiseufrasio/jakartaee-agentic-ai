# Capítulo 3 — `LargeLanguageModel` e tratamento de erros

## A fachada

`LargeLanguageModel` é a **única interface funcional da API** — uma fachada
minimalista, injetável via CDI, para conversar com o modelo:

```java
public interface LargeLanguageModel {
    String query(String prompt);
    <T> T query(String prompt, Class<T> resultType);
    String query(String prompt, Object... parameters);
    <T> T query(String prompt, Class<T> resultType, Object... parameters);
    <T> T unwrap(Class<T> implClass);
}
```

Quatro variações de `query` cobrindo os dois eixos: **com/sem parâmetros
posicionais** × **resposta String/tipada**. E o `unwrap`, no padrão do
`EntityManager.unwrap()` do Jakarta Persistence, para acessar APIs específicas do
vendor sem quebrar a portabilidade do resto do código.

## As regras dos placeholders `{}`

O prompt aceita o token exato `{}` como marcador **posicional** (inspirado no
SLF4J). As regras exatas — cobradas no TCK:

1. Os parâmetros são substituídos **em ordem de declaração**, cada um serializado
   com **Jakarta JSON Binding**.
2. Se o prompt tem N placeholders, **devem** ser fornecidos exatamente N parâmetros.
   Divergência ⇒ `IllegalArgumentException`.
3. **Exceção à regra:** prompt **sem** placeholder pode receber **no máximo um**
   parâmetro — ele é enviado ao modelo como **contexto estruturado** (a Payara o
   anexa como JSON numa nova linha após o prompt).
4. Apenas o token exato `{}` é placeholder; qualquer outro uso de chaves
   (`{nome}`, `{ }`) é texto literal do prompt.

```java
llm.query("Classify this event: {}", event);   // 1 placeholder, 1 parâmetro ✔
llm.query("Classify this event", event);       // 0 placeholders, 1 contexto ✔
llm.query("Compare {} with {}", a);            // ✘ IllegalArgumentException
```

## Respostas tipadas

```java
Sentiment s = llm.query("Return JSON {\"score\": ..., \"label\": ...} for: {}",
                        Sentiment.class, review);
```

A resposta do LLM (que se espera ser JSON) é **desserializada com JSON-B** para o
tipo pedido. Se a desserialização falha (o modelo devolveu texto solto, JSON
truncado...), o erro vira **`LLMException`** — não `IllegalArgumentException`,
porque a culpa é da resposta do serviço, não dos argumentos do chamador.

## Estado conversacional — a regra mais importante

> Implementações **devem manter estado conversacional para o contexto de workflow
> corrente** através das chamadas de `query`.

Ou seja: dentro de um mesmo workflow, a segunda chamada `query(...)` "lembra" da
primeira — o histórico é acumulado e reenviado ao modelo. E os limites:

- **Agente `@WorkflowScoped`:** a conversa está presa ao contexto do workflow e
  **termina com ele**.
- **Agente `@ApplicationScoped`:** o bean é um só, mas a conversa deve permanecer
  **isolada por contexto de workflow** — execuções concorrentes não podem vazar
  histórico entre si.
- Implementações devem ser **thread-safe dentro de um workflow**.

### Cenário 1 — memória entre fases do mesmo workflow

O segundo `query` não precisa reenviar o que já foi dito: o histórico acumulado
vai junto.

```java
@Agent
public class TriageAgent {

    @Inject
    private LargeLanguageModel llm;

    @Decision
    public boolean isRelevant(Ticket ticket) {
        // 1º turno: o ticket entra na conversa aqui
        String category = llm.query("Classifique este chamado: {}", ticket);
        return !"SPAM".equals(category);
    }

    @Action
    public String draftReply(Ticket ticket) {
        // 2º turno: o modelo "lembra" do chamado classificado acima —
        // note que o prompt nem repete o conteúdo do ticket.
        return llm.query(
            "Escreva uma resposta inicial para o chamado que você classificou.");
    }
}
```

### Cenário 2 — `@WorkflowScoped`: a conversa morre com o workflow

Cada disparo de evento cria um novo contexto de workflow — e uma conversa
zerada. Não existe memória *entre* workflows:

```java
tickets.fire(new Ticket("A"));  // workflow 1: conversa própria, descartada no fim
tickets.fire(new Ticket("B"));  // workflow 2: começa do zero — não "lembra" do ticket A
```

Se o segundo prompt fosse `"Compare com o chamado anterior"`, o modelo não teria
como responder: o histórico do workflow 1 já não existe.

### Cenário 3 — `@ApplicationScoped`: singleton, mas conversas isoladas

O bean é um só para a aplicação inteira; o estado conversacional, não — ele é
**por contexto de workflow**, mesmo sob concorrência:

```java
@Agent
@ApplicationScoped
public class SupportAgent {

    @Inject
    private LargeLanguageModel llm;   // injetado uma vez no singleton...

    @Decision
    public boolean needsHuman(CustomerMessage msg) {
        String mood = llm.query("Qual o humor deste cliente? {}", msg);
        return "ANGRY".equals(mood);
    }

    @Action
    public String reply(CustomerMessage msg) {
        // ...mas cada workflow enxerga SÓ o próprio histórico: se os clientes
        // X e Y estão sendo atendidos em paralelo, o humor detectado para X
        // jamais aparece no prompt do workflow de Y.
        return llm.query("Responda no tom adequado ao humor que você detectou.");
    }
}
```

É exatamente o cenário da questão 3 do quiz — e o que o TCK cobra quando exige
isolamento por workflow até para agentes `@ApplicationScoped`.

Na implementação da Payara isso sai "de graça" da arquitetura: o
`LargeLanguageModelImpl` guarda a conversa numa lista de turnos (`user`/`assistant`)
e é registrado como bean **`@Dependent`** — cada ponto de injeção/resolução dentro
do workflow recebe sua instância, e o engine resolve um LLM por execução de
workflow (detalhe no capítulo 6). Um detalhe elegante: se a chamada ao backend
falha, o turno do usuário é **removido** da conversa (rollback), para o histórico
não ficar com uma pergunta sem resposta.

## A hierarquia de erros

Dois tipos de falha, com culpados diferentes:

| Exceção | Quando | Culpado |
| --- | --- | --- |
| `IllegalArgumentException` | prompt nulo, `resultType` nulo, contagem de placeholders errada, parâmetro não-serializável para JSON | o **chamador** |
| `LLMException` (unchecked, estende `RuntimeException`) | falha de comunicação, rate limit, timeout, modelo indisponível, resposta malformada, falha de **des**serialização da resposta | o **serviço LLM** |

`LLMException` é unchecked de propósito: não força try-catch em todo query, e pode
ser capturada de forma centralizada por um método `@HandleException` do agente —
esse é o padrão idiomático para resiliência:

```java
@HandleException
void llmDown(LLMException ex, Question q) {
    answers.put(q.text(), "Serviço indisponível, tente mais tarde.");
    // retorna normalmente ⇒ workflow segue para o @Outcome
}
```

## O que a 1.0 NÃO padroniza (e por quê)

- **Seleção de provedor e configuração** (temperature, max tokens...) são
  implementation-specific na 1.0. A Payara usa MicroProfile Config com prefixo
  `payara.agentic.llm.*` (capítulo 7).
- O plano declarado no Javadoc: versões futuras padronizarão seleção de provedor e
  um conjunto comum de propriedades — **o mesmo modelo do Jakarta Persistence**
  (providers plugáveis + propriedades comuns + `unwrap` para o resto).
- Streaming, tools/function-calling, embeddings: fora do escopo da 1.0.

---

## Quiz — Capítulo 3

**1.** `llm.query("Resuma o pedido", pedido, cliente)` — o prompt não tem `{}` e
foram passados dois parâmetros. O que acontece?

<details><summary>Ver resposta</summary>

**`IllegalArgumentException`**. Prompt sem placeholder aceita **no máximo um**
parâmetro (enviado como contexto estruturado). Dois ou mais parâmetros sem
placeholders é erro do chamador.
</details>

**2.** O LLM responde `"Claro! Aqui está o JSON: {...}"` a um
`query(prompt, Invoice.class)` e a desserialização JSON-B falha. Qual exceção é
lançada e por que essa (e não a outra)?

<details><summary>Ver resposta</summary>

**`LLMException`**. A falha está na **resposta do serviço** (o modelo não devolveu
JSON puro), não nos argumentos do chamador. `IllegalArgumentException` fica
reservada para erros de entrada (prompt nulo, contagem de placeholders,
parâmetro não-serializável).
</details>

**3.** Um agente `@ApplicationScoped` atende dois eventos simultaneamente, e cada
workflow faz duas chamadas `query`. O que a spec garante sobre o histórico
conversacional?

<details><summary>Ver resposta</summary>

Cada workflow tem sua **conversa isolada**: a segunda chamada de cada workflow
enxerga apenas o histórico daquele workflow. Mesmo o agente sendo um singleton de
aplicação, o estado conversacional é **por contexto de workflow** e não pode vazar
entre execuções concorrentes. A conversa termina quando o contexto do workflow
termina.
</details>

**4.** No prompt `"Gere o guia para o formulário {nome} usando {}"`, quantos
placeholders a spec reconhece?

<details><summary>Ver resposta</summary>

**Um** — apenas o token exato `{}`. O `{nome}` é texto literal do prompt (não há
placeholders nomeados). Logo, exatamente um parâmetro deve ser fornecido.
</details>

**5.** Para que serve `unwrap(Class<T>)` e qual API existente da plataforma inspirou
esse método?

<details><summary>Ver resposta</summary>

Permite acessar a **implementação subjacente** do LLM para usar recursos
vendor-specific não expostos pela fachada (na Payara, por exemplo, o backend
concreto). Inspirado no `EntityManager.unwrap()` do **Jakarta Persistence**. Se o
tipo pedido não for compatível, lança `IllegalArgumentException`.
</details>

---

➡️ Próximo: [Capítulo 4 — O TCK](04-tck.md)
