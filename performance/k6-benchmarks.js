// B3 performance benchmarks. Run against `docker compose up`:
//   k6 run performance/k6-benchmarks.js
//   k6 run -e BASE_URL=http://localhost:8080 -e SCENARIO=load performance/k6-benchmarks.js
//
// Scenarios (all run by default):
//   load        100 payments/s for 5 minutes (constant-arrival-rate)   -> P95 initiation, error rate
//   webhooks    signed webhooks against captured payments              -> P95 webhook processing < 200 ms
//   idempotency replays of a completed request                         -> P95 < 10 ms
//   failover    primary gateway forced to time out                      -> end-to-end < 2 s after detection
import http from 'k6/http';
import { check } from 'k6';
import { Trend, Rate } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const KEY = __ENV.API_KEY || 'pk_test_payflow';
const ONLY = __ENV.SCENARIO;

const initiation = new Trend('payment_initiation_ms', true);
const webhookLatency = new Trend('webhook_processing_ms', true);
const replayLatency = new Trend('idempotency_replay_ms', true);
const failoverLatency = new Trend('failover_total_ms', true);
const failures = new Rate('payment_failures');

const all = {
  load: {
    executor: 'constant-arrival-rate', exec: 'load', rate: 100, timeUnit: '1s',
    duration: '5m', preAllocatedVUs: 200, maxVUs: 600,
  },
  webhooks: {
    executor: 'constant-arrival-rate', exec: 'webhooks', rate: 20, timeUnit: '1s',
    duration: '1m', preAllocatedVUs: 50, startTime: '10s',
  },
  idempotency: {
    executor: 'per-vu-iterations', exec: 'idempotency', vus: 5, iterations: 50, startTime: '5s',
  },
  failover: {
    executor: 'per-vu-iterations', exec: 'failover', vus: 2, iterations: 20, startTime: '15s',
  },
};

export const options = {
  scenarios: ONLY ? { [ONLY]: all[ONLY] } : all,
  thresholds: {
    payment_initiation_ms: ['p(95)<500'],
    webhook_processing_ms: ['p(95)<200'],
    idempotency_replay_ms: ['p(95)<10'],
    failover_total_ms: ['p(95)<3000'], // 1 s attempt budget + alternate gateway (< 2 s after detection)
    payment_failures: ['rate<0.01'],
  },
};

const headers = (extra = {}) => Object.assign({
  'Content-Type': 'application/json', 'X-API-Key': KEY, 'Idempotency-Key': uuidv4(),
}, extra);

function payment(method = 'CARD', amount = 120000) {
  return JSON.stringify({
    merchant_order_id: `K6-${uuidv4().slice(0, 8)}`, amount_paise: amount, currency: 'INR', payment_method: method,
  });
}

export function load() {
  const method = Math.random() < 0.4 ? 'UPI' : 'CARD';
  const res = http.post(`${BASE}/api/v1/payments`, payment(method), { headers: headers() });
  initiation.add(res.timings.duration);
  failures.add(res.status !== 201);
  check(res, { 'payment created': (r) => r.status === 201 });
}

export function webhooks() {
  const pay = http.post(`${BASE}/api/v1/payments`, JSON.stringify({
    merchant_order_id: `K6-WH-${uuidv4().slice(0, 8)}`, amount_paise: 50000, currency: 'INR',
    payment_method: 'CARD', capture_mode: 'MANUAL',
  }), { headers: headers() }).json();
  const body = JSON.stringify({
    event_id: `evt_${uuidv4()}`, status: 'captured', transaction_id: pay.id,
    gateway_reference: pay.gateway_reference, amount: 50000, currency: 'INR',
  });
  const sig = http.post(`${BASE}/api/v1/mock/webhooks/${pay.gateway}/sign`, body,
    { headers: { 'Content-Type': 'application/json', 'X-API-Key': KEY } }).json();
  const res = http.post(`${BASE}/api/v1/webhooks/${pay.gateway}`, body,
    { headers: Object.assign({ 'Content-Type': 'application/json' }, sig) });
  webhookLatency.add(res.timings.duration);
  check(res, { 'webhook accepted': (r) => r.status === 200 });
}

export function idempotency() {
  const h = headers();
  const body = payment('UPI', 10000);
  http.post(`${BASE}/api/v1/payments`, body, { headers: h });
  for (let i = 0; i < 5; i++) {
    const res = http.post(`${BASE}/api/v1/payments`, body, { headers: h });
    replayLatency.add(res.timings.duration);
    check(res, { replayed: (r) => r.headers['Idempotent-Replayed'] === 'true' });
  }
}

export function failover() {
  const preview = http.get(`${BASE}/api/v1/routing/preview?payment_method=CARD&amount_paise=120000`,
    { headers: { 'X-API-Key': KEY } }).json();
  const primary = preview.ranked[0].gateway;
  const res = http.post(`${BASE}/api/v1/payments`, payment('CARD'),
    { headers: headers({ 'X-Mock-Response': `${primary}=timeout` }) });
  failoverLatency.add(res.timings.duration);
  check(res, {
    'failed over': (r) => r.status === 201 && r.json('gateway') !== primary,
  });
}
