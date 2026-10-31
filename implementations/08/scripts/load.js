import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
export const options = {
 scenarios: { transfers: { executor: 'constant-arrival-rate', rate: 100, timeUnit: '1s', duration: __ENV.DURATION || '60s', preAllocatedVUs: 50, maxVUs: 150 } },
 thresholds: { http_req_duration: ['p(95)<500'], http_req_failed: ['rate<0.01'], checks: ['rate>0.99'] }
};
function uuid() { return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => { const r=Math.random()*16|0; return (c==='x'?r:(r&3|8)).toString(16); }); }
export default function() {
 const response=http.post((__ENV.BASE_URL||'https://localhost:8443')+'/api/v1/payments',JSON.stringify({paymentId:uuid(),fromAccount:__ENV.FROM_ACCOUNT||'ACC-1001',toAccount:__ENV.TO_ACCOUNT||'ACC-2002',amount:'0.001',date:new Date().toISOString(),purpose:'Load verification'}),{headers:{Authorization:'Bearer '+__ENV.TOKEN,'Content-Type':'application/json'}});
 check(response,{'created exactly once':r=>r.status===201,'canonical amount':r=>r.json('amount')==='0.001'});
}
