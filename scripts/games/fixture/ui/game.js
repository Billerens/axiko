// The screen of «Шахта» (the fixture of the node tests): a pick, carts, a descent.
(async function () {
  const h = ui.h;
  const sim = window.GameSim;
  const view = {};
  let g = null;

  function build(game) {
    g = game;
    view.coins = h('div', { class: 'g-big', id: 'coins' });
    view.rate = h('div', { class: 'g-rate', id: 'rate' });
    g.el.top.append(view.coins, view.rate);
    view.carts = h('div', { class: 'g-note', id: 'carts' });
    g.el.field.append(view.carts);
    view.dig = h('button', { class: 'g-btn', id: 'dig', onclick: dig }, GameIcons.el('pick'), 'Копать');
    view.buy = h('button', { class: 'g-btn primary', id: 'buy', onclick: buy });
    view.prestige = g.ui.prestigeButton(sim.LAYER);
    g.el.actions.append(view.dig, view.buy, view.prestige);
  }

  function dig() {
    g.act(state => sim.dig(state, g.config, g.links.mult()));
    g.sfx('tap');
    g.buzz('tap');
  }

  function buy() {
    if (!g.act(state => sim.buy(state, g.config))) {
      g.sfx('deny');
      g.buzz('deny');
      return;
    }
    g.sfx('buy');
    if (g.state.run.carts === 1) g.achieve('first-cart');
  }

  function render(state) {
    const cfg = g.config;
    view.coins.textContent = g.fmt.live(state.run.coins);
    view.rate.textContent = g.fmt.rate(Big.mul(sim.income(state, cfg), g.links.mult()));
    view.carts.textContent = 'Вагонеток: ' + state.run.carts;
    const price = sim.cost(state, cfg);
    view.buy.textContent = 'Вагонетка — ' + g.fmt.price(price);
    view.buy.disabled = Big.lt(state.run.coins, price);
    if (Big.gte(state.run.mined, cfg.rich)) g.achieve('rich');
  }

  await Game.start({ sim, render, init: build });
})();
