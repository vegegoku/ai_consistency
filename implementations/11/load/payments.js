import http from 'k6/http';
import { check } from 'k6';
import { randomUUID } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
export const options = { scenarios: { payments: {executor:'constant-arrival-rate', rate:100, timeUnit:'1s', duration:'30s', preAllocatedVUs:50, maxVUs:150}}, thresholds:{http_req_duration:['p(95)<500'],http_req_failed:['rate<0.001'],checks:['rate>0.999']}};
export default function () {
  const response=http.post(`${__ENV.BASE_URL||'http://localhost:8080'}/api/v1/payments`,JSON.stringify({paymentId:randomUUID(),fromAccount:'ACC-1001',toAccount:'ACC-2002',amount:'0.001',date:new Date().toISOString(),purpose:'Reference load test'}),{headers:{Authorization:`Bearer ${__ENV.TOKEN}`,'Content-Type':'application/json'}});
  check(response,{'completed':r=>r.status===201});
}
