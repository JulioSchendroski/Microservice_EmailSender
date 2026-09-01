// Teste de carga HTTP com k6 comparando sync vs async.
//
// Executa dois cenários em sequência (grupos), medindo latência por endpoint.
// Rode via Docker (não precisa instalar k6): veja scripts/run-k6.sh
//
// Variáveis de ambiente:
//   BASE_URL  (default http://host.docker.internal:8080)
//   VUS       usuários virtuais (default 50)
//   REQUESTS  requisições por cenário (default 500)
//
// IMPORTANTE: a aplicação alvo deve estar rodando com SMTP mockado
// (profile/config sem credenciais reais) para não enviar e-mails de verdade.

import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const VUS = parseInt(__ENV.VUS || '50');
const REQUESTS = parseInt(__ENV.REQUESTS || '500');

const syncLatency = new Trend('sync_latency_ms', true);
const asyncLatency = new Trend('async_latency_ms', true);
const syncErrors = new Counter('sync_errors');
const asyncErrors = new Counter('async_errors');

export const options = {
  scenarios: {
    sync: {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: REQUESTS,
      exec: 'syncScenario',
      startTime: '0s',
    },
    async: {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: REQUESTS,
      exec: 'asyncScenario',
      startTime: '30s', // roda depois do sync
    },
  },
};

function payload(i) {
  return JSON.stringify({
    ownerRef: `user-${i}`,
    emailFrom: 'no-reply@exemplo.com',
    emailTo: `destino${i}@exemplo.com`,
    subject: `k6 load ${i}`,
    text: `Corpo do e-mail de teste ${i}`,
  });
}

const params = { headers: { 'Content-Type': 'application/json' } };

export function syncScenario() {
  const res = http.post(`${BASE_URL}/sending-email`, payload(__ITER), params);
  syncLatency.add(res.timings.duration);
  const ok = check(res, { 'sync status 201': (r) => r.status === 201 });
  if (!ok) syncErrors.add(1);
}

export function asyncScenario() {
  const res = http.post(`${BASE_URL}/sending-email/async`, payload(__ITER), params);
  asyncLatency.add(res.timings.duration);
  const ok = check(res, { 'async status 202': (r) => r.status === 202 });
  if (!ok) asyncErrors.add(1);
}
