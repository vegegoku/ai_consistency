import http from 'k6/http';
import {check} from 'k6';
export const options={scenarios:{payments:{executor:'constant-arrival-rate',rate:100,timeUnit:'1s',duration:'60s',preAllocatedVUs:30,maxVUs:150}},thresholds:{http_req_duration:['p(95)<500'],checks:['rate>0.99'],http_req_failed:['rate<0.01']}};
// Preseed a source with at least 10.000 JOD and raise its daily limit before this test.
export default function(){
 const id='xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g,c=>{const r=Math.floor(Math.random()*16);return(c==='x'?r:(r&3)|8).toString(16);});
 const body=JSON.stringify({paymentId:id,fromAccount:__ENV.FROM||'ACC-1001',toAccount:__ENV.TO||'ACC-2002',amount:'0.001',date:new Date().toISOString(),purpose:'load-test'});
 const r=http.post((__ENV.BASE_URL||'http://localhost:8014')+'/api/v1/payments',body,{headers:{Authorization:'Bearer '+__ENV.TOKEN,'Content-Type':'application/json'}});
 check(r,{'committed':r.status===201});
}
export function handleSummary(data){return {'load-results.json':JSON.stringify(data,null,2)};}
