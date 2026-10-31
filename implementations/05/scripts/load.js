// k6 run -e TOKEN=... -e BASE_URL=https://... scripts/load.js
import http from 'k6/http';
import {check} from 'k6';
export const options={scenarios:{payments:{executor:'constant-arrival-rate',rate:100,timeUnit:'1s',duration:'60s',preAllocatedVUs:30,maxVUs:100}},thresholds:{http_req_duration:['p(95)<500'],http_req_failed:['rate<0.01']}};
function uuid(){return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g,c=>{const n=Math.random()*16|0;return(c==='x'?n:(n&3|8)).toString(16);});}
export default function(){const reverse=Math.random()<0.5;const from=reverse?'ACC-2002':'ACC-1001',to=reverse?'ACC-1001':'ACC-2002';const body={paymentId:uuid(),fromAccount:from,toAccount:to,amount:'0.001',date:new Date().toISOString(),purpose:'Reference load measurement'};const response=http.post(`${__ENV.BASE_URL||'http://localhost:8080'}/api/v1/payments`,JSON.stringify(body),{headers:{Authorization:`Bearer ${__ENV.TOKEN}`,'Content-Type':'application/json'}});check(response,{'completed':r=>r.status===201});}
