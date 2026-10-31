import http from 'k6/http';
import { check } from 'k6';
export const options={scenarios:{payments:{executor:'constant-arrival-rate',rate:100,timeUnit:'1s',duration:'60s',preAllocatedVUs:30,maxVUs:200}},thresholds:{http_req_duration:['p(95)<500'],http_req_failed:['rate<0.01']}};
export default function(){
 const id='xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g,c=>{const r=Math.random()*16|0;return(c==='x'?r:(r&3|8)).toString(16)});
 const r=http.post(`${__ENV.BASE_URL||'https://localhost:8443'}/api/v1/payments`,JSON.stringify({paymentId:id,fromAccount:__ENV.FROM_ACCOUNT||'ACC-1001',toAccount:__ENV.TO_ACCOUNT||'ACC-2002',amount:'0.001',date:new Date().toISOString(),purpose:'load measurement'}),{headers:{Authorization:`Bearer ${__ENV.TOKEN}`,'Content-Type':'application/json'}});
 check(r,{'created':res=>res.status===201});
}
