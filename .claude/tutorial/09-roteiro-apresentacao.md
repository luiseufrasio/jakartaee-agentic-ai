# Capítulo 9 — Roteiro da apresentação (Payara, agosto/2026)

## Arco narrativo sugerido (45–60 min)

1. **O problema (5 min).** Todo mundo quer agentes de IA; em Java, cada framework
   tem seu modelo proprietário. Gancho: "como seria o *Jakarta Persistence* dos
   agentes?"
2. **A spec (15 min).** O diagrama de fases
   (`Trigger → Decision* → Action* → Outcome` + `HandleException`); um agente
   completo em um slide (o `QuestionAgent` cabe inteiro); os três padrões de retorno
   de `@Decision`; a fachada `LargeLanguageModel` com placeholders `{}`; o
   `@WorkflowScoped`.
3. **Demo 1 — quickstart (10 min).** POST com pergunta real → mostrar
   `[TRIGGER] → [DECISION] → [ACTION] → [OUTCOME]` no `server.log`; POST com
   pergunta vazia → early termination ao vivo. Rodando em **Ollama local** (sem
   rede, sem custo — demo à prova de wifi de conferência).
4. **Por dentro da implementação (10 min).** O pipeline da extensão CDI (remoção do
   `@Observes` + observer sintético é o slide "aha!"); o `WorkflowEngine`; o escopo
   por `ThreadLocal`; o LLM auto-vetado (e como isso viabiliza testes com stub).
