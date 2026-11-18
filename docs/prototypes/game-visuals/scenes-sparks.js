(() => {
  'use strict';
  const TAU = Math.PI * 2;
  const clamp = n => Math.max(0,Math.min(1,n));
  function circle(c,x,y,r,fill,stroke,width=1) { c.beginPath(); c.arc(x,y,Math.max(0,r),0,TAU); if(fill){c.fillStyle=fill;c.fill();} if(stroke){c.strokeStyle=stroke;c.lineWidth=width;c.stroke();} }
  function line(c,x1,y1,x2,y2,color,width=1){c.beginPath();c.moveTo(x1,y1);c.lineTo(x2,y2);c.strokeStyle=color;c.lineWidth=width;c.stroke();}
  function ellipse(c,x,y,rx,ry,fill,stroke,width=1){c.beginPath();c.ellipse(x,y,rx,ry,0,0,TAU);if(fill){c.fillStyle=fill;c.fill();}if(stroke){c.strokeStyle=stroke;c.lineWidth=width;c.stroke();}}
  function rect(c,x,y,w,h,color,r=0){c.fillStyle=color;c.beginPath();c.roundRect(x,y,w,h,r);c.fill();}
  function polygon(c,points,fill,stroke,width=1){c.beginPath();points.forEach(([x,y],i)=>i?c.lineTo(x,y):c.moveTo(x,y));c.closePath();if(fill){c.fillStyle=fill;c.fill();}if(stroke){c.strokeStyle=stroke;c.lineWidth=width;c.stroke();}}
  function glow(c,x,y,r,color){const g=c.createRadialGradient(x,y,0,x,y,r);g.addColorStop(0,color);g.addColorStop(1,'transparent');circle(c,x,y,r,g);}
  function arc(c,x,y,r,a,b,color,width){c.beginPath();c.arc(x,y,r,a,b);c.strokeStyle=color;c.lineWidth=width;c.stroke();}
  function seeded(i){const x=Math.sin(i*127.1+34.21)*43758.5453;return x-Math.floor(x);}
  function spark(c,cx,cy,a,r,tint,length=32){const x=cx+Math.cos(a)*r,y=cy+Math.sin(a)*r;const tx=x+Math.cos(a)*length,ty=y+Math.sin(a)*length;const g=c.createLinearGradient(x,y,tx,ty);g.addColorStop(0,tint);g.addColorStop(1,'transparent');line(c,x,y,tx,ty,g,3);glow(c,x,y,14,tint+'90');c.save();c.translate(x,y);c.rotate(a);polygon(c,[[-8,0],[2,-3],[7,0],[2,3]],'#ffedcf');c.restore();}
  function bolt(c,x,y,r,color,angle=0){c.save();c.translate(x,y);c.rotate(angle);circle(c,0,0,r,'#2b3d40',color,1.5);line(c,-r*.5,0,r*.5,0,color,1);c.restore();}
  function crystal(c,x,y,size,t,color1,color2){c.save();c.translate(x,y);c.rotate(Math.sin(t*.45)*.12);polygon(c,[[0,-size],[-size*.63,-size*.1],[-size*.36,size*.63],[0,size],[size*.6,size*.1],[size*.32,-size*.65]],color1,color2,2);polygon(c,[[0,-size],[0,size],[-size*.63,-size*.1]],color2);polygon(c,[[0,-size],[size*.32,-size*.65],[size*.6,size*.1],[0,0]],'#fff2d1');line(c,0,0,size*.6,size*.1,'#ffd0a0',1);c.restore();}
  function pulse(c,x,y,r,state,color){
    if(state.elapsed>2.4)return;
    const p=clamp(state.elapsed/1.8),strength=(1-p);
    c.save();c.globalAlpha=strength;
    circle(c,x,y,r+p*140,null,color,4*strength+1);
    for(let i=0;i<24;i++){const a=i*TAU/24+.1;const rr=r+8+p*(50+seeded(i)*95);line(c,x+Math.cos(a)*rr,y+Math.sin(a)*rr,x+Math.cos(a)*(rr+12),y+Math.sin(a)*(rr+12),color,1+seeded(i)*2);}
    c.restore();
  }
  function orbit(c,t,s){
    const sky=c.createLinearGradient(0,0,1000,620);sky.addColorStop(0,'#111e30');sky.addColorStop(.55,'#07131f');sky.addColorStop(1,'#102d39');rect(c,0,0,1000,620,sky);
    glow(c,175,170,330,'#28515a40');glow(c,800,160,340,'#813b292c');
    for(let i=0;i<150;i++){const x=seeded(i)*1000,y=seeded(i+270)*550;const flicker=.25+Math.sin(t*.4+i)*.12;circle(c,x,y,seeded(i+90)*1.4+.25,`rgba(206,227,226,${flicker})`);}
    c.save();c.translate(505,295);c.rotate(-.3);ellipse(c,0,0,385,175,null,'#335764',1);ellipse(c,0,0,350,155,null,'#224351',1);c.restore();
    c.save();c.translate(505,295);c.rotate(.35);ellipse(c,0,0,358,142,null,'#263d4a',1);c.restore();
    // A far-off planet and its crescent establish scale, without obscuring timing lanes.
    circle(c,140,120,40,'#263842');circle(c,154,111,38,'#10202e');arc(c,140,120,40,1.45,3.7,'#7ba4a4',1.2);
    const planet=c.createRadialGradient(500,995,200,500,995,490);planet.addColorStop(0,'#223945');planet.addColorStop(.85,'#203e49');planet.addColorStop(.97,'#4e8e8e');planet.addColorStop(1,'#162d38');circle(c,500,995,488,planet);arc(c,500,995,490,3.9,5.52,'#6bb8b7',2);glow(c,500,518,175,'#71c0b216');
    for(let i=0;i<9;i++){const x=50+i*118,y=510+Math.sin(i*4)*30;polygon(c,[[x,y],[x+20,y-12],[x+48,y-4],[x+58,y+15],[x+27,y+24]],i%2?'#182a35':'#243943','#304652',1);polygon(c,[[x,y],[x+20,y-12],[x+27,y+24]],'#2e444d');}
    const cx=500,cy=285,r=166;
    glow(c,cx,cy,250,'#c9663020');
    for(let i=0;i<12;i++){
      const a=i*TAU/12;
      arc(c,cx,cy,r+13,a+.035,a+.42,'#263d46',19);
      arc(c,cx,cy,r+13,a+.06,a+.39,'#8a7557',3);
      c.save();c.translate(cx+Math.cos(a)*(r+18),cy+Math.sin(a)*(r+18));c.rotate(a);polygon(c,[[0,-7],[23,-5],[31,0],[23,5],[0,7]],'#314c54','#839388',1);bolt(c,9,0,3,'#b5aa81');c.restore();
    }
    circle(c,cx,cy,r,null,'#edae72',3);circle(c,cx,cy,r-10,null,'#ddab7770',1);circle(c,cx,cy,r+42,null,'#31535d',1);
    for(let i=0;i<60;i++){const a=i*TAU/60;line(c,cx+Math.cos(a)*(r+39),cy+Math.sin(a)*(r+39),cx+Math.cos(a)*(r+(i%5?44:50)),cy+Math.sin(a)*(r+(i%5?44:50)),i%5?'#45616a':'#92a8a4',1);}
    circle(c,cx,cy,95,'#081a25','#315563',1);
    c.save();c.translate(cx,cy);c.rotate(t*.07);for(let i=0;i<6;i++){const a=i*TAU/6;c.save();c.rotate(a);polygon(c,[[0,-95],[-12,-72],[0,-52],[12,-72]],i<3+Math.min(s.count,3)?'#b78158':'#254550','#46707a',1);c.restore();}c.restore();
    glow(c,cx,cy,110,'#f1a45f35');crystal(c,cx,cy,44+Math.sin(t)*2,t,'#c56f38','#f7b977');
    for(let i=0;i<6;i++){const a=i*1.047+t*.05+.4;const travel=(t*.12+i*.168)%1;const radius=360-travel*192;spark(c,cx,cy,a,radius,'#f7ab6a',25+travel*30);}
    // Outgoing decoys deliberately use a different direction, weight and hue.
    for(let i=0;i<3;i++){const a=1.3+i*2.05;const rr=90+((t*.09+i*.33)%1)*235;line(c,cx+Math.cos(a)*rr,cy+Math.sin(a)*rr,cx+Math.cos(a)*(rr-13),cy+Math.sin(a)*(rr-13),'#83c1c391',1.5);}
    pulse(c,cx,cy,r,s,'#ffcb8d');
    for(let i=0;i<6;i++){const a=-Math.PI/2+i*TAU/6;circle(c,cx+Math.cos(a)*230,cy+Math.sin(a)*230,3,i<Math.min(6,s.count)?'#ffda98':'#476c77');}
  }
  function pipe(c,points,width,color){c.lineJoin='round';c.lineCap='round';c.beginPath();points.forEach(([x,y],i)=>i?c.lineTo(x,y):c.moveTo(x,y));c.strokeStyle='#0b191e';c.lineWidth=width+7;c.stroke();c.strokeStyle=color;c.lineWidth=width;c.stroke();c.strokeStyle='#ffffff18';c.lineWidth=2;c.stroke();c.lineCap='butt';}
  function reactor(c,t,s){
    const wall=c.createLinearGradient(0,0,0,620);wall.addColorStop(0,'#24454b');wall.addColorStop(.7,'#152f36');wall.addColorStop(1,'#0e2028');rect(c,0,0,1000,620,wall);
    for(let i=0;i<7;i++){rect(c,i*164-20,0,156,485,i%2?'#25464a':'#294a4d',7);line(c,i*164+1,30,i*164+1,475,'#59726b',1);for(let y=25;y<480;y+=112)bolt(c,i*164+133,y,4,'#718076');}
    rect(c,0,480,1000,140,'#10252b');for(let i=0;i<12;i++)line(c,-150+i*140,620,200+i*66,480,'#314a4b',1);line(c,0,525,1000,525,'#314a4b',1);line(c,0,580,1000,580,'#314a4b',1);
    pipe(c,[[0,83],[260,83],[260,124],[435,124]],21,'#9a7450');pipe(c,[[1000,65],[827,65],[827,415],[720,415]],24,'#bd8c58');pipe(c,[[75,620],[75,438],[315,438],[315,360]],16,'#66887a');
    for(let i=0;i<3;i++){rect(c,26+i*30,71,12,25,'#d4ae70',2);rect(c,816,110+i*29,24,10,'#e0b579',2);}
    ellipse(c,550,523,245,34,'#06171d9c');
    // A compact bank of analogue instruments, machinery and glass tubes.
    rect(c,90,176,185,239,'#122a31',10);rect(c,100,185,165,215,'#355457',6);for(let x=117;x<258;x+=126)for(let y=200;y<394;y+=177)bolt(c,x,y,5,'#9fa389');
    for(let i=0;i<2;i++){const x=144+i*77;circle(c,x,241,29,'#b4baa1','#182f35',7);for(let j=0;j<7;j++){const a=Math.PI+j*Math.PI/6;line(c,x+Math.cos(a)*19,241+Math.sin(a)*19,x+Math.cos(a)*23,241+Math.sin(a)*23,'#465c58',1.5);}const a=3.5+(Math.sin(t*.7+i)*.12)+Math.min(s.count,8)*.07;line(c,x,241,x+Math.cos(a)*19,241+Math.sin(a)*19,'#9c5039',2);circle(c,x,241,3,'#3c5c58');}
    for(let i=0;i<6;i++){rect(c,120,300+i*11,65,4,'#173337',1);circle(c,216,304+i*14,3,i<=Math.min(s.count,5)?'#e9cb7a':'#45655c');}
    rect(c,900,180,75,278,'#203a40',12);for(let i=0;i<9;i++){rect(c,888,204+i*24,92,11,'#486b68',5);line(c,896,207+i*24,966,207+i*24,'#82a18b',2);}
    const cx=548,cy=287;
    polygon(c,[[365,408],[393,520],[429,520],[425,415]],'#7c8b78','#a8b298',2);polygon(c,[[678,408],[672,520],[708,520],[733,395]],'#5f786e','#a8b298',2);
    circle(c,cx,cy,219,'#10262c','#779584',3);circle(c,cx,cy,207,'#657b6d','#253b3b',12);circle(c,cx,cy,187,'#102930','#c8a771',9);
    const lens=c.createRadialGradient(cx-50,cy-60,10,cx,cy,181);lens.addColorStop(0,'#294f51');lens.addColorStop(.65,'#10393f');lens.addColorStop(1,'#071f28');circle(c,cx,cy,178,lens);
    for(let i=0;i<16;i++){const a=i*TAU/16;bolt(c,cx+Math.cos(a)*207,cy+Math.sin(a)*207,6,'#d0be91',a);}
    circle(c,cx,cy,146,null,'#79d6b5',3);circle(c,cx,cy,153,null,'#426e68',1);
    for(let i=0;i<8;i++){const a=i*TAU/8+t*.04;arc(c,cx,cy,124,a,a+.48,'#5a8f7e',2);}
    glow(c,cx,cy,120,'#83f2b82e');
    c.save();c.translate(cx,cy);c.rotate(-t*.09);for(let i=0;i<4;i++){c.rotate(Math.PI/2);polygon(c,[[-12,-66],[-25,-91],[25,-91],[12,-66]],'#587566','#a1ae88',2);}c.restore();
    crystal(c,cx,cy,42+Math.sin(t)*2,t,'#448f79','#b5e9aa');
    for(let i=0;i<4;i++){const a=i*1.57+.5;const rr=175-((t*.2+i*.25)%1)*90;spark(c,cx,cy,a,rr,'#a4f6bd',16);}
    pulse(c,cx,cy,145,s,'#c7f6bf');
    // Reflected light and a moving scanning line give the protective glass weight.
    arc(c,cx,cy,168,3.6,4.7,'#bfe1cc45',7);arc(c,cx,cy,164,.7,1.2,'#a8d3bb23',3);
    rect(c,409,45,272,28,'#142f35',4);for(let i=0;i<18;i++){rect(c,422+i*14,52,7,13,i<6+Math.min(s.count,12)?'#b0d09a':'#35514c',1);}
    for(let i=0;i<2;i++){rect(c,335+i*419,521,60,18,'#688574',3);circle(c,365+i*419,542,20,'#142e34','#7a9481',4);}
    const mist=(t*12)%110;for(let i=0;i<7;i++){const y=460-i*22-mist*.2;ellipse(c,846+Math.sin(t*.4+i)*9,y,14+i*3,7+i*2,`rgba(152,190,163,${.08-i*.008})`);}
    glow(c,550,289,290,'#80d5a20b');
  }
  window.AxikoVisuals.register({
    id:'sparks',title:'Искры в кольце',category:'Реакция · концентрация',
    variants:[
      {name:'Орбитальный резонатор',kicker:'Тишина между импульсами',description:'Кольцо становится прибором на орбите планеты. Навстречу летят горячие частицы, а отражение расходится световой волной по металлическим сегментам.',features:['Входящие искры — тёплые кометы со следом. Ложные выходящие — тонкие холодные штрихи.','Успешное отражение видно как направленный выброс частиц и расширяющуюся волну.','Серия попаданий зажигает сегменты резонатора; дальняя орбита остаётся спокойной.'],action:'Отразить искру',result:'Импульс отражён. Резонатор набирает энергию.',accent:'#efb77a',background:'#111f2b'},
      {name:'Сердце машины',kicker:'Тактильный ретрофутуризм',description:'Игра внутри стеклянного реактора: медные трубы, стрелочные приборы и живой кристалл. Каждое отражение ощутимо питает большую машину.',features:['Прозрачная камера задаёт границу игры, а приборы и медные трубы объясняют окружение.','Отражение отдаёт светом в кристалл. Свечение сохраняет читаемость рабочей зоны.','Успешные импульсы двигают стрелки и последовательно зажигают шкалу над реактором.'],action:'Дать импульс',result:'Энергия принята. Следующая секция подключена.',accent:'#b9d79b',background:'#1d3b40'}
    ],
    stats(v,s){return [['Серия',String(12+s.count)],['Точность',s.count?'98%':'96%'],[v?'Мощность':'Резонанс',String(48+s.count*4)+'%']];},
    draw(c,t,s,v){c.save();(v?reactor:orbit)(c,t,s);c.restore();}
  });
})();
