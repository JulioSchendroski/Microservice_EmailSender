# Relatório de Performance — Síncrono (HTTP) vs Assíncrono (RabbitMQ)

**Projeto:** Microservice_EmailSender
**Data:** 2026-09-01
**Autor:** benchmark automatizado (`EmailPerformanceLoadTest`)

---

## 1. Objetivo

Comparar, sob carga, os dois modos de envio de e-mail do microserviço:

- **Síncrono** — `POST /sending-email`: o cliente HTTP **bloqueia** até o e-mail ser enviado (SMTP) e persistido. Resposta reflete o resultado real (`201`/`502`).
- **Assíncrono** — `POST /sending-email/async`: a solicitação é **enfileirada** no RabbitMQ e o cliente recebe `202 Accepted` imediatamente; o envio real ocorre em background por um ou mais consumers.

Buscamos responder:
1. Qual a diferença de latência, throughput e tempo total entre os modos?
2. **Em que cenário sync ≈ async** (empatam)?
3. **A partir de qual cenário o async compensa** de forma clara?
4. Qual o efeito de aumentar o número de consumers na drenagem da fila?

> **Nenhum e-mail real é enviado.** O SMTP é 100% mockado por um `JavaMailSender` fake (`LatencyMockMailSender`) que apenas **simula a latência** de um servidor real. Isso vale mesmo que existam credenciais SMTP reais no ambiente.

---

## 2. Ferramentas e ambiente

| Item | Detalhe |
|------|---------|
| Linguagem/Framework | Java 21 (runtime) / Spring Boot 3.3.5 |
| Motor de carga | Teste JUnit customizado (`EmailPerformanceLoadTest`) com pool de threads (`ExecutorService`), medição por requisição via `System.nanoTime()` |
| Broker | RabbitMQ 3.13 (container Docker efêmero, porta 5673) |
| Banco | H2 em memória (isola o custo do Postgres) |
| SMTP | **Mockado** (`LatencyMockMailSender`) — latência simulada de **150 ms** por envio |
| Build | Maven (Failsafe roda o benchmark, isolado do `mvn test`) |

**Métricas coletadas por cenário:** tempo total (wall clock), throughput (req/s), latência média, p50, p95, p99, latência máxima, erros e — no async — **tempo de drenagem da fila** (até todas as mensagens serem processadas e persistidas no banco).

### Metodologia

- **Volume fixo:** 1.000 requisições por cenário.
- **Sweep de concorrência:** 1, 5, 10, 25, 50, 100 clientes simultâneos (threads disparando requisições).
- Para cada nível de concorrência, dispara-se o mesmo volume contra o endpoint **síncrono** e depois o **assíncrono**, medindo os dois.
- A latência SMTP simulada (150 ms) é a variável que torna a comparação realista: é o custo que o modo síncrono paga por requisição e que o assíncrono empurra para background.
- Dois experimentos: **1 consumer** (baseline) e **10 consumers** (drenagem paralela).

### Como reproduzir

```bash
# baseline (1 consumer), sweep completo
./scripts/run-load-test.sh   # usa defaults, ou:
mvn verify \
  -Dload.requests=1000 \
  -Dload.concurrency.sweep="1,5,10,25,50,100" \
  -Dtest.smtp.latency.ms=150 \
  -Dloadtest.consumers=1 \
  -Dloadtest.rabbitmq.addresses="amqp://guest:guest@localhost:5673"

# drenagem paralela (10 consumers)
mvn verify -Dloadtest.consumers=10 -Dload.concurrency.sweep="50,100" ... 
```

---

## 3. Resultados — 1 consumer (baseline)

Volume: 1.000 req/cenário · SMTP: 150 ms · Consumers: 1

### 3.1 Modo SÍNCRONO (`POST /sending-email`)

