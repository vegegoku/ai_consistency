import http from 'k6/http';
import {check} from 'k6';
import {randomUUID} from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
export const options={scenarios:{payments:{executor:'constant-arrival-rate',rate:100,timeUnit:'1s',duration:'60s',preAllocatedVUs:100,maxVUs:300}},thresholds:{http_req_duration:['p(95)<500'],checks:['rate>0.99']}};
export default function(){const response=http.post(`${__ENV.BASE_URL}/api/v1/payments`,JSON.stringify({paymentId:randomUUID(),fromAccount:__ENV.FROM_ACCOUNT||'ACC-1001',toAccount:__ENV.TO_ACCOUNT||'ACC-2002',amount:'0.001',date:new Date().toISOString(),purpose:'Reference load test'}),{headers:{Authorization:`Bearer ${__ENV.TOKEN}`,'Content-Type':'application/json'}});check(response,{'completed':r=>r.status===201});}
