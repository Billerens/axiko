/* Standalone art-direction studies. Canvas coordinates: 1000 × 620. */
(function () {
  'use strict';
  const TAU = Math.PI * 2;
  const clamp = (n, a, b) => Math.max(a, Math.min(b, n));
  const seed = n => { const v = Math.sin(n * 127.1 + 19.73) * 43758.5453; return v - Math.floor(v); };
  const mix = (a, b, n) => a + (b - a) * n;
  const pulse = s => s.elapsed >= 0 && s.elapsed < 3 ? Math.sin(Math.PI * s.elapsed / 3) : 0;
  const count = s => Math.max(0, Number(s.count) || 0);
  function poly(c, pts, fill, stroke, width) {
    c.beginPath(); pts.forEach((p, i) => i ? c.lineTo(p[0], p[1]) : c.moveTo(p[0], p[1])); c.closePath();
    if (fill) { c.fillStyle = fill; c.fill(); }
    if (stroke) { c.strokeStyle = stroke; c.lineWidth = width || 1; c.stroke(); }
  }
  function ellipse(c, x, y, rx, ry, fill, stroke, width, angle) {
    c.beginPath(); c.ellipse(x, y, rx, ry, angle || 0, 0, TAU);
    if (fill) { c.fillStyle = fill; c.fill(); }
    if (stroke) { c.strokeStyle = stroke; c.lineWidth = width || 1; c.stroke(); }
  }
  function rect(c, x, y, w, h, fill, radius, stroke) {
    c.beginPath(); c.roundRect(x, y, w, h, radius || 0);
    if (fill) { c.fillStyle = fill; c.fill(); }
    if (stroke) { c.strokeStyle = stroke; c.lineWidth = 1; c.stroke(); }
  }
  function line(c, pts, color, width, dash) {
    c.beginPath(); pts.forEach((p, i) => i ? c.lineTo(p[0], p[1]) : c.moveTo(p[0], p[1]));
    c.strokeStyle = color; c.lineWidth = width || 1; c.lineJoin = c.lineCap = 'round';
    if (dash) c.setLineDash(dash); c.stroke(); if (dash) c.setLineDash([]);
  }
  function text(c, label, x, y, size, color, align, weight) {
    c.font = (weight || 500) + ' ' + size + 'px "Segoe UI", sans-serif';
    c.textAlign = align || 'left'; c.textBaseline = 'middle'; c.fillStyle = color; c.fillText(label, x, y);
  }
  function grad(c, x, y, x2, y2, stops) {
    const g = c.createLinearGradient(x, y, x2, y2); stops.forEach(s => g.addColorStop(s[0], s[1])); return g;
  }
  function glow(c, x, y, radius, color) {
    const g = c.createRadialGradient(x, y, 0, x, y, radius); g.addColorStop(0, color); g.addColorStop(1, 'transparent');
    c.fillStyle = g; c.fillRect(x - radius, y - radius, radius * 2, radius * 2);
  }
  function stars(c, t, amount, color) {
    for (let i = 0; i < amount; i++) {
      c.globalAlpha = .2 + seed(i + 72) * .45 + Math.sin(t * .45 + i) * .12;
      ellipse(c, seed(i + 1) * 1000, seed(i + 331) * 620, .5 + seed(i + 711) * 1.1, .5 + seed(i + 711) * 1.1, color);
    }
    c.globalAlpha = 1;
  }
  function spark(c, x, y, r, color) {
    line(c, [[x-r, y], [x+r, y]], color, 1.2); line(c, [[x, y-r], [x, y+r]], color, 1.2);
  }
  function burst(c, x, y, s, color, radius) {
    if (s.elapsed < 0 || s.elapsed > 2.8) return;
    const p = s.elapsed / 2.8; c.save(); c.globalAlpha = (1 - p) * .8;
    ellipse(c, x, y, 8 + p * radius, 8 + p * radius * .7, null, color, 2 - p);
    for (let i = 0; i < 18; i++) {
      const a = i / 18 * TAU + seed(i + 4) * .3, d = (20 + p * radius) * (.55 + seed(i) * .45);
      spark(c, x + Math.cos(a) * d, y + Math.sin(a) * d * .7, 1 + (1-p)*3, color);
    }
    c.restore();
  }
  function tree(c, x, y, scale, dark, light) {
    line(c, [[x,y],[x,y-19*scale]], dark, 2*scale);
    ellipse(c,x,y-23*scale,10*scale,16*scale,light); ellipse(c,x-5*scale,y-19*scale,6*scale,10*scale,dark);
  }
  function cube(c, x, y, w, d, h, top, left, right) {
    poly(c, [[x,y-h],[x+w,y+d-h],[x,y+2*d-h],[x-w,y+d-h]], top);
    poly(c, [[x-w,y+d-h],[x,y+2*d-h],[x,y+2*d],[x-w,y+d]], left);
    poly(c, [[x,y+2*d-h],[x+w,y+d-h],[x+w,y+d],[x,y+2*d]], right);
  }
  function person(c, x, y, scale, coat, phase) {
    ellipse(c,x,y-13*scale,3*scale,3.6*scale,'#dfb696');
    rect(c,x-3*scale,y-9*scale,6*scale,9*scale,coat,2*scale);
    const step = Math.sin(phase || 0) * 2.3 * scale;
    line(c, [[x-1.5*scale,y-1*scale],[x-2*scale+step,y+6*scale]], '#283b43', 2*scale);
    line(c, [[x+1.5*scale,y-1*scale],[x+2*scale-step,y+6*scale]], '#283b43', 2*scale);
  }
  function pointOn(points, p) {
    const lengths = []; let total = 0;
    for(let i=1;i<points.length;i++){const n=Math.hypot(points[i][0]-points[i-1][0],points[i][1]-points[i-1][1]);lengths.push(n);total+=n;}
    let at = ((p%1)+1)%1 * total;
    for(let i=0;i<lengths.length;i++){if(at<=lengths[i]){const n=at/lengths[i];return{x:mix(points[i][0],points[i+1][0],n),y:mix(points[i][1],points[i+1][1],n),a:Math.atan2(points[i+1][1]-points[i][1],points[i+1][0]-points[i][0])};}at-=lengths[i];}
    return {x:points[0][0],y:points[0][1],a:0};
  }

  // INFINITY: tangible, suspended ecosystems with visible flow between them.
  function cascade(c, t, s) {
    c.fillStyle=grad(c,0,0,1000,620,[[0,'#091f2d'],[.55,'#132939'],[1,'#090e20']]); c.fillRect(0,0,1000,620);
    glow(c,250,130,340,'#24576c66'); glow(c,740,450,280,'#54388544'); stars(c,t,90,'#c6e9f0');
    c.save();c.globalAlpha=.1;poly(c,[[50,0],[150,0],[650,620],[530,620]],'#65cbe0');poly(c,[[250,0],[280,0],[850,620],[780,620]],'#b9dafa');c.restore();
    const worlds=[{x:285,y:169,r:153,accent:'#b6f3ef',top:'#417c83',side:'#204253',name:'I · ИСТОК'}, {x:516,y:328,r:181,accent:'#b0d98a',top:'#537263',side:'#273f43',name:'II · ПРОБУЖДЕНИЕ'}, {x:742,y:486,r:137,accent:'#cbb8ff',top:'#6c5c88',side:'#302c55',name:'III · НОВЫЙ МИР'}];
    for(let j=0;j<2;j++){
      const a=worlds[j],b=worlds[j+1];
      c.beginPath(); c.moveTo(a.x+57,a.y+20); c.bezierCurveTo(a.x+65,a.y+130,b.x-85,b.y-110,b.x-42,b.y-18); c.strokeStyle='#081b29';c.lineWidth=30;c.stroke();
      c.strokeStyle='#8de0d928';c.lineWidth=23;c.stroke();c.strokeStyle='#a2f5e855';c.lineWidth=3;c.stroke();
      for(let k=0;k<22;k++){
        const p=(t*.17+k/22)%1, q=1-p;
        const x=q*q*q*(a.x+57)+3*q*q*p*(a.x+65)+3*q*p*p*(b.x-85)+p*p*p*(b.x-42);
        const y=q*q*q*(a.y+20)+3*q*q*p*(a.y+130)+3*q*p*p*(b.y-110)+p*p*p*(b.y-18);
        ellipse(c,x+Math.sin(k*9)*7,y,1.7,4,'#baf9e5');
      }
    }
    worlds.forEach((w,j)=>{
      const bob=Math.sin(t*.45+j)*3; c.save();c.translate(0,bob);
      glow(c,w.x,w.y+22,w.r+30,w.accent+'15');
      poly(c,[[w.x-w.r,w.y],[w.x-w.r*.79,w.y+35],[w.x-60,w.y+75],[w.x+48,w.y+81],[w.x+w.r*.82,w.y+28],[w.x+w.r,w.y]],w.side);
      poly(c,[[w.x-60,w.y+75],[w.x-36,w.y+24],[w.x+48,w.y+81],[w.x+13,w.y+45]],'#142f3b');
      ellipse(c,w.x,w.y,w.r,w.r*.29,grad(c,0,w.y-50,0,w.y+40,[[0,w.top],[1,'#19303a']]),w.accent+'66',1.3);
      ellipse(c,w.x,w.y-3,w.r*.94,w.r*.25,null,w.accent+'33',1);
      c.save();c.beginPath();c.ellipse(w.x,w.y-4,w.r*.92,w.r*.25,0,0,TAU);c.clip();
      for(let k=0;k<7;k++)line(c,[[w.x-w.r,w.y-25+k*11],[w.x+w.r,w.y+30-k*6]],w.accent+'12',1);
      c.restore();
      if(j===0){
        poly(c,[[w.x-105,w.y+1],[w.x-53,w.y-90],[w.x+5,w.y+5]],'#7bb6c0');poly(c,[[w.x-53,w.y-90],[w.x-32,w.y-11],[w.x+5,w.y+5]],'#3b697f');poly(c,[[w.x-70,w.y-62],[w.x-53,w.y-90],[w.x-34,w.y-60],[w.x-51,w.y-69]],'#e1fbeb');
        ellipse(c,w.x+34,w.y+1,43,12,'#89ddd482');ellipse(c,w.x+34,w.y+1,34,8,'#c7fff829');
        for(let k=0;k<4;k++)poly(c,[[w.x+52+k*17,w.y-3],[w.x+58+k*17,w.y-32-seed(k)*19],[w.x+65+k*17,w.y-4]],'#9ee7e0');
      } else if(j===1){
        line(c,[[w.x-110,w.y+5],[w.x-35,w.y+13],[w.x+50,w.y-10],[w.x+120,w.y+4]],'#a7dab277',8);
        for(let k=0;k<11;k++)tree(c,w.x-125+seed(k+20)*245,w.y-19+seed(k+32)*25,.55+seed(k)*.55,'#245950','#9cc68a');
        cube(c,w.x+25,w.y+4,15,6,29,'#d5deaf','#8c9d73','#6a8373');
        for(let k=0;k<Math.min(4,count(s));k++)cube(c,w.x-65+k*29,w.y+12,9,4,14+k*5,'#dfedaf','#8b9e76','#587f78');
      } else {
        for(let k=0;k<5+Math.min(6,count(s));k++){
          const x=w.x-82+seed(k+60)*158,y=w.y-10+seed(k+70)*17,h=22+seed(k+80)*49;
          poly(c,[[x-6,y],[x-8,y-h*.65],[x,y-h],[x+8,y-h*.65],[x+6,y]],k%2?'#b59ad0':'#8b95cb');line(c,[[x,y-h],[x,y-3]],'#ddcbff',1);
        }
        ellipse(c,w.x,w.y+12,45,10,null,'#c8b9f777',1);
      }
      text(c,w.name,w.x,w.y+w.r*.29+53,10,w.accent+'b0','center',600);c.restore();
    });
    if(s.elapsed<3){const q=clamp(s.elapsed/2.5,0,1),x=mix(285,742,q),y=mix(130,460,q);glow(c,x,y,90,'#fff0a866');ellipse(c,x,y,7,7,'#fff6bd');burst(c,742,468,s,'#f5e4a9',110);}
    for(let k=0;k<12;k++){const x=100+seed(k+420)*800,y=70+((t*10+k*49)%480);ellipse(c,x,y,1.2,1.2,'#b6e7cc77');}
  }

  // INFINITY: an orbital garden, composed around a living gravitational core.
  function orbitGarden(c,t,s){
    c.fillStyle=grad(c,0,0,900,620,[[0,'#100d2d'],[.6,'#142e3f'],[1,'#092c30']]);c.fillRect(0,0,1000,620);
    glow(c,720,410,460,'#2d968522');glow(c,380,210,340,'#755eaa35');stars(c,t,130,'#e5e7f0');
    const ox=500,oy=300;
    for(let i=0;i<8;i++){
      c.save();c.translate(ox,oy);c.rotate(-.35);
      ellipse(c,0,0,105+i*37,48+i*22,null,i%2?'#b0ceb617':'#a69bdc26',1);c.restore();
    }
    c.save();c.translate(ox,oy);c.rotate(-.35);
    const n=6+Math.min(7,count(s));
    const orbs=[];
    for(let i=0;i<n;i++){
      const a=t*(.018+seed(i)*.022)*(i%2?-1:1)+i*2.39,rx=110+(i%7)*39,ry=50+(i%7)*23;
      const x=Math.cos(a)*rx,y=Math.sin(a)*ry;orbs.push({x,y,i,a});
      if(i>0)line(c,[[x,y],[Math.cos(a-.42)*rx,Math.sin(a-.42)*ry]],'#92cdba15',1);
    }
    orbs.forEach(o=>{
      const i=o.i,r=13+(i%4)*6,colors=['#9bcbb2','#cfa4c5','#efcc91','#9ebede'];
      c.save();c.translate(o.x,o.y);c.rotate(.35);
      glow(c,0,0,r*2.8,colors[i%4]+'20');
      ellipse(c,0,0,r,r,grad(c,-r,-r,r,r,[[0,colors[i%4]],[.6,colors[i%4]],[1,'#243c51']]));
      c.save();c.beginPath();c.arc(0,0,r,0,TAU);c.clip();
      for(let q=0;q<4;q++)ellipse(c,-r+q*r*.7,r*.3*Math.sin(q+i),r*.45,r*.28,i%2?'#748d9c55':'#4c877255',null,0,q*.4);
      c.restore();
      if(i%3===0)ellipse(c,0,0,r*1.7,r*.32,null,'#e2d9bda0',2,-.32);
      if(i%3===1){for(let k=0;k<3;k++)tree(c,-r*.4+k*r*.35,-r*.55,.3,'#427c6c','#c6e8b6');}
      if(i===n-1&&count(s))ellipse(c,0,0,r+8,r+8,null,'#c5f6d15a',1);
      c.restore();
    });c.restore();
    const p=pulse(s);glow(c,ox,oy,145+p*75,'#d2f7c444');glow(c,ox,oy,65+p*15,'#fff6b97a');
    for(let i=0;i<7;i++){
      const a=t*.08+i/7*TAU;c.save();c.translate(ox,oy);c.rotate(a);
      c.beginPath();c.moveTo(0,0);c.bezierCurveTo(20,-42,80,-45,94,0);c.bezierCurveTo(63,30,23,31,0,0);c.fillStyle=i%2?'#abd4ae72':'#fff0b0aa';c.fill();c.strokeStyle='#e7f6ce88';c.lineWidth=.8;c.stroke();c.restore();
    }
    ellipse(c,ox,oy,24,24,'#fbf3c5');ellipse(c,ox-6,oy-8,6,4,'#ffffffaa');
    burst(c,ox,oy,s,'#d2f7c4',245);
    for(let i=0;i<16;i++){const a=i*.8+t*.08,d=390+Math.sin(i)*20;ellipse(c,ox+Math.cos(a)*d,oy+Math.sin(a)*245,1.4,1.4,'#c0cfb8aa');}
    text(c,'ГРАВИТАЦИОННЫЙ САД',500,557,11,'#c4d7c27a','center',600);
    text(c,'каждый новый мир меняет орбиту остальных',500,579,11,'#b0c6c377','center');
  }

  function bottle(c,x,y,w,h,color,fillLevel){
    c.save();
    const neck=w*.35;
    c.beginPath();c.moveTo(x-neck/2,y-h);c.lineTo(x+neck/2,y-h);c.lineTo(x+neck/2,y-h*.72);c.quadraticCurveTo(x+w/2,y-h*.62,x+w/2,y-h*.4);c.lineTo(x+w/2,y-4);c.quadraticCurveTo(x+w/2,y,x+w*.3,y);c.lineTo(x-w*.3,y);c.quadraticCurveTo(x-w/2,y,x-w/2,y-4);c.lineTo(x-w/2,y-h*.4);c.quadraticCurveTo(x-w/2,y-h*.62,x-neck/2,y-h*.72);c.closePath();
    c.fillStyle='#badedb17';c.fill();c.strokeStyle='#b4d5cc77';c.lineWidth=1.3;c.stroke();c.clip();
    const ly=y-h*(fillLevel||.42);rect(c,x-w/2,ly,w,h,color,0);ellipse(c,x,ly,w/2,3,color);line(c,[[x-w*.28,y-h*.52],[x-w*.28,y-10]],'#ffffff55',2);
    c.restore();rect(c,x-neck*.7,y-h-5,neck*1.4,8,'#a48255',2);
  }
  // ALCHEMIST: warm material, midnight window and a reaction you can feel.
  function workshop(c,t,s){
    const p=pulse(s);
    c.fillStyle=grad(c,0,0,0,620,[[0,'#1b2428'],[.6,'#33332d'],[1,'#1f2426']]);c.fillRect(0,0,1000,620);
    for(let y=0;y<410;y+=60)for(let x=-50;x<1000;x+=120){rect(c,x+(y%120?55:0),y,112,53,'#80735e0a',7,'#ab9b7420');}
    glow(c,735,175,280,'#dba55d22');
    // Window: sky and a distinct arched timber frame.
    c.save();c.beginPath();c.moveTo(70,350);c.lineTo(70,150);c.bezierCurveTo(70,20,310,20,310,150);c.lineTo(310,350);c.closePath();c.fillStyle='#10191e';c.fill();c.lineWidth=18;c.strokeStyle='#554438';c.stroke();c.clip();
    c.fillStyle=grad(c,0,70,0,350,[[0,'#223d51'],[1,'#477072']]);c.fillRect(65,40,250,320);
    ellipse(c,230,122,25,25,'#e1e5b2');ellipse(c,240,114,24,24,'#2c4b5d');
    for(let i=0;i<18;i++)ellipse(c,90+seed(i+9)*200,75+seed(i)*180,1,1,'#edf0bfa0');
    poly(c,[[60,352],[60,291],[110,270],[145,305],[180,260],[220,292],[260,245],[330,283],[330,352]],'#213f46');
    c.restore();line(c,[[190,64],[190,350]],'#654e3a',8);line(c,[[73,226],[307,226]],'#654e3a',7);rect(c,58,347,264,17,'#7f6245',3);
    // Shelves and stored ingredients.
    for(let row=0;row<2;row++){
      const y=156+row*125;rect(c,580,y,335,15,'#564437',3);rect(c,589,y+15,317,7,'#172322',0);
      for(let i=0;i<7;i++){const h=40+seed(i+row*13)*37;bottle(c,606+i*45,y,20+seed(i)*8,h,['#588d76','#ac6d54','#a89968','#587a84'][(i+row)%4],.42+seed(i+3)*.18);}
      if(row===1)for(let i=0;i<Math.min(4,count(s));i++){ellipse(c,620+i*47,y-27,6,6,'#fff2a249');spark(c,620+i*47,y-27,3,'#e9deab');}
    }
    line(c,[[452,0],[452,115]],'#918060',2);ellipse(c,452,124,27,18,'#a58b59','#ddbe79',1);ellipse(c,452,125,17,10,'#f4dda0');glow(c,452,140,150,'#f2cc7326');
    // Herb bundles.
    for(let i=0;i<4;i++){const x=351+i*35;line(c,[[x,24],[x+3,106]],'#857557',1);for(let j=0;j<6;j++){ellipse(c,x+(j%2?6:-6),61+j*6,5,11,j%2?'#5b7861':'#789075',null,0,j%2?.5:-.5);}line(c,[[x-8,43],[x+7,43]],'#c6ac75',2);}
    // The substantial wooden worktop.
    poly(c,[[0,421],[1000,421],[1000,535],[0,535]],grad(c,0,421,0,535,[[0,'#8b6543'],[1,'#5d4333']]));
    for(let i=0;i<8;i++)line(c,[[0,435+i*12],[1000,431+i*12]],'#d4ab7320',1);
    rect(c,0,530,1000,90,'#2b2a27');rect(c,0,530,1000,14,'#47392d');
    for(let x=35;x<1000;x+=240){rect(c,x,558,196,45,'#333029',5,'#9a79493b');ellipse(c,x+98,580,5,4,'#ad8a54');}
    // Open recipe book, spoon and mineral bowl.
    poly(c,[[75,446],[210,431],[249,495],[111,511]],'#c7b786','#6b563b',2);poly(c,[[210,431],[326,440],[346,498],[249,495]],'#e1d2a3','#6b563b',2);
    for(let i=0;i<5;i++){line(c,[[96+i*4,455+i*9],[197+i*4,445+i*9]],'#766c4760',1);line(c,[[235+i*4,450+i*8],[306+i*5,455+i*8]],'#87724d60',1);}
    ellipse(c,155,477,20,11,null,'#7d8b64',1);spark(c,155,477,7,'#7d8b64');
    ellipse(c,366,467,49,16,'#222e30');ellipse(c,366,460,49,17,'#68716a');poly(c,[[317,460],[330,488],[397,488],[415,460]],'#535f58');line(c,[[373,454],[409,414]],'#929787',13);
    // Cauldron, three feet, a lit belly, liquid surface and copper rim.
    glow(c,570,445,170,'#74e2ad'+(p>.1?'55':'20'));
    for(const x of [490,560,633])line(c,[[x,488],[x-6,519]],'#212a28',12);
    ellipse(c,562,452,112,67,grad(c,450,380,660,480,[[0,'#425d55'],[.48,'#283e3a'],[1,'#152b2b']]),'#6a8061',2);
    ellipse(c,562,407,112,34,'#172d28','#ae9c62',8);
    ellipse(c,562,407,101,27,grad(c,0,385,0,435,[[0,'#a3eab1'],[1,'#3aab91']]),'#bdedb1',1);
    for(let i=0;i<5;i++){const rr=12+i*15;ellipse(c,562+Math.sin(t+i)*3,406,rr,rr*.23,null,'#e5f9bf'+(i%2?'55':'33'),1);}
    ellipse(c,445,436,13,20,null,'#9a8b57',5);ellipse(c,679,436,13,20,null,'#9a8b57',5);
    for(let i=0;i<16;i++){
      const q=(t*.16+i/16)%1,x=524+seed(i+4)*78+Math.sin(q*6+i)*18,y=399-q*(110+p*90),r=2+seed(i)*5+q*10;
      c.globalAlpha=(1-q)*.48;ellipse(c,x,y,r,r*.8,'#b6e6c3');c.globalAlpha=1;
    }
    for(let i=0;i<8;i++){const a=t*.6+i;ellipse(c,562+Math.cos(a)*70,407+Math.sin(a)*16,2+seed(i)*3,1.5,'#e9ffc499');}
    // Finished flask and growing crystals.
    bottle(c,830,489,76,144,count(s)%2?'#cbb175aa':'#6bb9a6aa',.48);
    for(let i=0;i<4+Math.min(4,count(s));i++){const x=730+i*19,y=509+Math.sin(i)*6,h=23+seed(i+16)*27;poly(c,[[x-7,y],[x-8,y-h*.6],[x,y-h],[x+9,y-h*.65],[x+6,y]],i%2?'#a9d1ad':'#779f94');line(c,[[x,y-h],[x,y-1]],'#deedb677',1);}
    burst(c,562,392,s,'#e2f8b2',135);
    if(p){for(let i=0;i<10;i++){const a=i/10*TAU+t; spark(c,562+Math.cos(a)*(35+p*60),363+Math.sin(a)*p*70,3*p,'#f6e9b3');}}
  }

  // ALCHEMIST: white glass, liquid optics and a macro crystal reaction.
  function microLab(c,t,s){
    const p=pulse(s);
    c.fillStyle=grad(c,0,0,1000,620,[[0,'#edf1e8'],[.6,'#dbe9e1'],[1,'#c6dad6']]);c.fillRect(0,0,1000,620);
    glow(c,800,120,350,'#ffffffb0');
    for(let i=0;i<7;i++)line(c,[[70+i*145,0],[70+i*145,620]],'#446e6710',1);
    for(let i=0;i<5;i++)line(c,[[0,90+i*130],[1000,90+i*130]],'#446e6710',1);
    ellipse(c,510,514,339,34,'#567c7223');
    // Instrument supports.
    rect(c,194,482,137,20,'#6b8680',10);rect(c,248,105,9,381,'#9baea6',4);line(c,[[252,122],[421,122],[421,173]],'#829c92',7);
    rect(c,708,487,156,18,'#6b8680',9);rect(c,780,154,8,335,'#9baea6',4);
    // A long transparent feed tube, detached from ordinary dashboard cards.
    line(c,[[289,314],[331,314],[331,222],[409,222]],'#87aaa250',14);line(c,[[289,314],[331,314],[331,222],[409,222]],'#f8fff9aa',9);line(c,[[289,314],[331,314],[331,222],[409,222]],'#64bcae77',3);
    line(c,[[617,327],[704,327],[704,240],[781,240]],'#6a9d9655',14);line(c,[[617,327],[704,327],[704,240],[781,240]],'#f3fff8bb',9);
    // Left measuring cylinder.
    rect(c,191,196,112,250,'#f6fff832',14,'#688d8799');
    c.save();c.beginPath();c.roundRect(196,201,102,240,10);c.clip();
    c.fillStyle=grad(c,0,271,0,441,[[0,'#87d4be88'],[1,'#3a8e86cc']]);c.fillRect(196,279,102,166);ellipse(c,247,279,51,9,'#9be0ca');
    for(let i=0;i<9;i++){const y=434-((t*15+i*19)%149);ellipse(c,210+seed(i)*73,y,1.5+seed(i+5)*3,2+seed(i)*4,null,'#e1fff5aa',1);}
    c.restore();ellipse(c,247,196,56,10,'#ebf5e7aa','#7ea096',2);ellipse(c,247,446,61,10,'#acbfb5');
    for(let i=0;i<8;i++){const y=231+i*26;line(c,[[272,y],[292,y]],'#3e706c88',1);if(i%2===0)text(c,String(80-i*10),263,y,9,'#4b726c','right');}
    // Central glass sphere with its meniscus and layered reagent.
    const cx=515,cy=345,r=127;
    ellipse(c,cx,cy,r,r,'#f9fff22e','#6f99948c',2);
    c.save();c.beginPath();c.arc(cx,cy,r-4,0,TAU);c.clip();
    c.fillStyle=grad(c,0,307,0,474,[[0,count(s)%2?'#efa18f99':'#8fcdbdcc'],[1,'#518b8bcc']]);c.fillRect(cx-r,326-p*13,r*2,160);
    ellipse(c,cx,326-p*13,124,19,count(s)%2?'#f0b8a6':'#a3dac9');
    for(let i=0;i<7;i++){
      c.beginPath();const y=347+i*14;c.moveTo(cx-r,y);c.bezierCurveTo(cx-40,y-15+Math.sin(t+i)*9,cx+40,y+18,cx+r,y-2);c.strokeStyle='#e9fff34d';c.lineWidth=2;c.stroke();
    }
    for(let i=0;i<18;i++){const q=(t*.12+i/18)%1;ellipse(c,cx-75+seed(i+15)*150,459-q*130,2+seed(i)*4,2+seed(i)*4,null,'#eaffed99',1);}
    // An immersed crystal changes permanently after each synthesis.
    const crystalH=49+Math.min(5,count(s))*7;
    poly(c,[[481,440],[481,406],[503,440-crystalH],[523,408],[521,440]],'#d4ebce');poly(c,[[503,440-crystalH],[523,408],[521,440],[502,449]],'#84b1ad');line(c,[[503,440-crystalH],[503,440]],'#f7fff0',2);
    c.restore();
    c.beginPath();c.ellipse(cx,cy,r-13,r-13,0,Math.PI*1.07,Math.PI*1.57);c.strokeStyle='#ffffffb8';c.lineWidth=10;c.stroke();
    c.beginPath();c.ellipse(cx,cy,r-7,r-7,0,.05,.7);c.strokeStyle='#ffffff72';c.lineWidth=3;c.stroke();
    rect(c,493,197,44,60,'#e7f6eb9c',6,'#78a09388');ellipse(c,515,201,23,6,'#bccfc2');
    // Suspended micropipette and falling drops.
    rect(c,503,93,24,76,'#74958d',8);rect(c,510,161,10,42,'#e4b3a0',2);poly(c,[[510,202],[520,202],[516,220],[514,220]],'#91b2a7');
    const dropY=225+((t*.6)%1)*86;ellipse(c,515,dropY,4,6,'#e49c85');
    // Right glass coil and two sealed sample chambers.
    for(let i=0;i<4;i++){c.beginPath();c.ellipse(781,263+i*27,43,18,0,0,TAU);c.strokeStyle='#71968e65';c.lineWidth=10;c.stroke();c.strokeStyle='#ecfff6b3';c.lineWidth=6;c.stroke();}
    line(c,[[781,355],[781,410]],'#7bbab1aa',7);
    bottle(c,760,479,54,78,'#baca93aa',.5);bottle(c,836,479,47,64,'#e4aa8bbb',.45);
    // Specimen dish: angular facets, readable even when the reaction is still.
    ellipse(c,540,521,105,24,'#c6dad0aa','#6d958777',1);ellipse(c,540,518,91,17,'#eaf1df77');
    for(let i=0;i<5+Math.min(5,count(s));i++){
      const x=474+seed(i+90)*130,y=518+seed(i+12)*10,h=20+seed(i+23)*45;
      poly(c,[[x-10,y],[x-13,y-h*.65],[x,y-h],[x+11,y-h*.7],[x+8,y]],i%2?'#dfaa98':'#83b7ac');poly(c,[[x,y-h],[x+11,y-h*.7],[x+8,y],[x,y+3]],i%2?'#b77c78':'#4e918e');line(c,[[x,y-h],[x,y]],'#ffffe699',1);
    }
    text(c,'SYNTHESIS / 04',515,569,11,'#3c726b','center',600);text(c,'капля → реакция → новая структура',515,590,10,'#71948b','center');
    burst(c,cx,cy,s,'#ec967f',110);
    if(p)glow(c,cx,cy,150,'#efbd8c'+Math.round(p*65).toString(16).padStart(2,'0'));
  }

  // COLONY: inhabited isometric rock, not a collection of resource counters.
  function asteroidTown(c,t,s){
    c.fillStyle=grad(c,0,0,1000,620,[[0,'#142634'],[.55,'#1d2832'],[1,'#10212c']]);c.fillRect(0,0,1000,620);
    glow(c,180,80,330,'#d28f5e2f');stars(c,t,100,'#dce6e9');
    ellipse(c,160,128,116,116,grad(c,65,20,260,230,[[0,'#d0a17c'],[.5,'#a16e5c'],[1,'#3a3d49']]));
    c.save();c.beginPath();c.arc(160,128,115,0,TAU);c.clip();for(let i=0;i<8;i++)ellipse(c,140,65+i*28,150,9,'#d1a18122',null,0,-.2);c.restore();
    c.save();c.translate(160,128);c.rotate(-.25);ellipse(c,0,0,172,31,null,'#bc9f8055',9);ellipse(c,0,0,181,35,null,'#bda28144',2);c.restore();
    for(let i=0;i<13;i++){const x=seed(i+30)*1000,y=seed(i+66)*620,r=4+seed(i)*13;c.save();c.translate(x+Math.sin(t*.06+i)*4,y);c.rotate(i);poly(c,[[-r,0],[-r*.6,-r],[r*.4,-r*.8],[r,r*.1],[r*.4,r],[-r*.7,r*.5]],'#60727744');c.restore();}
    // Asteroid facets and shelf of pale regolith.
    const top=[[157,321],[246,223],[431,193],[641,205],[842,296],[856,377],[704,455],[459,485],[261,426]];
    poly(c,[[157,321],[261,426],[459,485],[704,455],[856,377],[814,471],[662,549],[437,570],[243,490],[177,398]],'#4d4b4a');
    poly(c,[[261,426],[459,485],[437,570],[319,512]],'#75614f');poly(c,[[459,485],[704,455],[662,549],[437,570]],'#3d4546');poly(c,[[704,455],[856,377],[814,471],[662,549]],'#2b3c43');
    poly(c,top,grad(c,0,200,0,490,[[0,'#b2ac96'],[.6,'#938d78'],[1,'#6e756b']]),'#d6c3a355',1);
    for(let i=0;i<23;i++){const x=255+seed(i+77)*500,y=260+seed(i+41)*160;ellipse(c,x,y,7+seed(i)*14,3+seed(i+2)*5,'#525e522d');}
    line(c,[[278,333],[426,272],[604,331],[742,309]],'#4b625c',12);line(c,[[426,272],[503,391],[682,406]],'#4b625c',13);
    line(c,[[278,333],[426,272],[604,331],[742,309]],'#cfdbc169',2,[3,9]);line(c,[[426,272],[503,391],[682,406]],'#b3e2b477',2,[3,9]);
    // Solar field, individual dark cells.
    for(let row=0;row<2;row++)for(let k=0;k<4;k++){
      const x=271+k*32+row*15,y=334+row*29;
      line(c,[[x,y],[x,y+23]],'#4b5f5f',3);poly(c,[[x-19,y-13],[x+8,y-24],[x+28,y-12],[x,y]],'#284d61','#7cacab',1);
      line(c,[[x-9,y-16],[x+11,y-4]],'#a0c1bc66',1);line(c,[[x+1,y-20],[x+21,y-8]],'#a0c1bc66',1);
    }
    // Headquarters and luminous windows.
    cube(c,467,255,62,27,58,'#dde0cc','#94a7a1','#698e91');
    poly(c,[[409,232],[467,258],[467,277],[409,250]],'#487b85');poly(c,[[472,258],[523,236],[523,253],[472,276]],'#2d5c6b');
    for(let k=0;k<4;k++)line(c,[[418+k*12,239+k*5],[418+k*12,252+k*5]],'#c0e6d9',3);
    cube(c,462,235,26,12,26,'#ebead5','#9eaea0','#79a2a1');line(c,[[462,211],[462,172]],'#d4e0cb',3);ellipse(c,462,171,4,4,'#daae80');
    // Greenhouse dome, visible plants under glass.
    ellipse(c,613,330,70,28,'#627f71');
    c.save();c.beginPath();c.ellipse(613,322,66,53,0,Math.PI,TAU);c.lineTo(679,328);c.ellipse(613,328,66,23,0,0,Math.PI);c.closePath();c.fillStyle='#a2dcc664';c.fill();c.strokeStyle='#dce8caa1';c.lineWidth=2;c.stroke();
    for(let i=0;i<6;i++)tree(c,572+i*17,325+Math.sin(i)*7,.55,'#3d775c','#a4c884');
    for(let i=-2;i<=2;i++){c.beginPath();c.ellipse(613,321,Math.abs(i)*20+5,50,0,Math.PI,TAU);c.strokeStyle='#d9ead15e';c.lineWidth=1;c.stroke();}c.restore();
    // Cargo pads and small homes build out after each action.
    for(let i=0;i<2+Math.min(4,count(s));i++){
      const x=472+(i%3)*61,y=378+Math.floor(i/3)*28;
      cube(c,x,y,24,11,24,'#d1d4bc','#94aaa3','#5c858b');rect(c,x-12,y-8,10,8,'#dfdca3',1);line(c,[[x+4,y+4],[x+17,y-2]],'#a6ddcf',3);
    }
    ellipse(c,752,328,58,25,'#637c76','#b4c6ad',2);ellipse(c,752,328,36,15,null,'#d2d6ab',1);line(c,[[736,325],[752,333],[769,325]],'#dce2bb',2);
    // Antenna and crane silhouettes.
    line(c,[[747,271],[747,204],[799,224]],'#ced0b8',5);line(c,[[747,204],[788,204],[799,224]],'#738b83',3);line(c,[[793,224],[793,266]],'#d2d6be',1);rect(c,787,263,13,13,'#d2a677',2);
    const rover=pointOn([[278,333],[426,272],[503,391],[682,406]],t*.035);
    ellipse(c,rover.x,rover.y+5,12,5,'#405550');rect(c,rover.x-11,rover.y-8,22,11,'#e6d5a7',3);rect(c,rover.x-4,rover.y-14,10,7,'#4d7a85',2);glow(c,rover.x+12,rover.y-2,14,'#fff2ba44');
    if(s.elapsed<3){const q=clamp(s.elapsed/2,0,1),y=mix(60,290,1-Math.pow(1-q,3));glow(c,752,y+33,60,'#70dbe077');poly(c,[[733,y+20],[740,y+65],[748,y+20]],'#9cebea88');poly(c,[[762,y+20],[768,y+65],[776,y+20]],'#9cebea88');poly(c,[[720,y],[737,y-18],[767,y-18],[787,y],[777,y+21],[731,y+21]],'#dce0ca');rect(c,740,y-6,26,12,'#477b87',3);}
    burst(c,752,328,s,'#c8ead0',83);
    text(c,'ПОСЕЛЕНИЕ «ТИХАЯ ОРБИТА»',492,585,11,'#bdc9c5aa','center',600);
  }

  // COLONY: a readable sectional diorama of a working industrial organism.
  function asteroidSection(c,t,s){
    c.fillStyle=grad(c,0,0,0,620,[[0,'#111c2a'],[.35,'#1d2b35'],[1,'#322a2b']]);c.fillRect(0,0,1000,620);stars(c,t,60,'#d5dcde');
    ellipse(c,830,62,34,34,'#95b7b346');
    const rock=[[56,171],[116,145],[239,157],[324,131],[422,146],[521,136],[608,153],[748,129],[873,151],[941,177],[928,555],[845,593],[711,574],[551,590],[395,573],[249,591],[103,556]];
    poly(c,rock,'#655954');
    for(let i=0;i<8;i++){
      c.beginPath();c.moveTo(73,199+i*47);for(let k=1;k<10;k++)c.lineTo(73+k*95,190+i*47+seed(i*10+k)*21);c.strokeStyle=i%2?'#b4987521':'#211f2940';c.lineWidth=10;c.stroke();
    }
    // Surface plant, dishes and a low horizon.
    rect(c,151,111,168,42,'#b5b5a3',5);rect(c,161,120,145,16,'#395860',2);for(let i=0;i<9;i++)rect(c,168+i*15,123,7,10,'#a7d6c4',1);
    rect(c,335,85,23,68,'#839b91',2);rect(c,364,107,14,45,'#90a49a',2);for(let i=0;i<5;i++)ellipse(c,347+Math.sin(t*.5+i)*6,72-i*13-(t*6%13),8+i*3,5+i*2,'#c8d5c71a');
    line(c,[[688,140],[688,82]],'#bfcab1',3);c.beginPath();c.arc(688,67,30,.2,Math.PI-.2);c.fillStyle='#a4b7ac';c.fill();line(c,[[688,80],[703,55]],'#c7d8bd',2);
    // Cut faces and illuminated chambers.
    const rooms=[{x:108,y:196,w:256,h:113,c:'#405b54'},{x:390,y:196,w:267,h:113,c:'#364d58'},{x:108,y:334,w:256,h:110,c:'#554f47'},{x:390,y:334,w:267,h:110,c:'#4a4e4b'},{x:108,y:467,w:549,h:83,c:'#332f33'},{x:772,y:197,w:109,h:351,c:'#344a50'}];
    rooms.forEach(r=>{rect(c,r.x-6,r.y-7,r.w+12,r.h+14,'#292e32',7);rect(c,r.x,r.y,r.w,r.h,r.c,3);line(c,[[r.x+8,r.y+7],[r.x+r.w-8,r.y+7]],'#e7dca478',3);line(c,[[r.x,r.y+r.h-9],[r.x+r.w,r.y+r.h-9]],'#a2aa9277',3);});
    // Living quarters: plants, table, warm round windows.
    for(let i=0;i<4;i++){rect(c,126+i*56,223,43,38,'#829d8b',7);rect(c,130+i*56,227,35,26,'#224852',5);ellipse(c,146+i*56,237,7,7,'#9ec2aa77');}
    rect(c,185,280,94,8,'#c3b78e',2);line(c,[[198,288],[198,300]],'#a79f85',3);line(c,[[267,288],[267,300]],'#a79f85',3);tree(c,325,293,.95,'#456852','#9ebc7b');
    person(c,163+Math.sin(t*.1)*14,291,.9,'#ceaa80',t*2);person(c,293,292,.9,'#8ab9b0',0);
    // Greenhouse in the upper right room.
    for(let i=0;i<7;i++){rect(c,409+i*33,276,27,18,'#819784',2);tree(c,423+i*33,279,.75,'#50795b','#bdd291');line(c,[[423+i*33,217],[423+i*33,232]],'#bbdfad55',1);ellipse(c,423+i*33,215,11,3,'#dbedc0');}
    line(c,[[407,293],[637,293]],'#6b9fa0',4);
    // Smelting chamber and visible conveyor inventory.
    rect(c,122,353,80,73,'#90765e',5);rect(c,133,368,57,39,'#4b3c35',4);glow(c,161,389,45,'#ffad4b4d');rect(c,142,378,40,26,'#eaa25d',3);
    for(let i=0;i<6;i++){const x=207+(t*16+i*22)%142;poly(c,[[x,407],[x+9,401],[x+17,407],[x+13,418],[x+3,419]],'#bf9e6d');}
    rect(c,204,422,146,8,'#293d42',3);for(let i=0;i<12;i++)ellipse(c,210+i*12,427,3,3,'#92967d');
    // Water recovery: pipes, tanks and bubbles.
    for(let i=0;i<3;i++){rect(c,411+i*75,356,53,70,'#8eaaa347',8,'#8da69c');rect(c,416+i*75,387,43,35,'#77a8a080',3);ellipse(c,437+i*75,387,21,5,'#9fc7b0');for(let j=0;j<4;j++)ellipse(c,422+i*75+j*9,419-((t*12+j*7)%29),1.5,2,'#d1ead2aa');}
    line(c,[[405,343],[638,343],[638,428]],'#a8bb9a',4);
    // Lower mine: ore seams, drill and transport cart.
    for(let i=0;i<20;i++){const x=122+seed(i+13)*510,y=481+seed(i+50)*48;poly(c,[[x-4,y],[x,y-8],[x+7,y-3],[x+6,y+5]],i%3?'#d2a77077':'#aad5caa0');}
    line(c,[[126,536],[639,536]],'#879084',3);line(c,[[126,541],[639,541]],'#323f40',2);
    const cartX=150+(t*21%375);rect(c,cartX,516,40,18,'#ac9275',3);ellipse(c,cartX+8,536,4,4,'#272c32');ellipse(c,cartX+32,536,4,4,'#272c32');poly(c,[[cartX+4,517],[cartX+11,505],[cartX+24,508],[cartX+32,501],[cartX+38,517]],'#d7b27a');
    rect(c,573,495,47,28,'#7d9390',4);poly(c,[[620,501],[648,508],[620,518]],'#a5b9a3');
    // Shaft: lift follows actual action time, moving between visible floors.
    rect(c,686,178,61,379,'#1b303b',5);line(c,[[695,186],[695,547]],'#849a98',2);line(c,[[738,186],[738,547]],'#849a98',2);
    let liftY=346+Math.sin(t*.16)*13;if(s.elapsed<3)liftY=mix(474,213,Math.min(1,s.elapsed/2.7));
    line(c,[[714,182],[714,liftY]],'#bfd0b3',2);rect(c,695,liftY,42,44,'#bec4a3',3);rect(c,700,liftY+5,32,30,'#486b69',2);person(c,716,liftY+28,.65,'#e3bd77',0);
    // Battery vault receives one extra lit cell per delivery.
    for(let i=0;i<15;i++){const x=785+(i%3)*29,y=224+Math.floor(i/3)*59;rect(c,x,y,20,43,'#647b77',3);rect(c,x+4,y+6,12,28,i<6+count(s)?'#c5cf92':'#33494c',2);}
    text(c,'01 · ОБИТАЕМЫЙ УРОВЕНЬ',110,184,9,'#dddbc0','left',600);text(c,'02 · ПЕРЕРАБОТКА',110,322,9,'#dddbc0','left',600);text(c,'03 · ДОБЫЧА',110,457,9,'#dddbc0','left',600);
    burst(c,715,liftY+22,s,'#e2d6a1',65);
  }

  function cityBlock(c,x,y,w,h,height,tone){
    poly(c,[[x,y],[x+w,y],[x+w+7,y+7],[x+7,y+7]],tone);
    rect(c,x+7,y+7,w,h,'#77978a22',2);
    rect(c,x,y-height,w,h,tone,3);poly(c,[[x+w,y-height],[x+w+6,y-height+6],[x+w+6,y+h],[x+w,y+h-height]],'#8da495');
    rect(c,x+4,y-height+4,w-8,h-8,'#ffffff18',2);
  }
  // SUBWAY: an inhabited model city, with geography and a network that grows.
  function cityMap(c,t,s){
    c.fillStyle='#dce8d7';c.fillRect(0,0,1000,620);
    const river=[[605,-40],[564,78],[605,173],[542,271],[582,379],[534,482],[552,665]];
    line(c,river,'#c5d8cb',138);line(c,river,'#92bebc',106);line(c,river,'#add3ca',77);
    for(let i=0;i<18;i++){const q=pointOn(river,t*.006+i/18);line(c,[[q.x-17,q.y],[q.x+8,q.y-4]],'#e5f1d844',1);}
    // Broad streets, small quiet blocks, parks and canal edges.
    for(let y=83;y<620;y+=95)line(c,[[0,y],[470,y],[656,y+16],[1000,y+16]],'#f0eedc',15);
    for(let x=73;x<1000;x+=107)line(c,[[x,0],[x,620]],'#f0eedc',12);
    for(let i=0;i<76;i++){
      const x=33+(i%10)*103+seed(i)*12,y=33+Math.floor(i/10)*78+seed(i+10)*10;
      const rp=river.reduce((best,p)=>Math.abs(p[1]-y)<Math.abs(best[1]-y)?p:best,river[0]);
      if(Math.abs(x-rp[0])<98||((x>130&&x<295)&&(y>350&&y<490)))continue;
      cityBlock(c,x,y,27+seed(i+12)*31,23+seed(i+43)*20,3+seed(i+8)*12,['#b8c9b3','#c9cbb5','#bcc9bf','#d4c9ae'][i%4]);
    }
    rect(c,114,360,190,119,'#abc89f',22);ellipse(c,207,421,49,26,'#c6dcac');line(c,[[127,442],[189,386],[286,437]],'#e2dfb7',7);
    for(let i=0;i<24;i++){const x=123+seed(i+78)*171,y=370+seed(i+90)*93;ellipse(c,x+2,y+4,8,6,'#365d3920');ellipse(c,x,y,8+seed(i)*5,8+seed(i)*4,i%2?'#7b9f77':'#8dac7a');}
    // Bridges stitch the river to the city rather than ending at its bank.
    for(const y of [177,367,514]){line(c,[[475,y],[655,y+7]],'#7d9c90',25);line(c,[[475,y-3],[655,y+4]],'#e6e5cb',18);line(c,[[475,y-10],[655,y-3]],'#657c6f',1);}
    const routes=[[[72,177],[234,177],[345,277],[481,277],[659,367],[898,367]],[[180,55],[234,177],[234,315],[362,421],[481,514],[797,514]],[[81,315],[234,315],[345,277],[459,177],[709,177],[869,83]]];
    const colors=['#d36b5a','#387c7c','#bd9548'];
    routes.forEach((r,i)=>{line(c,r,'#f8f4dd',12);line(c,r,colors[i],7);});
    const extension=[[659,367],[709,265],[831,265],[898,177]];
    if(count(s)){line(c,extension,'#f8f4dd',12);line(c,extension,'#8b7ba7',7);for(let i=0;i<Math.min(5,count(s));i++)cityBlock(c,757+i*34,225-i%2*35,25,20,24+i*3,'#a5b7ac');}
    routes.forEach((r,i)=>r.forEach((pt,j)=>{
      if(i&&j===1)return;ellipse(c,pt[0]+2,pt[1]+3,9,9,'#426b4f1c');ellipse(c,pt[0],pt[1],8,8,'#f5f2dd',colors[i],2);ellipse(c,pt[0],pt[1],2,2,colors[i]);
    }));
    ellipse(c,345,277,13,13,'#f6f3dd','#596e64',2);ellipse(c,345,277,7,7,null,'#596e64',1);
    const allRoutes=count(s)?routes.concat([extension]):routes;
    allRoutes.forEach((r,i)=>{
      for(let j=0;j<2;j++){
        const pt=pointOn(r,t*(.018+i*.003)+j*.49+i*.2);c.save();c.translate(pt.x,pt.y);c.rotate(pt.a);
        rect(c,-18,3,37,9,'#2d4c4629',4);rect(c,-19,-6,38,12,'#fbf7e8',4,colors[i%3]);rect(c,-12,-4,8,8,'#668c89',2);rect(c,1,-4,8,8,'#668c89',2);line(c,[[13,-3],[13,3]],colors[i%3],2);c.restore();
      }
    });
    // City life: tiny traffic and ferries run on their own slower cadence.
    for(let i=0;i<12;i++){const x=(t*(i%2?8:-6)+i*89+1000)%1000,y=83+(i%5)*95;rect(c,x,y-3,10,5,i%3?'#98a997':'#cf9c70',2);}
    const ferry=pointOn(river,t*.012+.25);c.save();c.translate(ferry.x,ferry.y);c.rotate(ferry.a);rect(c,-14,-5,28,10,'#f4eed5',5);rect(c,-6,-3,12,6,'#7ca2a0',2);c.restore();
    text(c,'СТАРЫЙ ГОРОД',216,129,11,'#526f5f','center',650);text(c,'БОТАНИЧЕСКИЙ ПАРК',211,497,10,'#526f5f','center',650);text(c,'РЕЧНОЙ ВОКЗАЛ',743,396,11,'#526f5f','center',650);
    text(c,count(s)?'НОВЫЙ КВАРТАЛ':'СЕВЕРНЫЙ БЕРЕГ',817,211,10,'#526f5f','center',650);
    burst(c,709,265,s,'#9676ac',120);
    if(s.elapsed<3){c.save();c.globalAlpha=pulse(s);line(c,extension,'#fff7d7',12);c.restore();}
  }

  function trainSide(c,x,y,cars,body,t,s,doors){
    for(let i=0;i<cars;i++){
      const xx=x+i*124;
      rect(c,xx,y,119,55,body,9,'#f1ddb66e');rect(c,xx+2,y+34,115,15,'#327f88',1);
      rect(c,xx+10,y+10,30,19,'#243d4d',4);rect(c,xx+77,y+10,30,19,'#243d4d',4);
      ellipse(c,xx+21,y+54,5,5,'#182a31');ellipse(c,xx+98,y+54,5,5,'#182a31');
      const gap=doors*12;
      rect(c,xx+48,y+9,22,40,'#1d3f49',2);
      rect(c,xx+48-gap,y+9,10,39,'#c6cdb6',1);rect(c,xx+60+gap,y+9,10,39,'#c6cdb6',1);
      rect(c,xx+50-gap,y+12,6,14,'#577b81',1);rect(c,xx+62+gap,y+12,6,14,'#577b81',1);
      if(i<cars-1)rect(c,xx+119,y+17,5,32,'#33434a',1);
    }
    glow(c,x+cars*124-1,y+36,29,'#fae5a947');ellipse(c,x+cars*124-6,y+36,3,3,'#f7df9e');
  }
  // SUBWAY: a cinematic section through the city, with platforms and people.
  function underground(c,t,s){
    c.fillStyle=grad(c,0,0,0,620,[[0,'#d6ddd0'],[.28,'#aebfb3'],[.29,'#655a4f'],[1,'#343a40']]);c.fillRect(0,0,1000,620);
    // A city horizon gives the underground its place in a living world.
    for(let i=0;i<12;i++){
      const x=i*89-20,h=37+seed(i+7)*90;
      rect(c,x,151-h,65+seed(i)*20,h,['#b5c4b5','#afc0b1','#c1c9b5'][i%3],2);
      for(let yy=158-h;yy<142;yy+=14)for(let xx=x+9;xx<x+60;xx+=15)rect(c,xx,yy,5,7,'#7c9c932a',1);
    }
    rect(c,0,148,1000,18,'#d1c7ad');rect(c,0,166,1000,11,'#8f9581');
    for(let i=0;i<13;i++)tree(c,26+i*79,148,.6,'#728b70','#90a585');
    for(let i=0;i<9;i++){const x=(t*(i%2?6:-5)+i*117+1000)%1000;person(c,x,151,.64,['#7b8d80','#b4896d','#617d82'][i%3],t*3+i);}
    // Entrance canopy and a cut-away staircase from street to platform.
    rect(c,648,126,117,11,'#4b7779',3);line(c,[[658,137],[658,163]],'#647f78',4);line(c,[[756,137],[756,163]],'#647f78',4);rect(c,682,105,25,21,'#397778',3);text(c,'M',695,116,14,'#e9dfbb','center',700);
    poly(c,[[664,169],[729,169],[823,250],[758,250]],'#354c52');
    for(let i=0;i<11;i++)line(c,[[666+i*8,176+i*7],[726+i*8,176+i*7]],'#a7b7a3',3);
    line(c,[[663,169],[756,247]],'#d6d6b4',3);line(c,[[730,169],[823,247]],'#d6d6b4',3);
    // Earth layers remain visible around two carved stations.
    for(let i=0;i<10;i++){c.beginPath();c.moveTo(0,194+i*44);for(let x=80;x<=1040;x+=80)c.lineTo(x,194+i*44+seed(i+x)*12);c.strokeStyle=i%2?'#95816a26':'#1f2a3444';c.lineWidth=4;c.stroke();}
    const levels=[{y:248,h:120,color:'#b3b89e',name:'01  ·  НАБЕРЕЖНАЯ'},{y:437,h:114,color:'#8b9c8d',name:'02  ·  МАСТЕРСКИЕ'}];
    levels.forEach((l,j)=>{
      rect(c,37,l.y-15,926,l.h+27,'#233843',31);rect(c,51,l.y,898,l.h,l.color,18);
      // tiled wall, soft arches, warm practical lamps
      for(let yy=l.y+6;yy<l.y+65;yy+=15)line(c,[[65,yy],[935,yy]],'#e6ddbc28',1);
      for(let xx=71;xx<940;xx+=30)line(c,[[xx,l.y+6],[xx,l.y+65]],'#738c7c22',1);
      for(let i=0;i<7;i++){
        const x=98+i*131;rect(c,x,l.y+8,62,40,'#5e7d7877',9);rect(c,x+9,l.y+14,44,25,j?'#cab080':'#a3c4ae',3);
        line(c,[[x+4,l.y-1],[x+58,l.y-1]],'#ece2ab',3);glow(c,x+31,l.y+4,48,'#f9e3a81c');
        if(i%2===0){line(c,[[x+16,l.y+36],[x+27,l.y+23],[x+42,l.y+34]],j?'#a7785f':'#679587',2);}
      }
      rect(c,54,l.y+64,892,13,'#d2cbb0');rect(c,54,l.y+77,892,9,'#566b65');line(c,[[57,l.y+61],[942,l.y+61]],'#f0d998',3,[6,5]);
      rect(c,52,l.y+87,896,l.h-86,'#243a43');line(c,[[54,l.y+109],[947,l.y+109]],'#91a69d',2);
      for(let i=0;i<24;i++)line(c,[[61+i*38,l.y+108],[71+i*38,l.y+115]],'#475a5b',3);
      text(c,l.name,402,l.y+33,13,'#2f5657','center',650);
    });
    // Link between floors: diagonal escalators with individually moving steps.
    poly(c,[[694,363],[748,363],[630,433],[576,433]],'#263e48');
    for(let i=0;i<15;i++){const k=(i+t*.7)%15,x=694-k*7.8,y=365+k*4.5;line(c,[[x,y],[x+43,y]],'#8baca2',2);}
    line(c,[[695,359],[577,429]],'#c8d2ae',3);line(c,[[748,359],[630,429]],'#c8d2ae',3);
    // A stopping train: action opens doors, boards commuters, then moves out.
    let trainX=125+Math.sin(t*.12)*3,doors=0;
    if(s.elapsed<3){doors=Math.sin(Math.PI*clamp(s.elapsed/2.8,0,1));trainX+=s.elapsed>2.1?Math.pow(s.elapsed-2.1,2)*110:0;}
    trainSide(c,trainX,303,5,'#d8d9bb',t,s,doors);
    const lowerX=-510+(t*29%1600);trainSide(c,lowerX,492,4,'#c9c3a7',t,s,0);
    // People have a route and scale; they are not just a row of identical dots.
    for(let j=0;j<2;j++)for(let i=0;i<13;i++){
      let x=79+((i*67+t*(i%2?3:-3)+870)%870),y=levels[j].y+54;
      if(j===0&&s.elapsed<2.8&&i<5){x=mix(x,trainX+59+i*124,clamp(s.elapsed/2.4,0,1));y+=clamp(s.elapsed/2.4,0,1)*7;}
      person(c,x,y,.8,['#6c8580','#bc8c65','#697d95','#9a7665'][i%4],t*3+i);
    }
    // Visible platform furniture and new shops as ridership grows.
    for(const x of [240,779]){rect(c,x,300,43,5,'#8b6f51',2);line(c,[[x+5,305],[x+5,312]],'#4b6661',2);line(c,[[x+38,305],[x+38,312]],'#4b6661',2);}
    if(count(s)){rect(c,834,259,78,43,'#a58669',4);rect(c,841,268,63,26,'#ecd8a4',3);text(c,'КОФЕ',873,281,10,'#5d6959','center',700);}
    for(let i=0;i<4+Math.min(5,count(s));i++)ellipse(c,91+i*9,283,2,2,'#e6d19d');
    if(pulse(s))glow(c,400,324,200,'#f7df9d'+Math.round(pulse(s)*40).toString(16).padStart(2,'0'));
    text(c,'ГОРОД ПРОДОЛЖАЕТСЯ ПОД ЗЕМЛЁЙ',500,592,11,'#ccc4ad99','center',600);
  }

  const entries=[
    {
      id:'infinity-layers',title:'Слои бесконечности',category:'Миры и системы',
      variants:[
        {name:'Каскад миров',kicker:'МИКРОКОСМОС / ЖИВОЙ ПОТОК',description:'Ресурс становится светящейся рекой: проходит через ледяной исток, выращивает лес и собирает кристаллический мир ниже.',features:['Три различимых экосистемы вместо одинаковых панелей','Поток частиц показывает связь между слоями','Новый импульс оставляет постройки и кристаллы'],action:'Пустить импульс',result:'Поток достиг нижнего мира. Каскад стал сложнее.',accent:'#9edacb',background:'#102432'},
        {name:'Орбитальный сад',kicker:'ГЕНЕРАЦИЯ / ГРАВИТАЦИЯ',description:'Вселенные растут вокруг живого светового ядра. Каждая новая планета занимает собственную орбиту и меняет рисунок созвездия.',features:['Органический центр, планеты и наклонные орбиты','Спокойное вращение с разной скоростью и направлением','Импульс рождения и постоянный новый спутник'],action:'Родить новый мир',result:'В орбитальном саду появилась новая планета.',accent:'#d3e6b4',background:'#142538'}
      ],
      stats(v,s){const n=count(s);return v?[['Миров',String(6+n)],['Резонанс',(38+n*7)+'%'],['Цикл','∞']]:[['Слоёв','3'],['Поток',(128+n*24)+'/с'],['Связей',String(2+n)]];},
      draw(c,t,s,v){c.save();(v?orbitGarden:cascade)(c,t,s);c.restore();}
    },
    {
      id:'alchemist',title:'Алхимик',category:'Миры и системы',
      variants:[
        {name:'Мастерская после полуночи',kicker:'РЕМЕСЛО / ТЁПЛАЯ ФАНТАЗИЯ',description:'Лунное окно, дерево, медь и травы. Варка ощущается физически: зелье дышит, пускает пар и освещает предметы на столе.',features:['Обжитая мастерская со своей глубиной и материалами','Реакция котла: пузырьки, пар и световые искры','Коллекция готовых кристаллов растёт после варки'],action:'Сварить эссенцию',result:'Эссенция готова. На столе вырос новый кристалл.',accent:'#c6d79d',background:'#242d2a'},
        {name:'Стеклянная микролаборатория',kicker:'НАУКА / ОПТИКА ЖИДКОСТЕЙ',description:'Светлая лаборатория крупным планом. Капля идёт по прозрачной системе, жидкость меняет оттенок, а внутри реактора растёт кристалл.',features:['Честно светлый вариант с прозрачными колбами','Капли, уровни жидкости, трубки и охлаждающий змеевик','Новая форма кристалла остаётся в чашке образцов'],action:'Запустить синтез',result:'Реакция завершена. Структура кристалла стала сложнее.',accent:'#8ec6b3',background:'#e0ebe0'}
      ],
      stats(v,s){const n=count(s);return v?[['Образцов',String(5+n)],['Чистота',(92+Math.min(n,7))+'%'],['Раствор','35 мл']]:[['Эссенций',String(4+n)],['Огонь','Тихий'],['Рецепт','Лунный мох']];},
      draw(c,t,s,v){c.save();(v?microLab:workshop)(c,t,s);c.restore();}
    },
    {
      id:'asteroid-colony',title:'Колония на астероиде',category:'Миры и системы',
      variants:[
        {name:'Тихая орбита',kicker:'ИЗОМЕТРИЯ / ЖИВОЕ ПОСЕЛЕНИЕ',description:'Колония как маленькое обитаемое место: жилые модули, прозрачная оранжерея, солнечная ферма, грузовая площадка и работающий ровер.',features:['Гранёный астероид и различимые здания','Энергия, транспорт и растения работают в одном мире','Посадка грузовика открывает новые жилые модули'],action:'Принять грузовик',result:'Груз доставлен. На астероиде появился новый модуль.',accent:'#b9d5b8',background:'#192a33'},
        {name:'Под поверхностью',kicker:'РАЗРЕЗ / ИНДУСТРИАЛЬНАЯ ДИОРАМА',description:'Колония показана в разрезе: наверху жильё и зелень, ниже переработка и вода, внизу добыча. Все уровни соединяет настоящий лифт.',features:['Три разных рабочих уровня и поверхность астероида','Движущаяся руда, переработка воды, люди и конвейер','Доставка поднимается по шахте и заряжает хранилище'],action:'Поднять партию руды',result:'Лифт доставил партию. Энергетическое хранилище пополнено.',accent:'#dbc58e',background:'#29353b'}
      ],
      stats(v,s){const n=count(s);return v?[['Выработка',(42+n*6)+' кВт'],['Цехов','6'],['Доставок',String(n)]]:[['Жителей',String(18+n*3)],['Модулей',String(7+n)],['Автономность',(76+Math.min(n*2,20))+'%']];},
      draw(c,t,s,v){c.save();(v?asteroidSection:asteroidTown)(c,t,s);c.restore();}
    },
    {
      id:'subway',title:'Подземка',category:'Миры и системы',
      variants:[
        {name:'Город растёт',kicker:'ЖИВАЯ КАРТА / НОВЫЕ РАЙОНЫ',description:'У транспорта появляется контекст: река, мосты, парк и кварталы. Поезда видны на маршрутах, а новая ветка физически продолжает город.',features:['География и районы помогают запоминать сеть','Поезда, машины и паром дают карте спокойную жизнь','Новая линия соединяет берег и выращивает квартал'],action:'Открыть новую ветку',result:'Новый квартал подключён. По линии пошли поезда.',accent:'#67a399',background:'#dde7d6'},
        {name:'Город под городом',kicker:'СЕЧЕНИЕ / ЛЮДИ И СТАНЦИИ',description:'Видны улица, вход в метро, эскалаторы и два подземных уровня. Пассажиры становятся героями сцены, а прибытие поезда — маленьким событием.',features:['Два уровня метро связаны с настоящей улицей','Платформы, свет, пассажиры, реклама и поезда','Открываются двери; пассажиры садятся, станция оживает'],action:'Принять поезд',result:'Посадка завершена. На станции стало больше жизни.',accent:'#dbca9c',background:'#3c4849'}
      ],
      stats(v,s){const n=count(s);return v?[['Пассажиров',String(840+n*36)],['Интервал','2 мин'],['Прибытий',String(n)]]:[['Линий',String(3+(n?1:0))],['Поток',(8.4+n*.6).toFixed(1)+' тыс.'],['Охват',(64+Math.min(n*4,32))+'%']];},
      draw(c,t,s,v){c.save();(v?underground:cityMap)(c,t,s);c.restore();}
    }
  ];
  entries.forEach(entry=>window.AxikoVisuals.register(entry));
}());
