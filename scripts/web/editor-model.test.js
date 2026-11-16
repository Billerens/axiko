const test = require('node:test');
const assert = require('node:assert/strict');
const Model = require('../../app/src/main/assets/factory/ui/desk/editor-model.js');
const later = () => { let resolve, reject; const promise = new Promise((a,b)=>{resolve=a;reject=b;}); return {promise,resolve,reject}; };
const tick = () => new Promise(resolve => setImmediate(resolve));
const entry = (path,text,revision) => ({path,text,revision,size:text.length});
const error = code => Object.assign(new Error(code),{code});

test('out-of-order file reads retain each file identity and buffer', async () => {
  const a=later(), b=later();
  const store=Model.create(req=>req.path==='a.js'?a.promise:b.promise);
  const pa=store.open('a.js'), pb=store.open('b.js');
  b.resolve(entry('b.js','contents B','b1')); await pb;
  a.resolve(entry('a.js','contents A','a1')); await pa;
  assert.equal(store.docs.get('a.js').text,'contents A');
  assert.equal(store.docs.get('b.js').text,'contents B');
});

test('typing during save remains dirty and is never reported as saved', async () => {
  const write=later(); let submitted;
  const store=Model.create(req=>{
    if(req.op==='desk.read') return Promise.resolve(entry(req.path,'original','r1'));
    submitted=req; return write.promise;
  });
  const doc=await store.open('a.js');
  store.update(doc,'first edit');
  const saving=store.save(doc); await tick();
  store.update(doc,'newer edit');
  write.resolve(entry('a.js','first edit','r2')); await saving;
  assert.equal(submitted.text,'first edit');
  assert.equal(submitted.expectedRevision,'r1');
  assert.equal(submitted.draft,null,'live documents must not inherit a preview Referer');
  assert.equal(doc.saved,'first edit');
  assert.equal(doc.text,'newer edit');
  assert.equal(doc.revision,'r2');
  assert.equal(store.dirty(doc),true);
});

test('an explicit second save waits and uses the first successful revision', async () => {
  const writes=[]; const gates=[later(),later()];
  const store=Model.create(req=>{
    if(req.op==='desk.read') return Promise.resolve(entry(req.path,'zero','r0'));
    writes.push(req); return gates[writes.length-1].promise;
  });
  const doc=await store.open('a.js');
  store.update(doc,'one'); const saving=store.save(doc); await tick();
  store.update(doc,'two'); const queued=store.save(doc);
  assert.equal(saving,queued); assert.equal(writes.length,1);
  gates[0].resolve(entry('a.js','one','r1')); await tick();
  assert.equal(writes.length,2); assert.equal(writes[1].expectedRevision,'r1');
  assert.equal(writes[1].text,'two');
  gates[1].resolve(entry('a.js','two','r2')); await saving;
  assert.equal(store.dirty(doc),false);
});

test('conflicts preserve local edits until a remote revision is explicitly adopted', async () => {
  let fail=true;
  const store=Model.create(req=>{
    if(req.op==='desk.read') return Promise.resolve(entry(req.path,'old','r1'));
    return fail?Promise.reject(error('conflict')):Promise.resolve(entry(req.path,req.text,'r3'));
  });
  const doc=await store.open('a.js'); store.update(doc,'mine');
  await assert.rejects(store.save(doc),{code:'conflict'});
  assert.equal(doc.text,'mine'); assert.equal(doc.saved,'old'); assert.equal(doc.revision,'r1');
  assert.equal(doc.pending,null); assert.equal(doc.saving,false);
  store.adopt(doc,entry('a.js','theirs','r2'),true);
  assert.equal(doc.text,'mine'); assert.equal(doc.saved,'theirs'); assert.equal(doc.revision,'r2');
  fail=false; await store.save(doc);
  assert.equal(doc.saved,'mine');
});

test('synchronous transport failure does not trap later saves in a rejected promise', async () => {
  let fail=true;
  const store=Model.create(req=>{
    if(req.op==='desk.read') return Promise.resolve(entry(req.path,'old','r1'));
    if(fail) throw error('offline');
    return Promise.resolve(entry(req.path,req.text,'r2'));
  });
  const doc=await store.open('a.js'); store.update(doc,'edit');
  await assert.rejects(store.save(doc),{code:'offline'});
  assert.equal(doc.pending,null);
  fail=false; await store.save(doc); assert.equal(doc.saved,'edit');
});

test('a new file is created only by an explicit save with expectedRevision null', async () => {
  const calls=[];
  const store=Model.create(req=>{
    calls.push(req);
    if(req.op==='desk.read') return Promise.reject(error('not_found'));
    return Promise.resolve(entry(req.path,req.text,'created'));
  });
  const doc=await store.open('modules/new/main.js',{create:true});
  assert.equal(doc.isNew,true); assert.equal(calls.length,1);
  store.update(doc,'exports.ready=true;'); await store.save(doc);
  assert.equal(calls[1].expectedRevision,null);
  assert.equal(doc.isNew,false);
});

test('draft file reads and writes keep their explicit draft scope', async () => {
  const calls=[];
  const store=Model.create(req=>{
    calls.push(req);
    return Promise.resolve(entry(req.path,req.op==='desk.read'?'old':req.text,req.op==='desk.read'?'r1':'r2'));
  });
  const doc=await store.open('~d123/ui/a.html'); store.update(doc,'draft');
  await store.save(doc);
  assert.equal(doc.path,'ui/a.html');
  assert.equal(calls[0].draft,'d123'); assert.equal(calls[1].draft,'d123');
  assert.equal(doc.key,'~d123/ui/a.html');
});

test('closing a loading document prevents its delayed reply from modifying a reopened tab', async () => {
  const old=later(); let n=0;
  const store=Model.create(req=>++n===1?old.promise:Promise.resolve(entry(req.path,'fresh','new')));
  const pending=store.open('a.js');
  store.close(store.docs.get('a.js'));
  const reopened=await store.open('a.js');
  old.resolve(entry('a.js','stale','old')); await pending;
  assert.equal(reopened.text,'fresh');
  assert.equal(store.docs.get('a.js'),reopened);
});


