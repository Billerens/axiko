const http=require('node:http'),fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const base=path.resolve(__dirname,'../../app/src/main/assets/factory');
const setup="\nwindow.reads=[];window.writes=[];window.notes=[];\nwindow.mockRpc=req=>{\n if(req.op==='eval')return Promise.resolve(['a.js','b.js','fresh.js','slow.js']);\n return new Promise((resolve,reject)=>(req.op==='desk.read'?reads:writes).push({req,resolve,reject}));\n};\nwindow.editor=AxikoEditor.mount(document.getElementById('editor'),{rpc:mockRpc,open:p=>window.opened=p,toast:m=>notes.push(m)});\nwindow.resolveRead=(name,text,revision)=>{const item=reads.find(x=>x.req.path===name&&!x.done);item.done=true;item.resolve({path:name,text,revision,size:text.length});};\nwindow.rejectRead=(name)=>{const item=reads.find(x=>x.req.path===name&&!x.done);item.done=true;item.reject(Object.assign(new Error('Файл не найден'),{code:'not_found'}));};\n";
const server=http.createServer((req,res)=>{
 if(req.url==='/'){
  res.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});
  res.end('<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width"><link rel=stylesheet href="/ui/kit/kit.css"><link rel=stylesheet href="/ui/desk/editor.css"><style>body{margin:0}#editor{height:100vh;min-width:0}</style><div id=editor></div><script src="/ui/desk/editor-model.js"></script><script src="/ui/desk/editor.js"></script><script>'+setup+'</script>');return;
 }
 const file=path.join(base,req.url);
 if(fs.existsSync(file)){res.writeHead(200,{'Content-Type':req.url.endsWith('.js')?'text/javascript':'text/css'});res.end(fs.readFileSync(file));}
 else{res.writeHead(404);res.end();}
});
(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const browser=await chromium.launch({headless:true,executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined});
 try{
  for(const width of [1440,390]){
   const page=await browser.newPage({viewport:{width,height:900}});
   const errors=[];page.on('pageerror',e=>errors.push(e.message));page.on('dialog',d=>d.accept());
   await page.goto('http://127.0.0.1:'+server.address().port+'/');
   await page.evaluate(()=>{editor.openFile('a.js');editor.openFile('b.js');});
   await page.evaluate(()=>resolveRead('b.js','contents B','b1'));
   await page.waitForFunction(()=>document.querySelector('.ae-code').value==='contents B');
   await page.evaluate(()=>resolveRead('a.js','contents A','a1'));
   assert.equal(await page.locator('.ae-code').inputValue(),'contents B');
   await page.locator('.ae-code').fill('first B edit');
   await page.locator('.ae-code').press('Control+s');
   await page.waitForFunction(()=>writes.length===1);
   await page.locator('.ae-code').fill('newer B edit');
   await page.evaluate(()=>writes[0].resolve({path:'b.js',text:'first B edit',revision:'b2'}));
   await page.waitForFunction(()=>!document.querySelector('.ae-toolbar button.ae-primary').disabled);
   assert.equal(await page.locator('.ae-code').inputValue(),'newer B edit');
   assert.equal(await page.locator('.ae-state').innerText(),'Есть изменения');
   assert.equal(await page.evaluate(()=>writes[0].req.path),'b.js');
   await page.getByRole('tab',{name:/^a\.js/}).click();
   assert.equal(await page.locator('.ae-code').inputValue(),'contents A');
   await page.getByRole('tab',{name:/^b\.js/}).click();
   assert.equal(await page.locator('.ae-code').inputValue(),'newer B edit');
   await page.evaluate(()=>{editor.openFile('fresh.js',{create:true});});
   await page.evaluate(()=>rejectRead('fresh.js'));
   await page.waitForFunction(()=>!document.querySelector('.ae-code').disabled);
   await page.locator('.ae-code').fill('new file text');
   await page.locator('.ae-code').press('Control+s');
   await page.waitForFunction(()=>writes.length===2);
   assert.equal(await page.evaluate(()=>writes[1].req.expectedRevision),null);
   await page.evaluate(()=>writes[1].resolve({path:'fresh.js',text:'new file text',revision:'f1'}));
   await page.waitForFunction(()=>document.querySelector('.ae-state').textContent==='Сохранено');
   assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
   await page.locator('.ae-code').press('Shift+Tab');
   assert.equal(await page.evaluate(()=>document.activeElement.classList.contains('ae-code')),false,'Shift+Tab must leave the editor');
   assert.deepEqual(errors,[]);
   console.log(JSON.stringify({width,outOfOrderReads:true,typingDuringSave:true,tabsRetainBuffers:true,newFileNullRevision:true,overflow:false,pageErrors:errors}));
   await page.evaluate(()=>{
    localStorage.setItem('axiko.desk.buffer:slow.js',JSON.stringify({text:'UNSAVED RECOVERY',revision:'s1'}));
    editor.openFile('slow.js');
   });
   await page.getByRole('button',{name:'Закрыть slow.js',exact:true}).click();
   const retained=await page.evaluate(()=>!!localStorage.getItem('axiko.desk.buffer:slow.js'));
   assert.equal(retained,true,'closing a loading tab must preserve its unread recovery text');
   await page.evaluate(()=>resolveRead('slow.js','disk text','s1'));
   await page.waitForTimeout(250);
   assert.equal(await page.getByRole('tab',{name:/^slow\.js/}).count(),0);
   assert.equal(await page.evaluate(()=>!!localStorage.getItem('axiko.desk.buffer:slow.js')),true);
   console.log(JSON.stringify({reviewCase:'closeLoadingTabRecovery',width,recoveryPreserved:retained}));
   await page.close();
  }
 }finally{await browser.close();server.closeAllConnections();await new Promise(r=>server.close(r));}
})().catch(e=>{console.error(e);process.exitCode=1;server.closeAllConnections();server.close();});




