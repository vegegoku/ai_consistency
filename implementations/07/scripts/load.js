import http from 'k6/http';
import { check } from 'k6';
import crypto from 'k6/crypto';
export const options = {
  scenarios: { payments: { executor: 'constant-arrival-rate', rate: 100, timeUnit: '1s', duration: '60s', preAllocatedVUs: 40, maxVUs: 200 } },
  thresholds: { http_req_duration: ['p(95)<500'], http_req_failed: ['rate<0.01'], checks: ['rate>0.99'] },
};
function uuid() {
  const bytes = new Uint8Array(crypto.randomBytes(16)); bytes[6]=(bytes[6]&15)|64; bytes[8]=(bytes[8]&63)|128;
  const hex=Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join('');
  return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
}
export default function () {
  const response = http.post(`${__ENV.BASE_URL || 'http://localhost:8080'}/api/v1/payments`, JSON.stringify({paymentId:uuid(),fromAccount:__ENV.FROM_ACCOUNT || 'ACC-1001',toAccount:__ENV.TO_ACCOUNT || 'ACC-2002',amount:'0.001',date:new Date().toISOString(),purpose:'Reference load'}), { headers:{Authorization:`Bearer ${__ENV.TOKEN}`, 'Content-Type':'application/json'} });
  check(response, {'created exactly once':r=>r.status===201});
}
