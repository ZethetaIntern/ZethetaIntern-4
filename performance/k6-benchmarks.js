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

// B3 "payment initiation": API request -> gateway call initiated, reported by the server in Server-Timing.
const initiation = new Trend('payment_initiation_ms', true);
const paymentTotal = new Trend('payment_end_to_end_ms', true);   // informational: auth + capture round trip
const replayHttp = new Trend('idempotency_replay_http_ms', true); // informational: includes network
const webhookLatency = new Trend('webhook_processing_ms', true);
const replayLatency = new Trend('idempotency_replay_ms', true);
const failoverLatency = new Trend('failover_total_ms', true);
const failures = new Rate('payment_failures');

const all = {
  load: {
    executor: 'constant-arrival-rate', exec: 'load', rate: 100, timeUnit: '1s',
    duration: '5m', preAllocatedVUs: 200, maxVUs: 600,
  },
  // The remaining scenarios run after the load test, one at a time (B4.2 runs phases sequentially).
  webhooks: {
    executor: 'constant-arrival-rate', exec: 'webhooks', rate: 20, timeUnit: '1s',
    duration: '1m', preAllocatedVUs: 50, startTime: '5m15s',
  },
  idempotency: {
    executor: 'per-vu-iterations', exec: 'idempotency', vus: 5, iterations: 50, startTime: '6m25s',
  },
  failover: {
    executor: 'per-vu-iterations', exec: 'failover', vus: 2, iterations: 20, startTime: '6m55s',
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

// Server-Timing: "gateway_initiated;dur=12.3, app;dur=45.6" -> { gateway_initiated: 12.3, app: 45.6 }
function serverTiming(res) {
  const out = {};
  (res.headers['Server-Timing'] || '').split(',').forEach((part) => {
    const m = part.trim().match(/^([a-z_]+);dur=([0-9.]+)$/);
    if (m) out[m[1]] = parseFloat(m[2]);
  });
  return out;
}

function payment(method = 'CARD', amount = 120000) {
  return JSON.stringify({
    merchant_order_id: `K6-${uuidv4().slice(0, 8)}`, amount_paise: amount, currency: 'INR', payment_method: method,
  });
}

export function load() {
  const method = Math.random() < 0.4 ? 'UPI' : 'CARD';
  const res = http.post(`${BASE}/api/v1/payments`, payment(method), { headers: headers() });
  const t = serverTiming(res);
  if (t.gateway_initiated !== undefined) initiation.add(t.gateway_initiated);
  paymentTotal.add(res.timings.duration);
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
  const wt = serverTiming(res);
  webhookLatency.add(wt.app !== undefined ? wt.app : res.timings.duration); // receipt -> transition committed
  check(res, { 'webhook accepted': (r) => r.status === 200 });
}

export function idempotency() {
  const h = headers();
  const body = payment('UPI', 10000);
  http.post(`${BASE}/api/v1/payments`, body, { headers: h });
  for (let i = 0; i < 5; i++) {
    const res = http.post(`${BASE}/api/v1/payments`, body, { headers: h });
    const rt = serverTiming(res);
    replayLatency.add(rt.app !== undefined ? rt.app : res.timings.duration); // server-side detection + cached reply
    replayHttp.add(res.timings.duration);
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
