// Run against isolated funded accounts; never run against production money.
import http from 'k6/http';
import { check } from 'k6';
export const options={scenarios:{payments:{executor:'constant-arrival-rate',rate:100,timeUnit:'1s',duration:'60s',preAllocatedVUs:50,maxVUs:100}},thresholds:{http_req_duration:['p(95)<500'],http_req_failed:['rate<0.01'],checks:['rate>0.99']}};
function uuid(){return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g,c=>{const r=Math.floor(Math.random()*16);return(c==='x'?r:((r&3)|8)).toString(16);});}
export default function(){const response=http.post(`${__ENV.BASE_URL||'https://localhost:8443'}/api/v1/payments`,JSON.stringify({paymentId:uuid(),fromAccount:__ENV.FROM||'ACC-1001',toAccount:__ENV.TO||'ACC-2002',amount:'0.001',date:new Date().toISOString(),purpose:'Isolated load test'}),{headers:{Authorization:`Bearer ${__ENV.TOKEN}`,'Content-Type':'application/json'}});check(response,{'completed':r=>r.status===201});}