| Concorrência | Tempo total | Throughput | Lat. média | p50 | p95 | p99 | Máx | Erros |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1   | 168.254 ms | 5,9 req/s  | 167,7 ms  | 166 | 174 | 183 | 893 | 0 |
| 5   | 32.370 ms  | 30,9 req/s | 161,2 ms  | 160 | 169 | 179 | 192 | 0 |
| 10  | 16.047 ms  | 62,3 req/s | 159,5 ms  | 158 | 166 | 179 | 203 | 0 |
| 25  | 15.309 ms  | 65,3 req/s | 378,0 ms  | 425 | 465 | 607 | 758 | 0 |
| 50  | 15.308 ms  | 65,3 req/s | 744,3 ms  | 760 | 1362 | 1369 | 1976 | 0 |
| 100 | 15.319 ms  | 65,3 req/s | 1441,3 ms | 1520 | 1543 | 2886 | 2907 | 0 |

**Leitura:** a latência por requisição fica ancorada em ~150-167 ms (a latência do SMTP) enquanto há capacidade ociosa. O throughput **satura em ~65 req/s** a partir de concorrência 25 — esse é o teto do modo síncrono neste ambiente. Acima disso, aumentar clientes só **empilha fila de espera**: a latência explode (de 160 ms para 744 ms com 50 clientes, 1441 ms com 100), mas o throughput não sobe.

### 3.2 Modo ASSÍNCRONO (`POST /sending-email/async`)

| Concorrência | Tempo total (aceite) | Throughput | Lat. média | p50 | p95 | p99 | Máx | Drenagem da fila |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1   | 8.285 ms | 120,7 req/s  | 7,7 ms  | 7  | 12  | 16  | 55  | 151.208 ms |
| 5   | 2.055 ms | 486,6 req/s  | 9,7 ms  | 8  | 20  | 32  | 53  | 155.901 ms |
| 10  | 1.134 ms | 881,8 req/s  | 10,7 ms | 9  | 22  | 29  | 35  | 156.308 ms |
| 25  | 950 ms   | 1052,6 req/s | 21,9 ms | 17 | 61  | 77  | 114 | 156.034 ms |
| 50  | 1.046 ms | 956,0 req/s  | 42,5 ms | 26 | 144 | 184 | 248 | 156.157 ms |
| 100 | 1.145 ms | 873,4 req/s  | 72,2 ms | 45 | 204 | 276 | 299 | 155.613 ms |

**Leitura:** o cliente só paga o custo de **enfileirar** (7-72 ms), não a latência do SMTP. O throughput de aceite chega a **~1.000 req/s** (17x o síncrono). Porém, com **1 consumer**, a **drenagem** é sempre ~155 s — porque 1.000 e-mails × 150 ms processados serialmente = ~150 s. Ou seja: o async responde rápido e absorve o pico, mas o trabalho real leva o mesmo tempo para ser concluído com um único worker.

### 3.3 Comparação lado a lado (tempo de resposta ao cliente)

| Concorrência | Sync lat. média | Async lat. média | Ganho de latência | Sync thrpt | Async thrpt | Ganho de throughput |
|---:|---:|---:|---:|---:|---:|---:|
| 1   | 167,7 ms  | 7,7 ms  | **22x** | 5,9   | 120,7  | **20x** |
| 5   | 161,2 ms  | 9,7 ms  | **17x** | 30,9  | 486,6  | **16x** |
| 10  | 159,5 ms  | 10,7 ms | **15x** | 62,3  | 881,8  | **14x** |
| 25  | 378,0 ms  | 21,9 ms | **17x** | 65,3  | 1052,6 | **16x** |
| 50  | 744,3 ms  | 42,5 ms | **18x** | 65,3  | 956,0  | **15x** |
| 100 | 1441,3 ms | 72,2 ms | **20x** | 65,3  | 873,4  | **13x** |

---

## 4. Resultados — 10 consumers (drenagem paralela)

Volume: 1.000 req/cenário · SMTP: 150 ms · Consumers: **10**