5. **Demo 2 — tutorial generator (10 min).** Gerar o guia do formulário com Claude;
   refinar via chat ("make the business email explanation friendlier and add an
   example"); refinar um campo só. Mostrar que a troca Ollama↔Claude é **um
   arquivo de propriedades**.
6. **TCK e o caminho da spec (5 min).** Como se prova compatibilidade; o opt-in
   `jakarta.ai.agent.tck.implementation.present`, que separa as asserções de
   baseline em CDI puro das comportamentais; o que vem na 2.0 (múltiplos
   triggers, outros event sources, config padronizada de LLM).
7. **Q&A.**

## Checklist técnico pré-demo

- [ ] Ollama instalado, `ollama pull gemma3:4b` feito, serviço respondendo em
      `http://localhost:11434` (teste: `ollama run gemma3:4b "hi"`).
- [ ] `agentic-ai-core.jar` atual copiado para
      `glassfish/modules/` da distribuição + domínio reiniciado **limpando o cache
      OSGi** (pegadinha clássica: JAR novo com cache velho = classe velha).
- [ ] As credenciais do provedor de nuvem presentes **no mesmo shell/ambiente que
      inicia o domínio** (o processo do servidor herda o ambiente do pai), *antes*
      do `asadmin restart-domain`. Para a config Vertex padrão:
      `gcloud auth application-default login` mais `ANTHROPIC_VERTEX_PROJECT_ID` /
      `CLOUD_ML_REGION`. Se trocar para `provider=anthropic`:
      `$env:ANTHROPIC_API_KEY = "sk-ant-..."`.
- [ ] Os três WARs deployados e testados na véspera E na manhã da palestra
      (`quickstart.war`, `tutorial-generator.war`, `course.war`).
- [ ] `server.log` aberto num terminal com fonte grande (`Get-Content -Wait -Tail 0`).
- [ ] Plano B sem rede: quickstart com Ollama já cobre a demo principal; o tutorial
      generator pode cair para `provider=ollama` / `model=gemma3:12b` (baixe o
      modelo antes!). O Course Content Studio **não** cai bem: o timeout de 120 s
      por chamada do backend Ollama é menor que o passo do quiz num 12B de
      laptop.
- [ ] Requests prontos (não digitar JSON ao vivo): script/arquivo `.http` com o
      POST válido, o POST vazio e os refines.

## Perguntas prováveis da plateia (e as respostas)

**"Como isso se compara com LangChain4j / Spring AI?"**
Não compete — padroniza. LangChain4j é uma biblioteca (excelente) de um vendor;
Jakarta Agentic AI é uma **especificação** com TCK: você programa contra
`jakarta.ai.agent` e troca de implementação/provedor sem reescrever. Uma
implementação pode inclusive usar LangChain4j por baixo — e o `unwrap()` existe
exatamente para acessar o que a fachada não expõe.

**"E se o LLM alucinar/falhar no meio do workflow?"**
Duas camadas: `LLMException` (unchecked) para falhas do serviço, capturável por
`@HandleException` com semântica clara de recuperação (retornou normal = continua;
relançou = para); e respostas tipadas via JSON-B — se o modelo não devolver o JSON
esperado, é `LLMException`, não dado corrompido silencioso. O tutorial generator
ainda mostra defesa aplicada (strip de code fences, merge por campo).

**"Isso é assíncrono? Escala?"**
Na 1.0 o workflow roda sincronamente na thread do `Event.fire` — o que torna o
modelo de programação simples e o resultado disponível na mesma requisição. Nada
impede o chamador de disparar o evento a partir de um executor/virtual thread. O
isolamento é por contexto de workflow (ThreadLocal na Payara). Orquestração
assíncrona é candidata a versões futuras.

**"Por que eventos CDI como trigger, e não um método que eu chamo direto?"**
Desacoplamento (quem dispara não conhece o agente), é infra que todo servidor
Jakarta EE já tem, e permite fan-out natural (um evento, vários agentes). A spec já
prevê outras fontes no futuro (Messaging, REST, programático).

**"Múltiplos agentes podem colaborar?"**
Sim, via eventos: uma `@Action`/`@Outcome` de um agente pode injetar
`Event<X>` e disparar o trigger de outro. Orquestração multi-agente de primeira
classe é tema para versões futuras.

**"Quando sai? É oficial?"**
Posicione com cuidado: é uma proposta de especificação em desenvolvimento no
ecossistema Jakarta EE, com API, spec document, TCK e uma implementação funcional
na Payara — o material desta palestra. Roadmap (multiplos triggers, config LLM
padronizada) já documentado nos Javadocs da API.

**"Rodou local? Quanto custa a demo?"**
Quickstart: Ollama + gemma3:4b, zero custo, zero rede. Tutorial generator: Claude
para qualidade de HTML, com prompt caching para reduzir custo — mas roda em Ollama
também.

## Mensagens-chave (se a plateia só levar três coisas)

1. **Agentes como beans CDI** — o modelo de fases
   (`@Trigger/@Decision/@Action/@Outcome/@HandleException`) transforma "chamar um
   LLM" em um workflow gerenciado pelo container, com escopo, injeção, validação e
   tratamento de erro padronizados.
2. **Vendor-neutralidade real** — o código do agente não sabe qual LLM o serve;
   trocar Ollama↔Claude↔Vertex é configuração (MicroProfile Config na Payara).
3. **Spec de verdade** — com TCK executável (cada teste amarrado a um requisito por
   `@Assertion`) e uma implementação completa em produção de servidor (extensão CDI
   portável + engine), não um paper.

---

## Quiz final — integração de tudo

**1.** Do `trigger.fire(new Question("..."))` até o JSON de resposta: descreva o
caminho citando ao menos: extensão, observer, engine, contexto, resolução de
parâmetros e o papel do escopo.

<details><summary>Ver resposta</summary>

No deploy, a `AgenticAIExtension` removeu o `@Observes` do trigger, aplicou
`@WorkflowScoped` default, validou os metadados e registrou o **observer sintético**
para `Question` + o `WorkflowScopeContext`. No `fire`: o observer chama
`WorkflowEngine.execute` → `activate()` põe o contexto na thread → o bean do agente
e o LLM (`@Dependent`, um por workflow) são resolvidos → `@Trigger` roda (evento já
semeado no `WorkflowContext`) → `@Decision` retorna `Result(true, question)`
(details vai ao contexto) → `@Action` recebe `Question` por tipo via
`ParameterResolver`, consulta o LLM e grava no `AnswerStore` → `@Outcome` roda →
`finally` destrói os beans do escopo (`@PreDestroy`) e limpa o ThreadLocal → o
`fire` retorna e o recurso REST lê o `AnswerStore`.
</details>

**2.** Cite as três `DefinitionException` mais prováveis ao escrever um agente
descuidado, e explique por que a spec prefere falhar no deploy.

<details><summary>Ver resposta</summary>

Dois `@Trigger`; dois `@Outcome`; mistura de fases com/sem ordenação explícita
(ou: `@Observes` genérico em agente `@WorkflowScoped`; agente sem `@Trigger`).
Falhar no deploy (fail fast) transforma erro estrutural em feedback imediato e
determinístico, em vez de comportamento indefinido na primeira execução em
produção — mesma filosofia do CDI para beans mal-formados.
</details>

**3.** Você trocou o tutorial generator para `provider=anthropic`, a demo falha na
conferência: o guia vem vazio e o log mostra
`IllegalStateException: ... no API key found`. Qual foi o erro operacional e qual é
o fix?

<details><summary>Ver resposta</summary>

A `ANTHROPIC_API_KEY` não estava no ambiente **do processo do servidor** —
provavelmente exportada num shell diferente ou depois do start do domínio. Fix:
`$env:ANTHROPIC_API_KEY = "sk-ant-..."` e **então** `asadmin restart-domain` no
mesmo shell (o servidor herda o ambiente de quem o inicia). A mesma armadilha vale
para a config Vertex que vem no sample, onde o que falta é ADC /
`ANTHROPIC_VERTEX_PROJECT_ID`. Plano B: trocar para `provider=ollama` no
microprofile-config.
</details>

**4.** Um espectador afirma: "isso é só um wrapper de anotações em volta de uma
chamada HTTP para o LLM". Refute com três capacidades concretas do container que o
wrapper manual não teria.

<details><summary>Ver resposta</summary>

(1) **Ciclo de vida gerenciado**: contexto `@WorkflowScoped` com criação/destruição
automática de beans e `@PreDestroy`, isolado entre execuções concorrentes;
(2) **orquestração declarativa**: ordenação de fases (`@Priority`/`order`/
declaração), terminação antecipada padronizada, propagação de dados por tipo entre
fases e despacho de exceções para o handler mais específico com semântica
continua/para; (3) **integração com a plataforma**: Bean Validation nos parâmetros
das fases, interceptors CDI, e vendor-neutralidade com estado conversacional por
workflow garantido pela spec (mais o TCK para provar tudo isso).
</details>

**5.** Em uma frase cada, qual é o papel de: spec API, TCK, `agentic-ai-core`,
quickstart e tutorial generator na sua palestra?

<details><summary>Ver resposta</summary>

**API**: o contrato vendor-neutral (`jakarta.ai.agent`) que o desenvolvedor
programa. **TCK**: a prova executável de que uma implementação cumpre o contrato.
**agentic-ai-core**: a implementação da Payara — extensão CDI + engine + backends
LLM. **Quickstart**: o "hello world" que ensina as quatro fases em cinco classes.
**Tutorial generator**: o caso de uso real que mostra refinamento iterativo via
chat e as práticas defensivas de produção.
</details>

---

🏁 Fim do tutorial. Revise os quizzes errados e rode as demos! Boa palestra! 🎤
