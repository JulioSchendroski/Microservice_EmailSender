#!/usr/bin/env bash
#
# Roda o teste de carga k6 via Docker (imagem oficial grafana/k6),
# sem precisar instalar o k6 localmente.
#
# Pré-requisito: a aplicação deve estar rodando com SMTP MOCKADO.
# Suba a app com o profile 'loadtest' (não envia e-mail real):
#
#   MAIL_HOST=localhost MAIL_PORT=2525 \
#   SPRING_PROFILES_ACTIVE=loadtest ./mvnw spring-boot:run
#
# (ou simplesmente aponte MAIL_* para um SMTP fake/porta inexistente e trate a falha)
#
# Uso:
#   ./scripts/run-k6.sh [BASE_URL] [VUS] [REQUESTS]
#
# Exemplo:
#   ./scripts/run-k6.sh http://host.docker.internal:8080 100 1000

set -euo pipefail

BASE_URL="${1:-http://host.docker.internal:8080}"
VUS="${2:-50}"
REQUESTS="${3:-500}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "==> Rodando k6 (VUS=$VUS REQUESTS=$REQUESTS) contra $BASE_URL"
docker run --rm -i \
  --add-host=host.docker.internal:host-gateway \
  -e BASE_URL="$BASE_URL" \
  -e VUS="$VUS" \
  -e REQUESTS="$REQUESTS" \
  -v "$SCRIPT_DIR:/scripts" \
  grafana/k6 run /scripts/k6-load-test.js
