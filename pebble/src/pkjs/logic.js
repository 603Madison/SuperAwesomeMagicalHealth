/*
 * The watch app's phone-side logic, kept free of Pebble's globals so Node can test it
 * (node --test pebble/test/). index.js wires it to PebbleKit JS. Written as ES5: older Pebble
 * phone apps run an older JavaScript engine.
 */
'use strict';

/** The watch's taps, in the order of WatchLogKind on the server (and the C enum). */
var LOG_KINDS = ['MOOD', 'WATER', 'COFFEE', 'TOILET', 'LEAK', 'BOWEL', 'DOSE_TAKEN', 'HABIT', 'HOT_FLASH', 'SYMPTOM'];

/**
 * Features the watch shows, as bits (kept in step with app.h). WATER isn't a feature: it's set
 * unless the person has turned water off (WatchToday.water is false), and hides the water tap and
 * Today's water line.
 */
var MODULE_BITS = { MIND: 1, NUTRITION: 2, CONTINENCE: 4, MEDICATIONS: 8, HABITS: 16, CYCLE: 32, SYMPTOMS: 64, WATER: 128 };

var STRESS_STATES = ['STRESSED', 'ENGAGED', 'RELAXED', 'RESTORED'];

/** What the phone tells the watch (kept in step with app.h). */
var STATUS = { OK: 0, NOT_CONNECTED: 1, OFFLINE: 2, LOGGED: 3, QUEUED: 4, FAILED: 5 };

/** The symptom list on the watch, in order. */
var SYMPTOMS = ['headache', 'pain', 'nausea', 'fatigue', 'dizziness', 'other'];

/** What a watch list can hold: kept small so a message fits the watch's inbox. */
var MAX_ITEMS = 10;
var MAX_NAME = 20;

var QUEUE_KEY = 'samh.queue';
var QUEUE_MAX = 100;
var QUEUE_MAX_AGE_MS = 14 * 24 * 60 * 60 * 1000;

