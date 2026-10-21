import {spawn} from 'child_process'; import fs from 'fs';
const [,,url,jsfile]=process.argv; const UA=fs.readFileSync('UA.js','utf8');
const p=spawn('google-chrome',['--headless=new','--no-sandbox','--remote-debugging-port=9333','--user-agent='+UA,'about:blank']);
await new Promise(r=>setTimeout(r,2500));
const t=await (await fetch('http://127.0.0.1:9333/json/new?'+encodeURIComponent(url),{method:'PUT'})).json();
const ws=new WebSocket(t.webSocketDebuggerUrl); await new Promise(r=>ws.onopen=r);
await new Promise(r=>setTimeout(r,7000));
ws.onmessage=e=>{const m=JSON.parse(e.data); if(m.id==1){console.log(JSON.stringify(m.result.result.value||m.result).slice(0,1500)); p.kill(); process.exit(0)}};
ws.send(JSON.stringify({id:1,method:'Runtime.evaluate',params:{expression:'location.href+" || "+'+fs.readFileSync(jsfile,'utf8')}}));
