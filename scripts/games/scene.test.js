'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const H = require('./harness');
const source = fs.readFileSync(path.join(H.HUB,'engine','game.js'),'utf8');

function harness(reduced = false) {
  let now = 0, id = 0;
  const queue = new Map(), observers = [], errors = [], handlers = {}, changes = [];
  function events(target) {
    target.listeners = {};
    target.addEventListener = (name, fn) => (target.listeners[name] ||= new Set()).add(fn);
    target.removeEventListener = (name, fn) => target.listeners[name]?.delete(fn);
    target.emit = name => [...(target.listeners[name] || [])].forEach(fn => fn());
    return target;
  }
  const context = {save(){},restore(){},setTransform(){},clearRect(){}};
  const bounds = {width:344,height:213.28};
  function node(tag) {
    return {tag,style:{},dataset:{},isConnected:true,children:[],attrs:{},
      setAttribute(k,v){this.attrs[k]=v;},appendChild(n){this.children.push(n);},remove(){this.isConnected=false;},
      getContext(){return context;},getBoundingClientRect(){return bounds;}};
  }
  const document = events({hidden:false,createElement:node});
  const media = events({matches:reduced});
  const window = events({devicePixelRatio:3,matchMedia:()=>media});
  const game = {config:{'render.fps':24},on(name,fn){handlers[name]=fn;return()=>delete handlers[name];}};
  const sandbox = {window,document,console:{error(...args){errors.push(args);}},
    requestAnimationFrame(fn){queue.set(++id,fn);return id;},cancelAnimationFrame(key){queue.delete(key);},
    ResizeObserver:class{constructor(fn){this.fn=fn;}observe(){}disconnect(){}},
    IntersectionObserver:class{constructor(fn){this.fn=fn;observers.push(this);}observe(){}disconnect(){}},
    setTimeout,clearTimeout,setInterval,clearInterval};
  window.ResizeObserver=sandbox.ResizeObserver;window.IntersectionObserver=sandbox.IntersectionObserver;
  vm.runInNewContext(source,sandbox);
  const scene = window.Game.scene({parent:node('parent'),game,label:'Проверочная сцена',draw(c,m,t,fx){changes.push({m,t,fx});}});
  function advance(ms){const end=now+ms;while(now<end){now+=1000/60;const callbacks=[...queue.values()];queue.clear();callbacks.forEach(fn=>fn(now));}}
  return {scene,advance,queue,changes,document,media,window,handlers,observers,errors,bounds,game};
}

H.test('scene: bounded frames and backing pixels do not change the model',()=>{
  const h=harness(),data=Object.freeze({owned:42,active:3});h.scene.update(data);h.advance(1000);
  assert.ok(h.changes.length>=18&&h.changes.length<=26,'render rate stays within configured budget');
  assert.equal(h.changes[0].m,data);assert.deepEqual(data,{owned:42,active:3});
  assert.equal(h.scene.canvas.attrs['aria-label'],'Проверочная сцена');
  h.bounds.width=5000;h.bounds.height=3100;h.window.emit('resize');h.advance(100);
  assert.ok(h.scene.canvas.width*h.scene.canvas.height<=1403000);
  h.scene.dispose();
});
H.test('scene: app hidden, document hidden and offscreen stop all frame requests',()=>{
  const h=harness();h.scene.update({owned:1});h.advance(100);
  for(const mode of ['app','document','intersection']){
    if(mode==='app')h.handlers.hidden();
    if(mode==='document'){h.document.hidden=true;h.document.emit('visibilitychange');}
    if(mode==='intersection')h.observers[0].fn([{isIntersecting:false}]);
    const before=h.changes.length;h.scene.update({owned:2});h.scene.pulse('buy');h.advance(1000);
    assert.equal(h.changes.length,before);assert.equal(h.queue.size,0);assert.equal(h.scene.inspect().effects,0);
    if(mode==='app')h.handlers.visible();
    if(mode==='document'){h.document.hidden=false;h.document.emit('visibilitychange');}
    if(mode==='intersection')h.observers[0].fn([{isIntersecting:true}]);
    h.advance(100);assert.ok(h.changes.length>before);assert.equal(h.changes.at(-1).m.owned,2);
  }
  h.scene.dispose();
});
H.test('scene: reduced motion draws state changes once and keeps no idle loop',()=>{
  const h=harness(true);h.scene.update({owned:0});h.advance(1000);assert.equal(h.changes.length,1);assert.equal(h.queue.size,0);
  h.scene.update({owned:1});h.scene.pulse('buy',{delta:1},2);h.advance(1000);
  assert.equal(h.changes.length,2);assert.equal(h.changes.at(-1).fx[0].age,1);assert.equal(h.changes.at(-1).t,0);assert.equal(h.queue.size,0);
  h.media.matches=false;h.media.emit('change');h.advance(300);assert.ok(h.changes.length>2);
  h.media.matches=true;h.media.emit('change');h.advance(1000);const before=h.changes.length;h.advance(1000);assert.equal(h.changes.length,before);
  h.scene.dispose();
});
H.test('scene: mass purchases coalesce, expire and do not replay after hiding',()=>{
  const h=harness();h.scene.update({owned:10000});
  for(let i=0;i<100;i++)h.scene.pulse('buy',{delta:i},.15);
  assert.equal(h.scene.inspect().effects,1);
  for(let i=0;i<10;i++)h.scene.pulse('event-'+i,{},.15);
  assert.equal(h.scene.inspect().effects,4);h.advance(300);assert.equal(h.scene.inspect().effects,0);
  h.scene.pulse('prestige');h.handlers.hidden();h.handlers.visible();h.advance(100);assert.equal(h.scene.inspect().effects,0);
  h.scene.dispose();h.advance(100);assert.equal(h.queue.size,0);assert.equal(Object.keys(h.handlers).length,0);
});
H.done('Game.scene lifecycle');
