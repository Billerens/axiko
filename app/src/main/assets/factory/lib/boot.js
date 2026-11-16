// Runs once when the app starts (not in safe mode). Register background work here:
// subscriptions to system events, timers, start-up of modules that must run unseen.
// Keep it fast and wrap each module's start in try/catch so one failure does not stop the rest.

var starters = [
  // 'modules/<id>/main.js' — a module whose exports.start() should run at launch
];

// Routines the owner set up: their timers and listeners.
try {
  require('lib/routines').start();
} catch (error) {
  console.error('boot: routines: ' + error);
}

// Modules that ask to be started at launch in their module.json ("autostart": true) — those
// installed from the store do it this way, without editing this file.
try {
  var workspace = new java.io.File(context.getFilesDir(), 'assi/modules');
  var folders = workspace.listFiles() || [];
  for (var i = 0; i < folders.length; i++) {
    var id = String(folders[i].getName());
    var path = 'modules/' + id + '/main.js';
    if (starters.indexOf(path) >= 0) continue;
    try {
      var meta = require('lib/assi').files.readJson('modules/' + id + '/module.json');
      if (meta && meta.autostart) starters.push(path);
    } catch (ignored) {}
  }
} catch (error) {
  console.error('boot: autostart: ' + error);
}

starters.forEach(function (path) {
  try {
    var module = require(path);
    if (typeof module.start === 'function') module.start();
  } catch (error) {
    console.error('boot: ' + path + ': ' + error);
  }
});