| Modo | Conc. | Tempo total | Throughput | Lat. média | p95 | p99 | Drenagem |
|------|---:|---:|---:|---:|---:|---:|---:|
| sync  | 50  | 17.015 ms | 58,8 req/s  | 830,6 ms  | 1416 | 1969 | — |
| async | 50  | 2.184 ms  | 457,9 req/s | 95,8 ms   | 270  | 375  | **13.886 ms** |
| sync  | 100 | 15.399 ms | 64,9 req/s  | 1452,3 ms | 1554 | 2909 | — |
| async | 100 | 1.845 ms  | 542,0 req/s | 133,8 ms  | 453  | 563  | **13.877 ms** |

**Efeito dos consumers na drenagem:**

| Consumers | Drenagem de 1.000 e-mails | Redução |
|---:|---:|---:|
| 1  | ~155.600 ms (~2min36s) | — |
| 10 | ~13.880 ms (~14s)      | **~11,2x mais rápido** |

Com 10 consumers processando em paralelo, o tempo total de processamento cai de ~2,5 min para ~14 s. A drenagem escala quase linearmente com o número de consumers (10 consumers ≈ 1/11 do tempo), limitada pela latência individual do SMTP e pela contenção de recursos.

---

## 5. Análise: quando sync ≈ async e quando async compensa

### 5.1 Onde os dois modos "empatam"

Do ponto de vista de **latência percebida pelo cliente**, sync e async **nunca empatam** neste desenho: mesmo com 1 cliente, o síncrono paga 167 ms e o async 8 ms. O empate só aconteceria se a latência do SMTP fosse ~0 — ou seja, **quando o trabalho por trás do endpoint é trivial** (sem I/O externo lento), aí os dois modos ficam equivalentes e o async só adiciona complexidade (broker, serialização, consumer) sem ganho.

Do ponto de vista de **conclusão total do trabalho** (throughput real de e-mails entregues), sync e async **empatam quando o número de consumers ≈ concorrência do cliente síncrono**. Com 1 consumer, o async drena a 1.000 e-mails em ~155 s; o síncrono com concorrência 10 termina os mesmos 1.000 em ~16 s. Ou seja, **em vazão de conclusão, o síncrono com N threads equivale ao async com N consumers.** A vantagem do async não é fazer o trabalho mais rápido — é **desacoplar** o aceite da execução.

### 5.2 A partir de qual cenário o async compensa

O ponto de virada aparece já em **concorrência ≥ 1** para latência, mas o benefício se torna **decisivo a partir de concorrência 25**, quando o síncrono **satura** (throughput trava em ~65 req/s e a latência começa a crescer sem limite):

| Faixa de carga | Recomendação | Por quê |
|---|---|---|
| **Baixíssima** (1 req por vez, trabalho trivial) | Síncrono | Simplicidade; async não agrega |
| **Baixa/média com I/O lento** (SMTP ~150 ms, poucos clientes) | Async preferível | Latência ao cliente 15-22x menor |
| **Concorrência ≥ 25** (saturação do sync) | **Async fortemente recomendado** | Sync satura em ~65 req/s e a latência explode (760 ms → 2900 ms); async mantém aceite <1.100 ms e absorve o pico |
| **Picos de tráfego / rajadas** | **Async obrigatório** | O sync derruba a latência de todos; o async enfileira e drena no ritmo dos consumers |

**Resumo do ponto de virada:** neste ambiente (SMTP 150 ms), o síncrono satura em **~65 req/s** por volta de **25 clientes concorrentes**. A partir daí:
- **Latência do síncrono** cresce linearmente com a concorrência (fila de espera): 378 ms (25) → 744 ms (50) → 1.441 ms (100).
- **Latência do assíncrono** permanece baixa: 22 ms (25) → 42 ms (50) → 72 ms (100).

O async passa a compensar de forma clara **exatamente quando a taxa de chegada supera a capacidade de processamento síncrono** (~65 req/s aqui). Antes disso, ambos entregam o trabalho em tempo parecido; depois disso, o síncrono degrada e o assíncrono se mantém responsivo.

