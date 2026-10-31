"""Regenerate the checked-in OpenAPI contract; standard-library only."""
import json
from pathlib import Path

def ref(name): return {'$ref': '#/components/schemas/' + name}
def content(name): return {m: {'schema': ref(name)} for m in ['application/json','application/xml']}
fields={'paymentId':{'type':'string','format':'uuid'},'fromAccount':{'type':'string','minLength':1,'maxLength':128},'toAccount':{'type':'string','minLength':1,'maxLength':128},'amount':{'oneOf':[{'type':'number','minimum':0,'exclusiveMinimum':True,'maximum':10000,'multipleOf':0.001},{'type':'string','pattern':r'^[0-9]+(\.[0-9]{1,3})?$'}]},'date':{'type':'string','format':'date-time'},'purpose':{'type':'string','minLength':1,'maxLength':255},'notes':{'type':'string','maxLength':1000,'nullable':True}}
example={'paymentId':'d92192df-f79a-4553-8120-5660d2eb1294','fromAccount':'ACC-1001','toAccount':'ACC-2002','amount':'125.750','date':'2026-10-28T13:30:00+03:00','purpose':'Invoice INV-2048'}
errors={400:'MALFORMED_MESSAGE or VALIDATION_FAILED',401:'UNAUTHENTICATED',403:'FORBIDDEN or TLS_REQUIRED',404:'ACCOUNT_NOT_FOUND or PAYMENT_NOT_FOUND',406:'NOT_ACCEPTABLE',409:'ACCOUNT_NOT_ACTIVE, INSUFFICIENT_FUNDS, IDEMPOTENCY_CONFLICT, PAYMENT_LIMIT_EXCEEDED',413:'PAYLOAD_TOO_LARGE',415:'UNSUPPORTED_MEDIA_TYPE',429:'RATE_LIMITED',503:'TEMPORARILY_UNAVAILABLE',500:'INTERNAL_ERROR'}
def responses(success):
    result={str(k):{'description':v,'headers':{'X-Correlation-ID':{'schema':{'type':'string','format':'uuid'}},'Retry-After':{'schema':{'type':'integer'}}},'content':content('Error')} for k,v in errors.items()}
    result.update(success)
    return result
schemas={}
schemas['Payment']={'type':'object','additionalProperties':False,'xml':{'name':'payment'},'required':[k for k in fields if k!='notes'],'properties':fields}
schemas['Receipt']={'type':'object','xml':{'name':'receipt'},'required':['paymentId','status','fromAccount','toAccount','amount','currency','processedAt'],'properties':{k:fields[k] for k in ['paymentId','fromAccount','toAccount']}}
schemas['Receipt']['properties'].update({'status':{'type':'string','enum':['COMPLETED']},'amount':{'type':'string','pattern':r'^[0-9]+\.[0-9]{3}$'},'currency':{'type':'string','enum':['JOD']},'processedAt':{'type':'string','format':'date-time'}})
schemas['Error']={'type':'object','xml':{'name':'error'},'required':['status','code','message','issues','correlationId','timestamp'],'properties':{'paymentId':fields['paymentId'],'status':{'type':'string','enum':['ERROR','REJECTED']},'code':{'type':'string'},'message':{'type':'string'},'issues':{'type':'array','items':{'type':'object','properties':{k:{'type':'string'} for k in ['field','code','message']}}},'correlationId':{'type':'string','format':'uuid'},'timestamp':{'type':'string','format':'date-time'}}}
schemas['Balance']={'type':'object','properties':{'accountId':{'type':'string'},'availableBalance':schemas['Receipt']['properties']['amount'],'currency':schemas['Receipt']['properties']['currency']}}
schemas['Reconciliation']={'type':'object','properties':{'status':{'type':'string','enum':['BALANCED','MISMATCH']},'findings':{'type':'array','items':{'type':'object'}}}}
paths={}
request_content=content('Payment');request_content['application/json']['example']=example;request_content['application/xml']['example']='<payment>'+''.join(f'<{k}>{v}</{k}>' for k,v in example.items())+'</payment>'
paths['/api/v1/payments']={'post':{'operationId':'submitPayment','description':'Requires payments:write and account debit authorization. Exact JOD decimal input. Duplicate ID and equivalent canonical payload replay original receipt or rejection. Altered payload returns 409. Only purpose and notes trimmed. Date offsets and decimal scale canonicalized. Maximum default body 64 KiB.','requestBody':{'required':True,'content':request_content},'responses':responses({'201':{'description':'First committed transfer','headers':{'Location':{'schema':{'type':'string'}}},'content':content('Receipt')},'200':{'description':'Original completed receipt replay','content':content('Receipt')}})}}
stored={m:{'schema':{'oneOf':[ref('Receipt'),ref('Error')]}} for m in ['application/json','application/xml']}
paths['/api/v1/payments/{paymentId}']={'get':{'operationId':'getPayment','description':'Requires payments:read; original requester or administrator. Returns original COMPLETED or REJECTED outcome. Hidden and unknown payments return 404.','parameters':[{'name':'paymentId','in':'path','required':True,'schema':fields['paymentId']}],'responses':responses({'200':{'description':'Stored outcome','content':stored}})}}
paths['/api/v1/accounts/{accountId}/balance']={'get':{'operationId':'getBalance','description':'Requires payments:read and account ownership/view/debit grant. Unknown and hidden accounts both return 404.','parameters':[{'name':'accountId','in':'path','required':True,'schema':{'type':'string'}}],'responses':responses({'200':{'description':'Persisted balance','content':content('Balance')}})}}
paths['/api/v1/admin/reconciliation']={'get':{'operationId':'reconcile','description':'Requires payments:admin. Consistent snapshot of ledger/account discrepancies.','responses':responses({'200':{'description':'Reconciliation snapshot','content':content('Reconciliation')}})}}
spec={'openapi':'3.0.3','info':{'title':'Payment Processing API','version':'1.0.0'},'servers':[{'url':'https://localhost:8443'}],'security':[{'bearerAuth':[]}],'paths':paths,'components':{'securitySchemes':{'bearerAuth':{'type':'http','scheme':'bearer','bearerFormat':'JWT'}},'schemas':schemas}}
target=Path('src/main/resources/META-INF/openapi.json');target.parent.mkdir(exist_ok=True);target.write_text(json.dumps(spec,indent=2)+'\n')
