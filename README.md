# ASA Pay — API de Transações POS (Authorize / Confirm / Void)

API HTTP que orquestra transações de POS. Atua como _man-in-the-middle_: recebe as
requisições do POS/cliente, aplica as regras (autenticação, idempotência, lookup,
máquina de estados) e chama uma **API externa** (mock) que de fato executa
autorização, confirmação e desfazimento. É síncrona, idempotente, resiliente a
falhas da dependência externa e preparada para rodar em múltiplas instâncias
(cloud-native / Kubernetes).

## Stack

- Java 21, Spring Boot 3.4.5
- Spring Web + Bean Validation
- Spring Data JPA + PostgreSQL
- Resilience4j (Circuit Breaker, Retry, Bulkhead)
- Micrometer Tracing + OpenTelemetry (OTLP)
- ULID (`ulid-creator`) para o `transactionId`
- Testes: JUnit 5, Mockito, Testcontainers

## Como rodar

Pré-requisitos: JDK 21 e Docker.

```bash
# 1) Sobe Postgres + Jaeger (coletor de traces) localmente
docker compose up -d

# 2) Sobe a aplicação
./mvnw spring-boot:run
```

A API fica em `http://localhost:8080`. A UI de tracing (Jaeger) em
`http://localhost:16686`. Métricas e health em `http://localhost:8080/actuator`
(inclui `health`, `metrics`, `circuitbreakers`, `prometheus`).

### Testes

```bash
./mvnw test     # unitários (sem Docker)
./mvnw verify   # inclui os testes de integração *IT (exigem Docker / Testcontainers)
```

## Conceitos e campos

- **nsu**: identificador da transação no terminal.
- **terminalId**: identificador do terminal POS.
- **amount**: valor da transação.
- **transactionId**: id único gerado pela API após a autorização. É um **ULID**
  (único globalmente, ordenável no tempo, sem coordenação entre instâncias). O
  cliente não escolhe esse valor.
- **Associação obrigatória**: `(terminalId + nsu) -> transactionId`, o que permite
  desfazer (void) por `nsu + terminalId`.

### Estados

```
AUTHORIZED ──► CONFIRMED
           └─► VOIDED
```

Confirmar uma transação `VOIDED` ou desfazer uma `CONFIRMED` é transição inválida
(HTTP 409).

## Endpoints

