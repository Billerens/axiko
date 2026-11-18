// Live browser visibility regression. Run against an isolated QA instance after APK installation.
// Configuration: AXIKO_HUB_SESSION points to JSON {url, code, serial?}, or use AXIKO_HUB_URL + AXIKO_HUB_CODE.
// Optional: AXIKO_PLAYWRIGHT_MODULE (module name or absolute package path), AXIKO_CHROMIUM_EXECUTABLE.
// No ADB operations; the supplied endpoint is the only service accessed. The key is never printed.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict');
function playwright() {
 if(process.env.AXIKO_PLAYWRIGHT_MODULE)return require(process.env.AXIKO_PLAYWRIGHT_MODULE);
 for(const name of ['playwright','playwright-core','@playwright/test']) {try{return require(name);}catch(error){if(error.code!=='MODULE_NOT_FOUND')throw error;}}
 throw Error('Install Playwright or set AXIKO_PLAYWRIGHT_MODULE.');
}
(async()=>{
 const session=process.env.AXIKO_HUB_SESSION?JSON.parse(fs.readFileSync(process.env.AXIKO_HUB_SESSION,'utf8')):{};
 const url=process.env.AXIKO_HUB_URL||session.url, code=process.env.AXIKO_HUB_CODE||session.code;
 if(!url||!code)throw Error('Set AXIKO_HUB_SESSION or both AXIKO_HUB_URL and AXIKO_HUB_CODE for the QA instance.');
 const target=new URL(url);
 assert(['http:','https:'].includes(target.protocol),'The QA endpoint must use HTTP or HTTPS');
 assert(!target.username&&!target.password,'Pass credentials separately from the endpoint URL');
 const cookie=crypto.createHash('sha256').update('axiko:'+code).digest('hex').slice(0,32);
 const {chromium}=playwright();
 const browser=await chromium.launch({headless:true,executablePath:process.env.AXIKO_CHROMIUM_EXECUTABLE||undefined});
 let page;
 try{
  const context=await browser.newContext({viewport:{width:1440,height:920}});
  context.setDefaultTimeout(25000);
  context.setDefaultNavigationTimeout(30000);
  await context.addCookies([{name:'axiko',value:cookie,url:target.origin,httpOnly:true,sameSite:'Lax'}]);
  page=await context.newPage();const errors=[];page.on('pageerror',e=>errors.push(String(e)));
  console.log(JSON.stringify({phase:'open-hub'}));
  await page.goto(target.origin,{waitUntil:'domcontentloaded'});
  console.log(JSON.stringify({phase:'hub-loaded'}));
  await page.waitForFunction(()=>window.axikoDesk&&window.AxikoTransport&&document.querySelector('#connection').dataset.state==='online');
  console.log(JSON.stringify({phase:'transport-online'}));
  const entry=await page.evaluate(async()=>{
   const catalog=await AxikoTransport.call('modules/games/main.js','catalog',{details:false});
   const game=catalog.games?.find(g=>!g.archived)||catalog.games?.[0];
   return {path:game?.entry||'modules/games/ui/index.html',isGame:!!game,id:game?.id||null};
  });
  console.log(JSON.stringify({phase:'catalog',game:entry.id}));
  await page.evaluate(path=>axikoDesk.open(path),entry.path);
  // The old iframe body can remain while the new navigation is pending on a slower device.
  await page.waitForFunction(path=>{
   const frame=document.querySelector('#page');
   return frame?.contentWindow?.location.pathname==='/' + path.split(/[?#]/)[0] && frame.contentDocument?.readyState==='complete';
  },entry.path);
  const frameLocator=page.frameLocator('#page');
  await frameLocator.locator('body').waitFor();
  const gameFrame=page.frames().find(frame=>frame.parentFrame()===page.mainFrame()&&new URL(frame.url()).pathname==='/' + entry.path.split(/[?#]/)[0]);
  assert(gameFrame,'The selected module frame did not load');
  await gameFrame.waitForFunction(()=>window.axiko&&typeof axiko.on==='function');
  await gameFrame.evaluate(()=>{
   window.__qaVisibility=[];
   window.__qaOff=axiko.on('app.visible',event=>__qaVisibility.push({on:event.on,at:performance.now()}));
  });
  await gameFrame.waitForFunction(()=>__qaVisibility.length&&__qaVisibility.at(-1).on===true);
  await page.evaluate(()=>{
   window.__qaServerVisible=[];
   window.__qaTransportOff=AxikoTransport.subscribe(event=>{if(event.event==='app.visible')__qaServerVisible.push(event.data.on);});
  });
  await page.evaluate(()=>AxikoTransport.eval('emit("app.visible", {on:false}); true'));
  await page.waitForFunction(()=>__qaServerVisible.includes(false));
  assert.equal(await gameFrame.evaluate(()=>__qaVisibility.at(-1).on),true,'Phone visibility must not pause browser game');
  await page.evaluate(()=>axikoDesk.stage(false));
  await page.locator('#studio-open').click();
  await gameFrame.waitForFunction(()=>__qaVisibility.at(-1).on===false);
  assert.equal(await page.locator('#page').isVisible(),false);
  await page.locator('[data-studio="preview"]').click();
  await gameFrame.waitForFunction(()=>__qaVisibility.at(-1).on===true);
  assert.equal(await page.locator('#page').isVisible(),true);
  await page.setViewportSize({width:380,height:840});
  await page.locator('#chat-toggle').click();
  await gameFrame.waitForFunction(()=>__qaVisibility.at(-1).on===false);
  assert.equal(await page.locator('#chat-panel').isVisible(),true);
  await page.locator('#chat-close').click();
  await gameFrame.waitForFunction(()=>__qaVisibility.at(-1).on===true);
  assert.equal(await page.locator('#page').isVisible(),true);
  const visibility=await gameFrame.evaluate(()=>__qaVisibility.map(value=>value.on));
  const result={endpoint:target.origin,serial:session.serial||null,game:entry.id,actualGame:entry.isGame,phoneHiddenIgnored:true,studioHides:true,previewShows:true,mobileChatHides:true,mobileCloseShows:true,visibility,errors};
  await page.evaluate(()=>AxikoTransport.eval('emit("app.visible", {on:true}); true'));
  await gameFrame.evaluate(()=>__qaOff());
  await page.evaluate(()=>__qaTransportOff());
  assert.deepEqual(errors,[], 'The browser must not report page errors');
  console.log(JSON.stringify(result));
 }finally{
  if(page&&!page.isClosed()){
   await page.evaluate(()=>window.AxikoTransport?.eval('emit("app.visible", {on:true}); true')).catch(()=>{});
  }
  await browser.close();
 }
})().catch(error=>{console.error(error.message);process.exitCode=1;});

