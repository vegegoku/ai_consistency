import http from 'k6/http';
import {check} from 'k6';
import {randomUUID} from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
export const options={scenarios:{payments:{executor:'constant-arrival-rate',rate:100,timeUnit:'1s',duration:'60s',preAllocatedVUs:50,maxVUs:150}},thresholds:{http_req_duration:['p(95)<500'],http_req_failed:['rate<0.01'],checks:['rate>0.99']}};
export default function(){const source=__ENV.SOURCE||'LOAD-A';const destination=__ENV.DESTINATION||'LOAD-B';const response=http.post((__ENV.BASE_URL||'https://localhost:8443')+'/api/v1/payments',JSON.stringify({paymentId:randomUUID(),fromAccount:source,toAccount:destination,amount:'0.001',date:new Date().toISOString(),purpose:'Load test'}),{headers:{Authorization:'Bearer '+__ENV.TOKEN,'Content-Type':'application/json'}});check(response,{'committed':r=>r.status===201});}
export function handleSummary(data){return {'load-results.json':JSON.stringify(data,null,2)};}