### 5.3 Trade-off do assíncrono

- **Ganho:** latência ao cliente 13-22x menor; throughput de aceite ~15x maior; resiliência a picos.
- **Custo:** o trabalho não fica pronto na hora. Com poucos consumers, a fila drena lentamente (155 s com 1 consumer). É preciso **dimensionar consumers** conforme o volume — com 10 consumers a drenagem cai para ~14 s.
- **Complexidade:** exige broker (RabbitMQ), tratamento de falhas (retry + DLQ, já implementados) e observabilidade da fila.

---

## 6. Conclusões

1. **Latência ao cliente:** o assíncrono é **13-22x mais rápido** em todos os cenários, porque não paga a latência do SMTP.
2. **Saturação do síncrono:** trava em **~65 req/s** a partir de ~25 clientes; acima disso a latência cresce sem limite (até ~2,9 s no p99 com 100 clientes).
3. **Ponto de virada:** o async compensa de forma **decisiva a partir de concorrência ≥ 25** (quando a taxa de chegada supera a capacidade do sync). Em cargas muito baixas com trabalho trivial, os dois se equivalem e o sync é mais simples.
4. **Consumers importam:** a drenagem escala quase linearmente — de ~155 s (1 consumer) para ~14 s (10 consumers), ~11x. O async responde rápido independentemente, mas a **conclusão** do trabalho depende do número de consumers.
5. **Recomendação:** usar **síncrono** quando o chamador precisa da confirmação imediata do envio e o volume é baixo; usar **assíncrono** para alto volume, picos, ou quando a latência de resposta ao cliente é crítica — dimensionando os consumers para a vazão desejada.

---

## 7. Dados brutos (CSV)

### 1 consumer
```
modo;concorrencia;requests;wall_ms;throughput_req_s;lat_media_ms;p50_ms;p95_ms;p99_ms;max_ms;erros;drain_ms
sync;1;1000;168254;5.9;167.7;166;174;183;893;0;
async;1;1000;8285;120.7;7.7;7;12;16;55;0;151208
sync;5;1000;32370;30.9;161.2;160;169;179;192;0;
async;5;1000;2055;486.6;9.7;8;20;32;53;0;155901
sync;10;1000;16047;62.3;159.5;158;166;179;203;0;
async;10;1000;1134;881.8;10.7;9;22;29;35;0;156308
sync;25;1000;15309;65.3;378.0;425;465;607;758;0;
async;25;1000;950;1052.6;21.9;17;61;77;114;0;156034
sync;50;1000;15308;65.3;744.3;760;1362;1369;1976;0;
async;50;1000;1046;956.0;42.5;26;144;184;248;0;156157
sync;100;1000;15319;65.3;1441.3;1520;1543;2886;2907;0;
async;100;1000;1145;873.4;72.2;45;204;276;299;0;155613
```

### 10 consumers
```
modo;concorrencia;requests;wall_ms;throughput_req_s;lat_media_ms;p50_ms;p95_ms;p99_ms;max_ms;erros;drain_ms
sync;50;1000;17015;58.8;830.6;788;1416;1969;2597;0;
async;50;1000;2184;457.9;95.8;62;270;375;526;0;13886
sync;100;1000;15399;64.9;1452.3;1532;1554;2909;4278;0;
async;100;1000;1845;542.0;133.8;58;453;563;769;0;13877
```

> Observações de ambiente: o benchmark roda com SMTP mockado (nenhum e-mail real). Testcontainers não foi usado por incompatibilidade da API do Docker do host (cliente 1.32 < mínimo 1.44); o RabbitMQ foi provisionado via `docker run` com `--tmpfs /var/lib/rabbitmq` e `RABBITMQ_ERLANG_COOKIE` para contornar um erro de permissão (`eacces`) no cookie do Erlang.
