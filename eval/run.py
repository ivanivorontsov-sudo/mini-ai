import sys, json
from llama_cpp import Llama
m=Llama(model_path=sys.argv[1], n_ctx=2048, n_threads=4, verbose=False)
prof={'normal':(open('normal.txt').read(),0.7,0.95,1.0),'honest':(open('honest.txt').read(),0.3,0.9,1.1)}
out=[]
for q in open('questions.txt').read().strip().split('\n'):
  r={'q':q}
  for k,(sp,t,p,rp) in prof.items():
    a=m.create_chat_completion([{'role':'system','content':sp},{'role':'user','content':q}],temperature=t,top_p=p,repeat_penalty=rp,max_tokens=220,seed=1)
    r[k]=a['choices'][0]['message']['content'].strip()
  out.append(r); print(json.dumps(r,ensure_ascii=False),flush=True)
