import http from 'k6/http';
import { check } from 'k6';
import crypto from 'k6/crypto';
export const options = {
  scenarios: { payments: { executor: 'constant-arrival-rate', rate: 100, timeUnit: '1s', duration: '60s', preAllocatedVUs: 50, maxVUs: 150 } },
  thresholds: { http_req_duration: ['p(95)<500'], http_req_failed: ['rate<0.01'], checks: ['rate>0.99'] },
};
function uuid() {
  const bytes = new Uint8Array(crypto.randomBytes(16)); bytes[6] = (bytes[6] & 15) | 64; bytes[8] = (bytes[8] & 63) | 128;
  const h = Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0,8)}-${h.slice(8,12)}-${h.slice(12,16)}-${h.slice(16,20)}-${h.slice(20)}`;
}
export default function () {
  const payload = { paymentId: uuid(), fromAccount: __ENV.FROM || 'ACC-1001', toAccount: __ENV.TO || 'ACC-2002', amount: '0.001', date: new Date().toISOString(), purpose: 'Reference load measurement' };
  const r = http.post(`${__ENV.BASE_URL || 'https://localhost:8443'}/api/v1/payments`, JSON.stringify(payload), { headers: { Authorization: `Bearer ${__ENV.TOKEN}`, 'Content-Type': 'application/json' } });
  check(r, { completed: r => r.status === 201 && r.json('status') === 'COMPLETED' });
}
