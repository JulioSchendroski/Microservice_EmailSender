#!/usr/bin/env bash
#
# Benchmark sync vs async — SMTP 100% mockado (nenhum e-mail real é enviado).
#
# Sobe um RabbitMQ efêmero via `docker run`, executa o teste de carga
# (EmailPerformanceLoadTest) apontando para esse broker, imprime o relatório
# comparativo e derruba o container ao final.
#
# Uso:
#   ./scripts/run-load-test.sh [REQUESTS] [CONCURRENCY] [SMTP_LATENCY_MS]
#
# Exemplo:
#   ./scripts/run-load-test.sh 500 100 150
#
# Requisitos: Docker em execução e Maven (mvn) ou ./mvnw no PATH.

set -euo pipefail

REQUESTS="${1:-200}"
CONCURRENCY="${2:-50}"
SMTP_LATENCY_MS="${3:-150}"

RABBIT_NAME="emailsender-loadtest-rabbit"
RABBIT_PORT="${RABBIT_PORT:-5673}"          # porta host (evita conflito com 5672 local)
RABBIT_IMAGE="rabbitmq:3.13-management"
RABBIT_ADDRESSES="amqp://guest:guest@localhost:${RABBIT_PORT}"

# Escolhe o comando Maven disponível
if command -v mvn >/dev/null 2>&1; then
  MVN="mvn"
elif [ -x "./mvnw" ]; then
  MVN="./mvnw"
else
  echo "ERRO: nem 'mvn' nem './mvnw' encontrados." >&2
  exit 1
fi

cleanup() {
  echo "==> Derrubando RabbitMQ ($RABBIT_NAME)..."
  docker rm -f "$RABBIT_NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "==> Removendo container antigo (se houver)..."
docker rm -f "$RABBIT_NAME" >/dev/null 2>&1 || true

echo "==> Subindo RabbitMQ ($RABBIT_IMAGE) na porta $RABBIT_PORT..."
docker run -d --name "$RABBIT_NAME" \
  -p "${RABBIT_PORT}:5672" \
  "$RABBIT_IMAGE" >/dev/null

echo "==> Aguardando RabbitMQ ficar pronto..."
for i in $(seq 1 60); do
  if docker exec "$RABBIT_NAME" rabbitmq-diagnostics -q ping >/dev/null 2>&1; then
    echo "    RabbitMQ pronto."
    break
  fi
  sleep 1
  if [ "$i" -eq 60 ]; then
    echo "ERRO: RabbitMQ não ficou pronto a tempo." >&2
    exit 1
  fi
done

echo "==> Executando benchmark (requests=$REQUESTS concurrency=$CONCURRENCY smtpLatencyMs=$SMTP_LATENCY_MS)..."
$MVN -q verify \
  -Dload.requests="$REQUESTS" \
  -Dload.concurrency="$CONCURRENCY" \
  -Dtest.smtp.latency.ms="$SMTP_LATENCY_MS" \
  -Dloadtest.rabbitmq.addresses="$RABBIT_ADDRESSES"

echo "==> Benchmark finalizado."
