// The screen of «Искры»: what is drawn and what the buttons do. The engine (Game.start) gives the
// frame — g.el.top (numbers), g.el.field, g.el.actions (under the thumb) — and keeps the state,
// saves, offline, prestige, sound. The logic is in ../sim.js; numbers come from g.config.
(async function () {
  const h = ui.h;
  const sim = window.GameSim;
  const view = {};
  let g = null;
  let tips = [];
  let tip = null;

  function build(game) {
    g = game;
    view.sparks = h('div', { class: 'g-big', id: 'sparks' });
    view.rate = h('div', { class: 'g-rate', id: 'rate' });
    g.el.top.append(view.sparks, view.rate);

    view.forges = h('div', { class: 't-forges', id: 'forges' });
    view.embers = h('div', { class: 'g-note', id: 'embers' });
    view.tip = h('div', { class: 't-tip', id: 'tip' });
    g.el.field.append(view.forges, view.embers, view.tip);

    view.hammer = h('button', { class: 'g-btn', id: 'hammer', onclick: strike }, GameIcons.el('hand'), 'Ударить молотом');
    view.buyLabel = h('span', {});
    view.buyPrice = h('span', { class: 'g-sub g-num' });
    view.buy = h('button', { class: 'g-btn primary', id: 'buy', onclick: buy }, h('span', {}, view.buyLabel, view.buyPrice));
    view.boost = h('button', { class: 'g-btn small', id: 'boost', onclick: boost });
    view.prestige = g.ui.prestigeButton(sim.LAYER);
    view.prestige.id = 'prestige';
    g.el.actions.append(view.hammer, view.buy, h('div', { class: 'g-btns' }, view.boost, view.prestige));

    g.on('chest', () => g.sfx('chest'));
    g.on('prestige', () => { tip = null; });
    fetch('../content/tips.json').then(r => r.json()).then(list => { tips = list; }).catch(() => {});
  }

  function strike() {
    g.act(state => sim.hammer(state, g.config, g.links.mult()));
    g.sfx('tap');
    g.buzz('tap');
  }

  function buy() {
    const bought = g.act(state => sim.buy(state, g.config));
    if (!bought) {
      g.sfx('deny');
      g.buzz('deny');
      return;
    }
    g.sfx('buy');
    g.buzz('buy');
    if (g.state.run.forges === 1) g.achieve('first-forge');
    if (g.state.run.forges % 10 === 0) newTip();
  }

  async function boost() {
    const r = await g.links.spend('boost');
    if (!r.ok) {
      ui.toast(r.off ? 'Связка «Шаги» выключена' : 'Нужна ' + r.need + ' энергия — это ~' + g.fmt(r.need * g.config['steps.perSteps']) + ' шагов');
      g.buzz('deny');
      return;
    }
    g.act(state => { state.run.boostUntil = Math.max(Date.now(), state.run.boostUntil) + g.config['boost.minutes'] * 60000; }, { now: true, reason: 'spend' });
    g.sfx('level');
  }

  async function newTip() {
    // The stock prepared while there was network; without it — the built-in or a made-up one.
    tip = await g.stock.take('tips');
    if (!tip && tips.length) tip = tips[Math.floor(Math.random() * tips.length)];
    g.redraw();
  }

  function render(state) {
    const cfg = g.config;
    const rate = Big.mul(sim.income(state, cfg), g.links.mult() * (state.run.boostUntil > Date.now() ? cfg['boost.mult'] : 1));
    view.sparks.textContent = g.fmt.live(state.run.sparks);
    view.rate.textContent = g.fmt.rate(rate);
    view.forges.textContent = state.run.forges ? 'Горнов: ' + state.run.forges : 'Горнов пока нет — ударьте молотом и купите первый';
    const embers = Big.of(state.layers[sim.LAYER].currency, 0);
    view.embers.textContent = Big.isZero(embers) ? '' : 'Уголь: ' + g.fmt(embers) + ', доход ' + g.fmt.mult(sim.bonus(state, sim.LAYER, embers, cfg));
    view.tip.replaceChildren(...(tip ? [h('div', { class: 't-tip-title' }, tip.title), h('div', { class: 'g-note' }, tip.text)] : []));
    const price = sim.cost(state, cfg);
    view.buyLabel.textContent = 'Горн №' + (state.run.forges + 1);
    view.buyPrice.textContent = g.fmt.price(price) + ' искр';
    view.buy.disabled = Big.lt(state.run.sparks, price);
    const left = Math.max(0, Math.ceil((state.run.boostUntil - Date.now()) / 60000));
    view.boost.textContent = left ? 'Меха: ещё ' + left + ' мин' : 'Раздуть меха';
    view.boost.hidden = !(g.links.status().steps && g.links.status().steps.on);
    if (Big.gte(state.run.earned, cfg['million'])) g.achieve('million');
  }

  await Game.start({ sim, render, init: build });
})();