/** "health.example.com/" → "https://health.example.com"; null if it isn't a web address. */
function normaliseServer(input) {
  if (input == null) return null;
  var text = String(input).replace(/^\s+|\s+$/g, '');
  if (!text) return null;
  if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(text)) text = 'https://' + text;
  var match = /^(https?):\/\/([^\/?#\s]+)([^?#\s]*)/i.exec(text);
  if (!match) return null;
  var path = match[3].replace(/\/+$/, '');
  return match[1].toLowerCase() + '://' + match[2] + path;
}

/** The server's watch settings page, which returns to the watch with a key. */
function configPageUrl(server, returnTo) {
  return server + '/pebble/config?return_to=' + encodeURIComponent(returnTo || 'pebblejs://close');
}

function escapeHtml(text) {
  return String(text).replace(/[&<>"']/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
  });
}

/**
 * The first settings page: asks which server to use, then goes to that server's own page (where
 * the person signs in). Built in, so it works before the watch knows any server.
 */
function setupPageUrl(currentServer, connected) {
  var value = currentServer ? escapeHtml(currentServer) : '';
  var html =
    '<!doctype html><html><head><meta charset="utf-8">' +
    '<meta name="viewport" content="width=device-width,initial-scale=1">' +
    '<title>SAM Health</title><style>' +
    'body{margin:0;font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;color:#1e293b;' +
    'background:linear-gradient(160deg,#ede9fe 0%,#f8fafc 45%,#dcfce7 100%);min-height:100vh}' +
    'main{max-width:420px;margin:0 auto;padding:28px 18px}' +
    'h1{font-size:26px;margin:0 0 6px}p{color:#475569;line-height:1.45;margin:0 0 18px}' +
    '.card{background:#fff;border-radius:20px;padding:18px;box-shadow:0 8px 30px rgba(15,23,42,.08)}' +
    'label{display:block;font-weight:600;margin-bottom:8px}' +
    'input{box-sizing:border-box;width:100%;font-size:17px;padding:12px 14px;border:1px solid #cbd5e1;border-radius:12px}' +
    'button{width:100%;margin-top:14px;font-size:17px;font-weight:600;padding:13px;border:0;border-radius:999px;' +
    'background:#7c3aed;color:#fff}button.ghost{background:transparent;color:#7c3aed;border:1px solid #c4b5fd}' +
    '#error{color:#b91c1c;margin-top:10px;min-height:1em}' +
    '</style></head><body><main><h1>Connect your watch</h1>' +
    '<p>Type your SuperAwesomeMagicalHealth server\'s address. You\'ll sign in on the next page.</p>' +
    '<form class="card" id="f"><label for="s">Your server</label>' +
    '<input id="s" type="url" inputmode="url" autocomplete="url" autocapitalize="off" spellcheck="false" ' +
    'placeholder="https://health.example.com" value="' + value + '" required>' +
    '<div id="error"></div><button type="submit">Connect</button>' +
    (connected ? '<button type="button" class="ghost" id="d">Disconnect this watch</button>' : '') +
    '</form></main><script>' +
    'function norm(t){t=(t||"").trim();if(!t)return null;if(!/^[a-z][a-z0-9+.-]*:\\/\\//i.test(t))t="https://"+t;' +
    'var m=/^(https?):\\/\\/([^\\/?#\\s]+)([^?#\\s]*)/i.exec(t);if(!m)return null;' +
    'return m[1].toLowerCase()+"://"+m[2]+m[3].replace(/\\/+$/,"");}' +
    'document.getElementById("f").onsubmit=function(e){e.preventDefault();' +
    'var s=norm(document.getElementById("s").value);' +
    'if(!s){document.getElementById("error").textContent="That doesn\'t look like a web address.";return;}' +
    'location.href=s+"/pebble/config?return_to="+encodeURIComponent("pebblejs://close");};' +
    'var d=document.getElementById("d");if(d)d.onclick=function(){' +
    'location.href="pebblejs://close#"+encodeURIComponent(JSON.stringify({disconnect:true}));};' +
    '</script></body></html>';
  return 'data:text/html;charset=utf-8,' + encodeURIComponent(html);
}

function safeDecode(text) {
  try {
    return decodeURIComponent(text);
  } catch (e) {
    return text;
  }
}

function originOf(url) {
  var match = /^(https?:\/\/[^\/?#]+)/i.exec(url || '');
  return match ? match[1] : null;
}

/**
 * What the settings page sent back when it closed: {server, token}, {disconnect: true}, or null
 * (closed without connecting). Accepts JSON (encoded once or twice, with or without a leading
 * "#") or "server=…&token=…".
 */
function parseConfigResponse(response, fallbackServer) {
  if (response == null) return null;
  var text = String(response).replace(/^#+/, '');
  if (!text || text === 'CANCELLED') return null;
  var candidates = [text, safeDecode(text), safeDecode(safeDecode(text))];
  var found = null;
  for (var i = 0; i < candidates.length && !found; i++) {
    var c = candidates[i].replace(/^#+/, '').replace(/^\s+|\s+$/g, '');
    if (c.charAt(0) === '{') {
      try {
        found = JSON.parse(c);
      } catch (e) {
        found = null;
      }
    } else if (/(^|&)token=/.test(c)) {
      found = {};
      c.split('&').forEach(function (pair) {
        var at = pair.indexOf('=');
        if (at > 0) found[safeDecode(pair.slice(0, at))] = safeDecode(pair.slice(at + 1).replace(/\+/g, ' '));
      });
    }
  }
  if (!found || typeof found !== 'object') return null;
  if (found.disconnect === true || found.disconnect === 'true') return { disconnect: true };
  var token = typeof found.token === 'string' ? found.token.replace(/^\s+|\s+$/g, '') : '';
  if (!token) return null;
  var server = normaliseServer(found.server) || normaliseServer(originOf(found.url)) || normaliseServer(fallbackServer);
  if (!server) return null;
  return { server: server, token: token };
}

/** A WatchLog body for POST /api/watch/log. */
function buildLog(kind, value, ref, at) {
  if (LOG_KINDS.indexOf(kind) < 0) return null;
  var log = { kind: kind };
  if (value != null && !isNaN(value)) log.value = Math.round(Number(value));
  if (ref != null && ref !== '') log.ref = String(ref);
  if (at != null) log.at = at;
  return log;
}

/**
 * A tap from the watch ({Action, Value, Index}) as a WatchLog, using the lists last sent to the
 * watch to know which dose or habit [index] means. Null when it can't be made sense of.
 */
function logFromAction(action, value, index, sent, now) {
  var kind = LOG_KINDS[action];
  if (!kind) return null;
  sent = sent || { doses: [], habits: [] };
  switch (kind) {
    case 'DOSE_TAKEN':
      var dose = sent.doses[index];
      return dose ? buildLog(kind, null, dose, now) : null;
    case 'HABIT':
      var habit = sent.habits[index];
      return habit ? buildLog(kind, 1, habit, now) : null;
    case 'SYMPTOM':
      var symptom = SYMPTOMS[index];
      if (!symptom || value == null || value < 0 || value > 10) return null;
      return buildLog(kind, value, symptom, now);
    case 'MOOD':
      if (value == null || value < 1 || value > 5) return null;
      return buildLog(kind, value, null, now);
    case 'HOT_FLASH':
      if (value == null || value < 1 || value > 3) return null;
      return buildLog(kind, value, null, now);
    case 'WATER':
    case 'COFFEE':
      if (value == null || value <= 0 || value > 2000) return null;
      return buildLog(kind, value, null, now);
    default:
      return buildLog(kind, null, null, now);
  }
}

/** A name that fits a watch row: no tabs or line breaks, at most MAX_NAME characters. */
function shortName(name) {
  var text = String(name == null ? '' : name).replace(/[\t\r\n]+/g, ' ').replace(/^\s+|\s+$/g, '');
  var chars = Array.from ? Array.from(text) : text.split('');
  if (chars.length > MAX_NAME) text = chars.slice(0, MAX_NAME - 1).join('') + '…';
  return text || '–';
}

function score(value) {
  return typeof value === 'number' && isFinite(value) ? Math.round(value) : -1;
}

/**
 * WatchToday as one AppMessage for the watch, plus which dose and habit each row is (the watch
 * sends back row numbers). Lists are packed as lines of tab-separated fields:
 * doses "time\tname\t0|1", habits "name\t0|1".
 */
function todayToMessage(today) {
  var modules = 0;
  (today.modules || []).forEach(function (m) {
    if (m !== 'WATER' && MODULE_BITS[m]) modules |= MODULE_BITS[m];
  });
  // Older servers don't say: water is on unless the server says it's off.
  var water = today.water !== false;
  if (water) modules |= MODULE_BITS.WATER;
  var doses = (today.doses || []).slice(0, MAX_ITEMS);
  var habits = (today.habits || []).slice(0, MAX_ITEMS);
  var message = {
    Status: STATUS.OK,
    Modules: modules,
    Readiness: score(today.readiness),
    Sleep: score(today.sleep),
    Activity: score(today.activity),
    Steps: Math.max(0, Math.round(today.steps || 0)),
    StepGoal: Math.max(0, Math.round(today.stepGoal || 0)),
    Water: water ? Math.max(0, Math.round(today.waterMl || 0)) : 0,
    WaterGoal: water ? Math.max(0, Math.round(today.waterGoal || 0)) : 0,
    Stress: today.stress ? STRESS_STATES.indexOf(today.stress) : -1,
    Doses: doses.map(function (d) {
      return shortName(d.time).slice(0, 8) + '\t' + shortName(d.name) + '\t' + (d.taken ? '1' : '0');
    }).join('\n'),
    Habits: habits.map(function (h) {
      return shortName(h.name) + '\t' + (h.done ? '1' : '0');
    }).join('\n'),
  };
  return {
    message: message,
    sent: {
      doses: doses.map(function (d) { return d.medicationId + '@' + d.scheduledFor; }),
      habits: habits.map(function (h) { return h.id; }),
    },
  };
}

/** What to do after a request: "ok", "retry" (try again later), "auth" (the key was revoked) or "drop". */
function classifyStatus(status) {
  if (status >= 200 && status < 300) return 'ok';
  if (status === 401) return 'auth';
  if (status === 0 || status === 408 || status === 429 || status >= 500) return 'retry';
  return 'drop';
}

/** The server's error message ({"error": "…"}), short enough for the watch. */
function errorMessage(body, fallback) {
  try {
    var parsed = JSON.parse(body);
    if (parsed && typeof parsed.error === 'string' && parsed.error) return parsed.error.slice(0, 90);
  } catch (e) {
    // Not JSON.
  }
  return fallback;
}

// ---- The offline queue: taps that couldn't be sent wait in the phone's storage. ----

function readQueue(storage, now) {
  var list;
  try {
    list = JSON.parse(storage.getItem(QUEUE_KEY) || '[]');
  } catch (e) {
    list = [];
  }
  if (!Array.isArray(list)) list = [];
  return list.filter(function (item) {
    return item && item.log && typeof item.queuedAt === 'number' && (now == null || now - item.queuedAt <= QUEUE_MAX_AGE_MS);
  });
}

function writeQueue(storage, list) {
  if (list.length) storage.setItem(QUEUE_KEY, JSON.stringify(list));
  else storage.removeItem(QUEUE_KEY);
}

/** Keeps a tap to send later; the oldest go first if the queue is full. Returns the queue's length. */
function enqueue(storage, log, now) {
  var list = readQueue(storage, now);
  list.push({ log: log, queuedAt: now });
  if (list.length > QUEUE_MAX) list = list.slice(list.length - QUEUE_MAX);
  writeQueue(storage, list);
  return list.length;
}

/**
 * Sends what's queued, oldest first, one at a time: send(log, callback(outcome)) with an outcome
 * from classifyStatus. Stops at the first "retry" or "auth"; "drop" removes the tap (the server
 * will never take it). Calls done({sent, dropped, left, stopped}).
 */
function drain(storage, send, now, done) {
  var list = readQueue(storage, now);
  var sent = 0;
  var dropped = 0;
  function next() {
    if (!list.length) {
      writeQueue(storage, list);
      done({ sent: sent, dropped: dropped, left: 0, stopped: null });
      return;
    }
    send(list[0].log, function (outcome) {
      if (outcome === 'ok' || outcome === 'drop') {
        if (outcome === 'ok') sent++;
        else dropped++;
        list.shift();
        writeQueue(storage, list);
        next();
      } else {
        writeQueue(storage, list);
        done({ sent: sent, dropped: dropped, left: list.length, stopped: outcome });
      }
    });
  }
  next();
}

module.exports = {
  LOG_KINDS: LOG_KINDS,
  MODULE_BITS: MODULE_BITS,
  STRESS_STATES: STRESS_STATES,
  STATUS: STATUS,
  SYMPTOMS: SYMPTOMS,
  MAX_ITEMS: MAX_ITEMS,
  QUEUE_KEY: QUEUE_KEY,
  QUEUE_MAX: QUEUE_MAX,
  QUEUE_MAX_AGE_MS: QUEUE_MAX_AGE_MS,
  normaliseServer: normaliseServer,
  configPageUrl: configPageUrl,
  setupPageUrl: setupPageUrl,
  parseConfigResponse: parseConfigResponse,
  buildLog: buildLog,
  logFromAction: logFromAction,
  shortName: shortName,
  todayToMessage: todayToMessage,
  classifyStatus: classifyStatus,
  errorMessage: errorMessage,
  readQueue: readQueue,
  enqueue: enqueue,
  drain: drain,
};
