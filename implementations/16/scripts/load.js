import http from 'k6/http';
import { check } from 'k6';
export const options = { scenarios: { payments: { executor: 'constant-arrival-rate', rate: 100, timeUnit: '1s', duration: '60s', preAllocatedVUs: 30, maxVUs: 100 } }, thresholds: { http_req_duration: ['p(95)<500'], checks: ['rate>0.99'] } };
export default function () {
 const hex = () => Math.floor(Math.random()*0x10000).toString(16).padStart(4,'0');
 const id = `${hex()}${hex()}-${hex()}-4${hex().slice(1)}-a${hex().slice(1)}-${hex()}${hex()}${hex()}`;
 const body = { paymentId: id, fromAccount: __ENV.FROM_ACCOUNT || 'ACC-1001', toAccount: __ENV.TO_ACCOUNT || 'ACC-2002', amount: '0.001', date: new Date().toISOString(), purpose: 'Reference load test' };
 const r = http.post(`${__ENV.BASE_URL || 'http://localhost:8080'}/api/v1/payments`, JSON.stringify(body), { headers: { Authorization: `Bearer ${__ENV.TOKEN}`, 'Content-Type': 'application/json' } });
 check(r, { 'committed': x => x.status === 201 });
}
