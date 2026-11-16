(() => {
  'use strict';
  const order = ['dynasty','library','infinity-layers','alchemist','menagerie','tower','one-cell-dungeon','garden','asteroid-colony','dragon-hoard','subway','wave-survival','sparks'];
  const $ = id => document.getElementById(id);
  const games = order.map(id => window.AssiVisuals.games.get(id)).filter(Boolean);
  const states = new Map();
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)');
  let current = games[0], variant = 0, mobile = false, overview = false, paused = reduced.matches;
  let pendingTabFocus = null;
  let time = 4, previousFrame = 0, frameRequest = 0, overviewBuilt = false, sceneVisible = true, choices = {};
  try { choices = JSON.parse(localStorage.getItem('assi-visual-directions') || '{}') || {}; } catch (_) {}
  const canvas = $('scene'), ctx = canvas.getContext('2d', {alpha:false});
  function element(tag, className, text) { const e = document.createElement(tag); if (className) e.className = className; if (text != null) e.textContent = text; return e; }
  function stateFor(game = current, v = variant) {
    const key = game.id + '/' + v;
    if (!states.has(key)) states.set(key, {count:0, at:-999});
    const stored = states.get(key), elapsed = Math.max(0, time - stored.at);
    return {count:stored.count, elapsed, effect:Math.max(0, 1-elapsed/2.4)};
  }
  function draw(game, v, context, width, height, at, state) {
    context.save();
    context.setTransform(width / 1000, 0, 0, height / 620, 0, 0);
    context.clearRect(0,0,1000,620);
    game.draw(context, at, state, v);
    context.restore();
  }
  function drawCurrent() {
    if (!current || overview) return;
    draw(current, variant, ctx, canvas.width, canvas.height, time, stateFor());
  }
  function resize() {
    const width = canvas.getBoundingClientRect().width;
    if (!width) return;
    const ratio = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = Math.max(1, Math.round(width * ratio));
    canvas.height = Math.round(canvas.width * .62);
    drawCurrent();
  }
  function stop() { cancelAnimationFrame(frameRequest); frameRequest = 0; previousFrame = 0; }
  function loop(stamp) {
    frameRequest = 0;
    if (paused || overview || document.hidden || !sceneVisible) return;
    if (!previousFrame) previousFrame = stamp;
    const elapsed = stamp - previousFrame;
    if (elapsed >= 1000/30) {
      time += Math.min(elapsed / 1000, .07);
      previousFrame = stamp;
      drawCurrent();
    }
    frameRequest = requestAnimationFrame(loop);
  }
  function start() { if (!frameRequest && !paused && !overview && !document.hidden && sceneVisible) frameRequest = requestAnimationFrame(loop); }
  function nav() {
    const focusedGame = document.activeElement?.closest('.game-link')?.dataset.game;
    const fragment = document.createDocumentFragment();
    games.forEach((game, i) => {
      const a = element('a', 'game-link'); a.href = '#' + game.id + '/a'; a.dataset.game = game.id;
      if (!overview && game === current) a.setAttribute('aria-current','page');
      a.append(element('span', 'nav-index', String(i+1).padStart(2,'0')), element('span', '', game.title));
      if (choices[game.id] === 0 || choices[game.id] === 1) { const check = element('span','nav-check','✓'); check.setAttribute('aria-label','Направление выбрано'); a.append(check); }
      fragment.append(a);
    });
    $('games-nav').replaceChildren(fragment);
    if (focusedGame) $('games-nav').querySelector('[data-game="' + focusedGame + '"]')?.focus({preventScroll:true});
    if (window.innerWidth <= 760) {
      const active = $('games-nav').querySelector('[aria-current=page]');
      if (active) $('games-nav').scrollLeft = Math.max(0, active.offsetLeft - $('games-nav').offsetLeft - 35);
    }
  }
  function stats() {
    const rows = current.stats ? current.stats(variant, stateFor()) : [];
    $('game-stats').replaceChildren(...rows.map(([label,value]) => {
      const el = element('div','stat'); el.append(element('div','stat-label',label),element('div',String(value).length > 6 ? 'stat-value long-value' : 'stat-value',value)); return el;
    }));
  }
  function updateFavorite() {
    const chosen = choices[current.id] === variant;
    $('favorite-button').setAttribute('aria-pressed', String(chosen));
    $('favorite-button').textContent = chosen ? '♥ Направление выбрано' : '♡ Выбрать направление';
  }
  function updateMotionButton() {
    $('motion-button').setAttribute('aria-pressed',String(paused));
    $('motion-button').replaceChildren(document.createTextNode(paused ? '▷ ' : 'Ⅱ '), element('span','',paused ? 'Оживить' : 'Пауза'));
    $('motion-button').title = paused ? 'Включить движение' : 'Остановить движение';
  }
  function renderDetail() {
    stop(); overview = false;
    $('detail-view').hidden = false; $('overview-view').hidden = true;
    const design = current.variants[variant], index = games.indexOf(current);
    $('game-title').textContent = current.title;
    $('game-category').textContent = current.category || 'Игровой мир';
    $('page-number').textContent = String(index+1).padStart(2,'0') + ' / 13';
    $('frame-title').textContent = current.title;
    $('game-frame').style.setProperty('--scene-accent',design.accent);
    $('game-frame').style.setProperty('--scene-bg',design.background);
    $('game-frame').setAttribute('aria-labelledby', variant === 0 ? 'variant-a' : 'variant-b');
    $('concept-kicker').textContent = design.kicker;
    $('concept-title').textContent = design.name;
    $('concept-description').textContent = design.description;
    $('concept-features').replaceChildren(...design.features.map(text => element('li','',text)));
    $('scene-caption').textContent = design.kicker;
    canvas.setAttribute('aria-label',current.title + ': ' + design.name + '. ' + design.description);
    canvas.textContent = design.description;
    $('action-button').textContent = design.action;
    $('action-result').textContent = stateFor().count ? design.result : 'Сцена откликнется на ваше действие.';
    $('variant-tabs').replaceChildren(...current.variants.map((v,i) => {
      const button = element('button','variant-tab'); button.id = i === 0 ? 'variant-a' : 'variant-b';
      button.setAttribute('role','tab'); button.setAttribute('aria-selected',String(i === variant));
      button.setAttribute('aria-controls','game-frame'); button.tabIndex = i === variant ? 0 : -1;
      button.append(element('span','',i === 0 ? 'A' : 'B'),document.createTextNode(v.name));
      button.addEventListener('click',() => { if (i !== variant) pendingTabFocus = i; location.hash = current.id + '/' + (i ? 'b':'a'); });
      button.addEventListener('keydown',event => {
        if (['ArrowLeft','ArrowRight','Home','End'].includes(event.key)) {
          event.preventDefault(); const next = event.key === 'Home' ? 0 : event.key === 'End' ? 1 : 1-variant;
          if (next !== variant) pendingTabFocus = next;
          location.hash = current.id + '/' + (next ? 'b':'a');
        }
      });
      return button;
    }));
    document.title = current.title + ' — Миры Assi';
    $('overview-button').innerHTML = 'Все концепции <span aria-hidden="true">↗</span>';
    nav(); stats(); updateFavorite(); updateMotionButton(); resize(); start();
    if (pendingTabFocus !== null) { $(pendingTabFocus ? 'variant-b':'variant-a').focus({preventScroll:true}); pendingTabFocus = null; }
  }
  function buildOverview() {
    if (overviewBuilt) return;
    const fragment = document.createDocumentFragment();
    games.forEach((game,index) => {
      const section = element('section','overview-game'), heading = element('div','overview-game-heading');
      heading.append(element('span','',String(index+1).padStart(2,'0')), element('h2','',game.title));
      const pair = element('div','overview-pair');
      game.variants.forEach((design,v) => {
        const button = element('button','overview-card'); button.dataset.choice = game.id + '/' + v;
        button.setAttribute('aria-label',game.title + ': ' + design.name + ', открыть макет');
        const thumb = element('canvas'); thumb.width = 600; thumb.height = 372; thumb.setAttribute('aria-hidden','true');
        draw(game,v,thumb.getContext('2d',{alpha:false}),600,372,4,{count:0,elapsed:999,effect:0});
        const caption = element('div','overview-caption');
        caption.append(element('b','',v ? 'B':'A'),element('span','',design.name),element('span','choice-mark',''));
        button.append(thumb,caption);
        button.addEventListener('click',() => { location.hash = game.id + '/' + (v ? 'b':'a'); window.scrollTo({top:0,behavior:'instant'}); });
        pair.append(button);
      });
      section.append(heading,pair); fragment.append(section);
    });
    $('overview-grid').replaceChildren(fragment); overviewBuilt = true;
  }
  function renderOverview() {
    stop(); overview = true;
    $('detail-view').hidden = true; $('overview-view').hidden = false;
    buildOverview();
    document.querySelectorAll('[data-choice]').forEach(card => { const [id,v] = card.dataset.choice.split('/'); card.querySelector('.choice-mark').textContent = choices[id] === Number(v) ? '♥' : '↗'; });
    $('overview-button').innerHTML = 'Вернуться к сцене <span aria-hidden="true">↙</span>';
    document.title = '26 визуальных концепций — Миры Assi'; nav();
  }
  function route() {
    const hash = location.hash.slice(1);
    if (hash === 'workspace') { renderDetail(); $('workspace').focus(); return; }
    if (hash === 'all') return renderOverview();
    const [id,design] = hash.split('/');
    current = games.find(game => game.id === id) || games[0]; variant = design === 'b' ? 1 : 0;
    renderDetail();
  }
  $('action-button').addEventListener('click', () => {
    const key = current.id + '/' + variant, stored = states.get(key);
    stored.count++; stored.at = time - (paused ? 1 : 0);
    $('action-result').textContent = current.variants[variant].result + (stored.count > 1 ? ' ×' + stored.count : '');
    stats(); drawCurrent();
  });
  $('reset-button').addEventListener('click',() => { states.delete(current.id + '/' + variant); stats(); $('action-result').textContent = 'Сцена откликнется на ваше действие.'; drawCurrent(); });
  $('motion-button').addEventListener('click',() => { paused = !paused; updateMotionButton(); if (paused) stop(); else start(); });
  function setMobile(value) {
    mobile = value; $('game-frame').classList.toggle('mobile',mobile);
    $('mobile-button').setAttribute('aria-pressed',String(mobile)); $('wide-button').setAttribute('aria-pressed',String(!mobile));
    $('mobile-button').classList.toggle('selected',mobile); $('wide-button').classList.toggle('selected',!mobile);
    $('size-caption').textContent = mobile ? 'Мобильный игровой экран · до 390 px' : 'Широкий игровой экран'; resize();
  }
  $('mobile-button').addEventListener('click',() => setMobile(true));
  $('wide-button').addEventListener('click',() => setMobile(false));
  $('favorite-button').addEventListener('click',() => {
    if (choices[current.id] === variant) delete choices[current.id]; else choices[current.id] = variant;
    try { localStorage.setItem('assi-visual-directions', JSON.stringify(choices)); } catch (_) {}
    updateFavorite(); nav();
  });
  $('next-button').addEventListener('click',() => { location.hash = games[(games.indexOf(current)+1)%games.length].id + '/a'; });
  $('overview-button').addEventListener('click',() => { location.hash = overview ? current.id + '/' + (variant ? 'b':'a') : 'all'; });
  document.querySelector('.skip').addEventListener('click', event => { event.preventDefault(); $('workspace').focus(); $('workspace').scrollIntoView({block:'start'}); });
  window.addEventListener('hashchange',route);
  document.addEventListener('visibilitychange',() => { if (document.hidden) stop(); else start(); });
  reduced.addEventListener('change',event => { if (event.matches) { paused = true; stop(); updateMotionButton(); } });
  new ResizeObserver(resize).observe(canvas);
  new IntersectionObserver(entries => { sceneVisible = entries[0].isIntersecting; if (sceneVisible) start(); else stop(); }).observe(canvas);
  route();
})();
