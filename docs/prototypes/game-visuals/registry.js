/* Shared only by the standalone visual studies, independent of the Axiko runtime. */
window.AssiVisuals = {
  games: new Map(),
  register(game) {
    if (!game.id || game.variants.length !== 2 || this.games.has(game.id)) throw new Error('Invalid visual study: ' + game.id);
    this.games.set(game.id, game);
  }
};
