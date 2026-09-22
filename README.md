# Microservice_EmailSender

Microserviço em Java/Spring Boot para envio de e-mails, suportando **dois modos** de operação:

- **Síncrono (HTTP):** o e-mail é enviado na hora e a resposta reflete o resultado.
- **Assíncrono (RabbitMQ):** a solicitação é enfileirada e processada por um consumer, com retry e Dead Letter Queue (DLQ).

## Stack

- Java 17 (compilável em Java 21)
- Spring Boot 3.3.5 (Web, Data JPA, Mail, Validation, AMQP)
- PostgreSQL
- RabbitMQ
- springdoc-openapi (Swagger UI)

## Endpoints

| Método | Rota | Modo | Resposta |
|--------|------|------|----------|
| `POST` | `/sending-email` | Síncrono | `201 Created` (enviado) / `502 Bad Gateway` (falha no envio) / `400` (validação) |
| `POST` | `/sending-email/async` | Assíncrono (enfileira) | `202 Accepted` |
| `GET`  | `/emails` | Consulta paginada | `200 OK` |
| `GET`  | `/emails/{emailId}` | Consulta por ID | `200 OK` / `404 Not Found` |

Swagger UI: `http://localhost:8080/swagger-ui.html`

### Corpo da requisição (ambos os POST)

```json
{
  "ownerRef": "user-123",
  "emailFrom": "no-reply@exemplo.com",
  "emailTo": "destino@exemplo.com",
  "subject": "Assunto",
  "text": "Conteúdo do e-mail"
}
```

## Arquitetura

```
                         ┌─────────────────────────┐
  POST /sending-email    │      EmailController     │
 ─────────────────────▶  │  (síncrono)              │──▶ EmailService.createAndSend ──▶ SMTP
                         │                          │        (201 / 502)
  POST .../async         │  (assíncrono)            │──▶ EmailProducer ──▶ RabbitMQ
 ─────────────────────▶  └─────────────────────────┘                          │
                                                                               ▼
                                                    EmailConsumer ◀──── email.queue
                                                          │
                                                          ├─ sucesso: SENT
                                                          └─ falha (após retries): ──▶ email.dlq (via DLX)
```

### Topologia RabbitMQ

- Exchange: `email.exchange` (direct) — routing key `email.send`
- Fila: `email.queue` (durável, com dead-lettering)
- DLX: `email.dlx` → DLQ: `email.dlq` (routing key `email.dead`)
- Retry no consumer: 3 tentativas com backoff exponencial (2s → x2, máx 10s); após esgotar, mensagem vai para a DLQ.

## Configuração (variáveis de ambiente)

Todas as credenciais são externalizadas. Defina via ambiente (há defaults para desenvolvimento local):

| Variável | Descrição | Default |
|----------|-----------|---------|
| `SERVER_PORT` | Porta HTTP | `8080` |
| `DB_URL` | URL do PostgreSQL | `jdbc:postgresql://localhost:5432/ms_email` |
| `DB_USERNAME` / `DB_PASSWORD` | Credenciais do banco | `postgres` / `postgres` |
| `MAIL_HOST` / `MAIL_PORT` | Servidor SMTP | `smtp.gmail.com` / `587` |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | Credenciais SMTP | vazio |
| `RABBITMQ_ADDRESSES` | Endereço(s) do broker | `amqp://guest:guest@localhost:5672` |

## Executando

```bash
# infra local (exemplo)
docker run -d -p 5432:5432 -e POSTGRES_DB=ms_email -e POSTGRES_PASSWORD=postgres postgres:16
docker run -d -p 5672:5672 -p 15672:15672 rabbitmq:3-management

# aplicação
./mvnw spring-boot:run
```

## Build e testes

```bash
./mvnw clean verify
```

Os testes de contexto sobem com H2 em memória e sem broker/SMTP reais (mocks), então não exigem infraestrutura externa. O benchmark de carga (`*LoadTest`) roda na fase `verify` (Failsafe), mas o cenário assíncrono é **pulado** se nenhum RabbitMQ for informado (veja abaixo). O `./mvnw test` executa apenas os testes unitários/contexto, sem o benchmark.

## Comparação de performance: síncrono vs assíncrono

Um teste de carga compara os dois modos disparando N requisições concorrentes contra cada endpoint e imprimindo latência (média, p50/p95/p99), throughput e tempo total.

**Nenhum e-mail real é enviado.** O SMTP é 100% mockado por `LatencyMockMailSender`, que apenas simula a latência de um servidor real (configurável). Isso vale mesmo que existam credenciais SMTP reais no ambiente.

### Rodando o benchmark (recomendado)

Requer Docker. O script sobe um RabbitMQ efêmero, roda o benchmark e o derruba ao final:

```bash
./scripts/run-load-test.sh [REQUESTS] [CONCURRENCY] [SMTP_LATENCY_MS]
# exemplo:
./scripts/run-load-test.sh 200 50 150
```

Ou manualmente, apontando para um RabbitMQ já existente:

```bash
./mvnw verify \
  -Dload.requests=200 -Dload.concurrency=50 -Dtest.smtp.latency.ms=150 \
  -Dloadtest.rabbitmq.addresses="amqp://guest:guest@localhost:5672"
```

Parâmetros (todos via `-D`):

| Propriedade | Default | Descrição |
|-------------|---------|-----------|
| `load.requests` | `200` | Total de requisições por cenário |
| `load.concurrency` | `50` | Threads concorrentes disparando |
| `test.smtp.latency.ms` | `150` | Latência simulada do SMTP mockado |
| `loadtest.rabbitmq.addresses` | — | Endereço do broker; se ausente, o cenário async é pulado |

### Resultado de exemplo

Execução local com `requests=200`, `concurrency=50`, `smtpLatencyMs=150`:

| Métrica | Síncrono (`/sending-email`) | Assíncrono (`/sending-email/async`) |
|---------|------------------------------|--------------------------------------|
| Tempo total (wall) | 3827 ms | **450 ms** |
| Throughput | 52 req/s | **444 req/s** |
| Latência média | 866 ms | **79 ms** |
| Latência p95 | 1434 ms | **170 ms** |
| Latência máx | 2033 ms | 233 ms |
| Erros | 0 | 0 |
| Drenagem da fila → banco | — | ~30,6 s |

**Leitura dos números:** no modo síncrono o cliente **bloqueia** durante todo o envio SMTP (~150ms × fila de espera pela concorrência), resultando em latência ~11x maior e throughput ~8x menor. O modo assíncrono responde imediatamente (`202 Accepted`) e o trabalho pesado é drenado em background pelo consumer — o cliente não paga a latência do SMTP. Note que o *processamento total* do async (drenagem) leva mais tempo porque um único consumer processa a fila serialmente; o ganho é na **latência percebida pelo cliente** e na **capacidade de absorver picos** sem derrubar o tempo de resposta.

O profile `loadtest` ativa o `LoadTestMailConfig`, que substitui o `JavaMailSender` real por um mock com latência (`loadtest.smtp.latency-ms`, default 150ms) — garantindo que nenhum e-mail seja enviado.
