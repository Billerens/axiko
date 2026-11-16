// The phone's senses for the life links, read from Rhino: the network, the step counter, the
// barometer, a window of the accelerometer, Wi-Fi networks around. Every function fails soft:
// no sensor, no permission, a sleeping phone — it returns null and the link goes on without it.
// Only the engine (Rhino) calls this file; the pure logic of the links is in their own files.

var TYPE = { accel: 1, pressure: 6, linear: 10, stepDetector: 18, stepCounter: 19 };

function manager() {
  try {
    return context.getSystemService('sensor');
  } catch (error) {
    return null;
  }
}

/** Is there a working connection now (validated internet)? null when it cannot be told. */
exports.online = function () {
  try {
    var cm = context.getSystemService('connectivity');
    var network = cm.getActiveNetwork();
    if (!network) return false;
    var caps = cm.getNetworkCapabilities(network);
    return !!caps && caps.hasCapability(12) && caps.hasCapability(16);
  } catch (error) {
    return null;
  }
};

/** Is the sensor of this type on the phone? */
exports.has = function (type) {
  var sm = manager();
  try {
    return !!(sm && sm.getDefaultSensor(TYPE[type] || type));
  } catch (error) {
    return false;
  }
};

/**
 * Listens to a sensor for `ms` and returns what came: [[t, v0, v1, v2], …] (null when there is no
 * such sensor). `first` — stop at the first value (the barometer, the step counter).
 */
function listen(type, ms, first, delayUs) {
  var sm = manager();
  if (!sm) return null;
  var sensor = sm.getDefaultSensor(type);
  if (!sensor) return null;
  var out = [];
  var done = false;
  var listener = extend(android.hardware.SensorEventListener, {
    onSensorChanged: function (event) {
      if (done) return;
      var v = event.values;
      out.push([Date.now(), Number(v[0]), v.length > 1 ? Number(v[1]) : 0, v.length > 2 ? Number(v[2]) : 0]);
      if (first) done = true;
    },
    onAccuracyChanged: function () {}
  });
  try {
    sm.registerListener(listener, sensor, delayUs || 20000);
    var until = Date.now() + ms;
    while (!done && Date.now() < until) sleep(first ? 50 : 200);
  } finally {
    done = true;
    try {
      sm.unregisterListener(listener);
    } catch (ignored) { /* already gone */ }
  }
  return out;
}

/** The barometer now, hPa (null without one or when it did not answer in 3 s). */
exports.pressure = function () {
  var got = listen(TYPE.pressure, 3000, true, 200000);
  return got && got.length ? got[0][1] : null;
};

/** The step counter since the phone started (null without permission or sensor). */
exports.stepCounter = function () {
  try {
    if (context.checkSelfPermission('android.permission.ACTIVITY_RECOGNITION') !== 0) return null;
  } catch (ignored) { /* an old Android: no runtime permission */ }
  // A batching counter may answer late after the listener is registered.
  var got = listen(TYPE.stepCounter, 6000, true, 0);
  return got && got.length ? Math.round(got[0][1]) : null;
};

/**
 * A window of the accelerometer: {rms, freq, steps, n}. rms — the root mean square of the
 * acceleration without gravity (a high-pass filter when only the raw sensor is there), freq —
 * the dominant frequency estimated by crossings of the mean, steps — the step detector's count.
 */
exports.window = function (sec) {
  var ms = Math.max(1000, sec * 1000);
  var linear = exports.has('linear');
  var stepsBox = { n: 0 };
  var sm = manager();
  var stepSensor = null;
  var stepListener = null;
  try {
    if (sm && context.checkSelfPermission('android.permission.ACTIVITY_RECOGNITION') === 0) {
      stepSensor = sm.getDefaultSensor(TYPE.stepDetector);
      if (stepSensor) {
        stepListener = extend(android.hardware.SensorEventListener, {
          onSensorChanged: function () { stepsBox.n++; },
          onAccuracyChanged: function () {}
        });
        sm.registerListener(stepListener, stepSensor, 0);
      }
    }
  } catch (ignored) { /* no step detector */ }
  var samples;
  try {
    samples = listen(linear ? TYPE.linear : TYPE.accel, ms, false, 20000);
  } finally {
    if (stepListener) {
      try {
        sm.unregisterListener(stepListener);
      } catch (ignored) { /* already gone */ }
    }
  }
  if (!samples || samples.length < 10) return null;
  return exports.measure(samples, !linear, stepsBox.n);
};

/** The numbers of a window from raw samples [[t, x, y, z]] (pure: node tests call it). */
exports.measure = function (samples, raw, steps) {
  var mags = [];
  var gravity = [0, 0, 0];
  var alpha = 0.9;
  for (var i = 0; i < samples.length; i++) {
    var s = samples[i];
    var x = s[1];
    var y = s[2];
    var z = s[3];
    if (raw) {
      // High-pass: take away a slowly following estimate of gravity.
      if (i === 0) gravity = [x, y, z];
      gravity[0] = alpha * gravity[0] + (1 - alpha) * x;
      gravity[1] = alpha * gravity[1] + (1 - alpha) * y;
      gravity[2] = alpha * gravity[2] + (1 - alpha) * z;
      x -= gravity[0];
      y -= gravity[1];
      z -= gravity[2];
      if (i < 10) continue;
    }
    mags.push([s[0], Math.sqrt(x * x + y * y + z * z)]);
  }
  if (mags.length < 5) return null;
  var sum = 0;
  var sq = 0;
  mags.forEach(function (m) {
    sum += m[1];
    sq += m[1] * m[1];
  });
  var mean = sum / mags.length;
  var rms = Math.sqrt(sq / mags.length);
  var crossings = 0;
  for (var j = 1; j < mags.length; j++) {
    if ((mags[j - 1][1] - mean) * (mags[j][1] - mean) < 0) crossings++;
  }
  var span = (mags[mags.length - 1][0] - mags[0][0]) / 1000;
  // Two crossings of the mean per period of the magnitude.
  var freq = span > 0 ? crossings / 2 / span : 0;
  return { rms: rms, freq: freq, steps: steps || 0, n: mags.length };
};

/** Names of the Wi-Fi networks seen at the last scan (an empty list without location access). */
exports.wifiNames = function () {
  try {
    var wm = context.getSystemService('wifi');
    try {
      wm.startScan();
    } catch (ignored) { /* throttled: four scans in two minutes */ }
    var list = wm.getScanResults();
    var out = [];
    for (var i = 0; i < list.size(); i++) {
      var r = list.get(i);
      var name = r.SSID !== undefined ? String(r.SSID) : '';
      if (name) out.push(name.replace(/^"|"$/g, ''));
    }
    return out;
  } catch (error) {
    return [];
  }
};
