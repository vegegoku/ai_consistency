import http from 'k6/http';
import { check } from 'k6';
export const options = { scenarios: { steady: {executor: 'constant-arrival-rate',rate: 100,timeUnit: '1s',duration: '60s',preAllocatedVUs: 30,maxVUs: 100} },thresholds:{http_req_duration:['p(95)<500'],checks:['rate>0.99']},tlsAuth:[],insecureSkipTLSVerify:true};
export default function() {
 const n=`${__VU.toString(16).padStart(8,'0')}-0000-4000-8000-${(__ITER+Date.now()).toString(16).padStart(12,'0').slice(-12)}`;
 const r=http.post(`${__ENV.BASE_URL||'https://localhost:8443'}/api/v1/payments`,JSON.stringify({paymentId:n,fromAccount:'ACC-1001',toAccount:'ACC-2002',amount:'0.001',date:new Date().toISOString(),purpose:'Reference load test'}),{headers:{'Content-Type':'application/json',Authorization:`Bearer ${__ENV.TOKEN}`}});
 check(r,{'completed': r=>r.status===201});
}
