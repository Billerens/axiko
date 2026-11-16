// Line icons for games, in the kit's style (24×24, stroke only, no fill): window.GameIcons.
//   GameIcons.el('coin')          an <svg class="i"> element (a kit icon when the name is the kit's)
//   GameIcons.svg('coin')         the markup
//   GameIcons.names               the names here
// A game may add its own: GameIcons.add('mushroom', '<path d="…"/>').
(function () {
  var ICONS = {
    coin: '<circle cx="12" cy="12" r="8"/><path d="M9.5 9.5h4a1.5 1.5 0 0 1 0 3h-3a1.5 1.5 0 0 0 0 3h4M12 7.5v1.5M12 15.5V17"/>',
    gem: '<path d="M6 4h12l3 5-9 11L3 9z"/><path d="M3 9h18M9 4l3 5 3-5M12 20l-3-11M12 20l3-11"/>',
    gear: '<circle cx="12" cy="12" r="3"/><path d="M12 2.5v3M12 18.5v3M4.2 6.5l2.6 1.5M17.2 16l2.6 1.5M4.2 17.5l2.6-1.5M17.2 8l2.6-1.5"/><circle cx="12" cy="12" r="6.5"/>',
    chest: '<path d="M3 10h18v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z"/><path d="M3 10V8a4 4 0 0 1 4-4h10a4 4 0 0 1 4 4v2"/><path d="M10.5 10v3h3v-3"/>',
    trophy: '<path d="M8 4h8v5a4 4 0 0 1-8 0z"/><path d="M8 6H5a3 3 0 0 0 3 4M16 6h3a3 3 0 0 1-3 4M12 13v4M8.5 20h7M9.5 17h5"/>',
    train: '<rect x="6" y="3" width="12" height="14" rx="3"/><path d="M6 11h12M9.5 14h.01M14.5 14h.01M8 21l2-4M16 21l-2-4"/>',
    steps: '<path d="M7 15c-1.5 0-2.5-1.5-2.5-4S5.5 5 7.5 5 10 7 10 9.5 9 15 7 15zM6 18.5a1.5 1.5 0 0 0 3 0V17H6z"/><path d="M17 11c1.5 0 2.5-1.5 2.5-4S18.5 1 16.5 1 14 3 14 5.5 15 11 17 11zM15 14.5a1.5 1.5 0 0 0 3 0V13h-3z"/>',
    dice: '<rect x="4" y="4" width="16" height="16" rx="3"/><path d="M8.5 8.5h.01M15.5 15.5h.01M12 12h.01M15.5 8.5h.01M8.5 15.5h.01"/>',
    crown: '<path d="M3 8l4 4 5-7 5 7 4-4-2 11H5z"/><path d="M5 19h14"/>',
    sword: '<path d="M14.5 3H21v6.5L10 20.5 3.5 14z"/><path d="M6 17l-3 3M8.5 12.5l3 3"/>',
    shield: '<path d="M12 3l8 3v6c0 4.5-3.4 8-8 9-4.6-1-8-4.5-8-9V6z"/>',
    pick: '<path d="M4 20l9-9M14 4c3 0 6 3 6 6M9 5c2.5-1.5 7-1 10 2s3.5 7.5 2 10"/>',
    leaf: '<path d="M5 19c0-8 5-14 15-14 0 10-6 15-14 15"/><path d="M5 19c3-4 6-7 10-9"/>',
    flask: '<path d="M9 3h6M10 3v6L4.5 18.5A1.6 1.6 0 0 0 6 21h12a1.6 1.6 0 0 0 1.5-2.5L14 9V3"/><path d="M7 15h10"/>',
    map: '<path d="M3 6l6-2 6 2 6-2v14l-6 2-6-2-6 2z"/><path d="M9 4v14M15 6v14"/>',
    timer: '<circle cx="12" cy="13" r="8"/><path d="M12 9v4l2.5 2M9.5 2h5"/>',
    sparkle: '<path d="M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5L18 18M6 18l2.5-2.5M15.5 8.5L18 6"/>',
    energy: '<path d="M13 2L4 14h7l-1 8 9-12h-7z"/>',
    rocket: '<path d="M12 15l-3-3c1-5 4-8.5 10-9-.5 6-4 9-9 10z"/><path d="M9 12l-4 1-1 3 4-1M12 15l-1 4 3-1 1-4M15 8.5h.01"/>',
    factory: '<path d="M3 21V10l6 4V10l6 4V4h4v17z"/><path d="M7 17h2M12 17h2"/>',
    tree: '<path d="M12 3l6 8h-3l4 6H5l4-6H6z"/><path d="M12 17v4"/>',
    fish: '<path d="M3 12c3-5 9-6 13-3l5-3v12l-5-3c-4 3-10 2-13-3z"/><path d="M8 11h.01"/>',
    skull: '<path d="M12 3a8 8 0 0 0-5 14.2V20h10v-2.8A8 8 0 0 0 12 3z"/><path d="M9 12h.01M15 12h.01M10 20v-2M14 20v-2"/>',
    exit: '<path d="M15 18l-6-6 6-6"/>',
    up: '<path d="M12 19V5M6 11l6-6 6 6"/>',
    import: '<path d="M12 3v12M8 11l4 4 4-4M5 12v7h14v-7"/>',
    reset: '<path d="M4 12a8 8 0 1 0 2.3-5.7L4 8.6"/><path d="M4 4v4.6h4.6"/>',
    hand: '<path d="M8 13V5.5a1.5 1.5 0 0 1 3 0V11M11 10.5V4a1.5 1.5 0 0 1 3 0v6.5M14 10.5V5.5a1.5 1.5 0 0 1 3 0V14c0 4-2.5 7-6.5 7-3 0-4.5-1.5-6-4l-1.8-3.2a1.4 1.4 0 0 1 2.3-1.6L8 15"/>'
  };

  function svg(name) {
    var body = ICONS[name];
    if (!body) return null;
    return '<svg class="i" viewBox="0 0 24 24" aria-hidden="true">' + body + '</svg>';
  }

  function el(name) {
    var markup = svg(name);
    if (!markup) return window.ui && window.ui.icon ? window.ui.icon(name) : document.createElement('span');
    var holder = document.createElement('span');
    holder.innerHTML = markup;
    return holder.firstChild;
  }

  function add(name, body) { ICONS[name] = String(body); }

  window.GameIcons = { svg: svg, el: el, add: add, names: Object.keys(ICONS) };
})();