Todos os endpoints estão sob `/v1/pos/transactions` e exigem autenticação HMAC
(ver seção [Segurança](#segurança-hmac)).

### 1) Autorizar — `POST /v1/pos/transactions/authorize`

Request:

```json
{ "nsu": "123456", "amount": 199.90, "terminalId": "T-1000" }
```

Chama a API externa para autorizar; em sucesso gera o `transactionId`, persiste a
transação e o vínculo `(terminalId, nsu) -> transactionId` e responde **200**:

```json
{ "nsu": "123456", "amount": 199.90, "terminalId": "T-1000", "transactionId": "01HZX...ABC" }
```

**Idempotência**: repetições com o mesmo `(terminalId + nsu)` retornam **200** com o
**mesmo** `transactionId`, sem criar nova transação e sem nova chamada externa.

### 2) Confirmar — `POST /v1/pos/transactions/confirm`

Request:

```json
{ "transactionId": "01HZX...ABC" }
```

Localiza a transação e chama a API externa para confirmar. Retorna **204 No Content**.
Confirm repetido é no-op idempotente (também **204**).

### 3) Desfazer (void) — `POST /v1/pos/transactions/void`

Aceita uma de duas formas:

```json
{ "transactionId": "01HZX...ABC" }
```

```json
{ "nsu": "123456", "terminalId": "T-1000" }
```

Resolve o `transactionId` (diretamente ou pelo vínculo `nsu + terminalId`), chama a
API externa para desfazer e retorna **204 No Content**. Void repetido é no-op
idempotente (também **204**).

### Códigos de resposta

| Situação                                             | Status |
|------------------------------------------------------|--------|
| Authorize ok                                         | 200    |
| Confirm / Void ok (ou repetição idempotente)         | 204    |
| Payload inválido                                     | 400    |
| Falha de autenticação HMAC                           | 401    |
| Transação não encontrada                             | 404    |
| Transição de estado inválida                         | 409    |
| Dependência externa indisponível (circuito aberto)   | 503    |

Erros usam `application/problem+json` (RFC 7807).

## Idempotência distribuída

A aplicação **não depende de estado local em memória** — o requisito é rodar em
vários pods simultaneamente. A unicidade é garantida no banco:

- Constraint `UNIQUE (terminal_id, nsu)` (`uk_transaction_terminal_nsu`) é a fonte
  de verdade da idempotência do authorize.
- Fluxo do authorize: (1) lookup por `(terminalId, nsu)` — se existe, retorna o
  `transactionId` existente; (2) senão, gera o ULID, chama a externa e faz o INSERT.
- **Corrida entre pods**: se dois pods tentam autorizar o mesmo par ao mesmo tempo,
  o INSERT perdedor recebe `DataIntegrityViolationException`; a aplicação relê e
  devolve a transação vencedora. Assim, mesmo concorrentemente, há **um único**
  `transactionId` para cada `(terminalId, nsu)`.
- Confirm/void são no-op quando a transação já está no estado final correspondente.

**Limitação conhecida / produção**: a janela entre "chamar a externa" e "persistir"
pode, em corrida rara, gerar uma chamada externa duplicada (o INSERT perdedor já
chamou a externa). O efeito colateral no domínio é nulo (continua um só
`transactionId`), mas para eliminar a duplicidade da chamada externa em produção
usaríamos uma **chave de idempotência** propagada à API externa (header
`Idempotency-Key = terminalId:nsu`) ou um _claim_ prévio do slot
`(terminalId, nsu)` antes da chamada externa.

## Resiliência — anti-cascata

Como a API depende de uma API externa, há um mecanismo explícito para evitar
**cascading failures**. Tudo configurado na instância Resilience4j `externalApi`
(ver `application.properties`). A ordem dos aspectos é
**Retry (externo) → CircuitBreaker → Bulkhead (interno)**.

### Timeouts

As chamadas externas não podem ficar penduradas. No mock in-process o "timeout" é
representado pela latência simulada; ao trocar pelo cliente HTTP real, os timeouts de
conexão/leitura (`external.api.connect-timeout`, `external.api.read-timeout`) impõem o
limite duro por chamada. Há ainda `timeout-duration=3s` configurado para uso com
`@TimeLimiter` quando o fluxo for assíncrono (`CompletableFuture`).

### Retry controlado (sem retry storm)

- Apenas **falha transitória** (`ExternalApiException`) é re-tentada — circuito
  aberto e bulkhead cheio **não** são re-tentados.
- `max-attempts=3`, backoff **exponencial** (`200ms`, multiplicador `2`) com
  **jitter** (`randomized-wait-factor=0.5`). O jitter dessincroniza os clientes e
  evita o "retry storm" (todos re-tentando no mesmo instante).

### Circuit Breaker — sinais de abertura/fechamento

- Janela por contagem (`COUNT_BASED`) das últimas **20** chamadas, mínimo de **10**
  para avaliar.
- **Abre** quando a taxa de falha ≥ **50%** _ou_ a taxa de chamadas lentas (acima de
  `2s`) ≥ **50%**.
- Em **OPEN**, fica `wait-duration-in-open-state=10s` e então transiciona
  automaticamente para **HALF_OPEN**, liberando **3** chamadas de teste; se
  passarem, fecha; senão, reabre.

### Bulkhead (limite de concorrência)

`max-concurrent-calls=20` isola a dependência: uma API externa lenta não consome
todas as threads/conexões do serviço. Excedido o limite, a chamada falha rápido em
vez de enfileirar indefinidamente. O pool de conexões do banco (HikariCP) também é
limitado, protegendo os recursos locais.

### Comportamento com o circuito aberto

Quando o circuito está **aberto** (ou o bulkhead está cheio, ou os retries se
esgotam), o `fallbackMethod` converte a falha em `ExternalApiUnavailableException`,
e a API responde **503 Service Unavailable** com `Retry` implícito a cargo do
cliente — falha rápida e explícita, sem propagar a falha em cascata nem segurar
recursos locais.

### Como exercitar

Ajuste no `application.properties` (ou via variável de ambiente) para simular
degradação da dependência:

```properties
external.api.simulated-failure-rate=1.0   # 0.0 a 1.0
external.api.simulated-latency-ms=2500     # força chamadas "lentas"
```

## Segurança (HMAC)

Todos os requests aos endpoints `/v1/pos/` precisam estar assinados, para evitar
requisições forjadas e replay.

- Headers obrigatórios: **`X-Signature`** (HMAC-SHA256, hex minúsculo) e
  **`X-Timestamp`** (epoch em segundos).
- **String canônica assinada**: `{timestamp}.{MÉTODO}.{path}.{body}`.
- O servidor recalcula o HMAC com o segredo compartilhado (`security.hmac.secret`) e
  compara em **tempo constante**.
- **Anti-replay**: o `X-Timestamp` precisa estar dentro de
  `security.hmac.timestamp-tolerance-seconds` (padrão 300s) em relação ao relógio do
  servidor.
- Falha de validação → **401**. Pode ser desligado em dev/testes com
  `security.hmac.enabled=false`.

Exemplo de geração da assinatura (shell):

```bash
TS=$(date +%s)
BODY='{ "nsu": "123456", "amount": 199.90, "terminalId": "T-1000" }'
SECRET='dev-secret-troque-em-producao'
CANON="${TS}.POST./v1/pos/transactions/authorize.${BODY}"
SIG=$(printf '%s' "$CANON" | openssl dgst -sha256 -hmac "$SECRET" | awk '{print $2}')

curl -X POST http://localhost:8080/v1/pos/transactions/authorize \
  -H "Content-Type: application/json" \
  -H "X-Timestamp: $TS" \
  -H "X-Signature: $SIG" \
  -d "$BODY"
```

**Produção**: o segredo deve vir de variável de ambiente/secret manager, nunca do
arquivo de propriedades. Para replay mais forte, somar um `nonce` de uso único
persistido por uma janela curta.

## Coleção Postman

O arquivo `ASA-Pay.postman_collection.json` (na raiz do projeto) já vem com os 4
requests (Authorize, Confirm, Void por `transactionId` e Void por `nsu + terminalId`)
e assina cada requisição automaticamente.

**Importar**: no Postman, `Import` → selecione `ASA-Pay.postman_collection.json`.

**Configurar as variáveis da coleção** (aba _Variables_ da coleção):

| Variável | Valor padrão | Observação |
|---|---|---|
| `baseUrl` | `http://localhost:8080` | URL da API |
| `hmacSecret` | `dev-secret-troque-em-producao` | **precisa ser igual** ao `security.hmac.secret` do servidor |
| `transactionId` | _(vazio)_ | preenchida automaticamente pelo Authorize |

**Como funciona**: um _Pre-request Script_ no nível da coleção gera, a cada
requisição, o `X-Timestamp` (epoch atual) e o `X-Signature` (HMAC-SHA256 da string
canônica `{timestamp}.{método}.{path}.{body}`). Ele resolve as `{{variáveis}}` do
corpo antes de assinar, garantindo que o texto assinado seja idêntico ao enviado. O
Authorize tem um _test script_ que salva o `transactionId` retornado na variável da
coleção, então o Confirm e o Void o reaproveitam.

**Como usar**: rode os requests na ordem — `1. Authorize` → `2. Confirm` ou
`3./4. Void`. Os corpos estão minificados (uma linha) de propósito: JSON
pretty-printed introduz quebras de linha que divergem entre o que o Postman assina e
o que envia, quebrando a assinatura.

**Dica de depuração**: se um request retornar **401**, abra o Postman Console
(`View → Show Postman Console`) e compare o `SIG>>>` impresso pelo script com o
`X-Signature` enviado. Se forem iguais, o problema é o `hmacSecret` diferir do
servidor; caso contrário, é o corpo/assinatura.

## Observabilidade

- **Correlation ID obrigatório**: o `CorrelationIdFilter` usa o header
  `X-Correlation-Id` recebido ou gera um; coloca no MDC (aparece nos logs), o ecoa
  na resposta e o anexa ao span atual como a tag `correlation.id` (pesquisável no
  Jaeger em _Tags_).
- **Tracing**: Micrometer Tracing + OpenTelemetry exportando via **OTLP gRPC** para
  `localhost:4317` (Jaeger, no `docker-compose.yml`). Sampling 100% em dev.
- Logs incluem `correlationId`, `traceId` e `spanId`.

## Estrutura

```
com.asa.pay
├── api            # Controller, DTOs (records) e GlobalExceptionHandler
├── domain         # Transaction (entidade JPA) e TransactionStatus
├── repository     # TransactionRepository (Spring Data JPA)
├── service        # TransactionService (orquestração + idempotência)
├── external       # Contrato e mock resiliente da API externa
├── security       # Filtro HMAC, assinador e wrapper de body
├── web            # CorrelationIdFilter
└── exception      # Exceções de domínio mapeadas para HTTP
```

## Limitações e próximos passos

- A API externa é um **mock in-process** (permitido pelo desafio). Próximo passo:
  cliente HTTP real (RestClient/WebClient) com `@TimeLimiter` para timeout duro no
  fluxo assíncrono.
- Idempotência da chamada externa em corrida: ver nota na seção de idempotência
  (propagar `Idempotency-Key`).
- Migrações de schema via Flyway/Liquibase em vez de `ddl-auto=update` em produção.
```
