/*
 * The phone side of the watch app (PebbleKit JS): talks to the person's SuperAwesomeMagicalHealth
 * server with a watch key, and passes today's numbers and taps between it and the watch.
 */
'use strict';

var logic = require('./logic');

var SERVER_KEY = 'samh.server';
var TOKEN_KEY = 'samh.token';

/** Which dose and habit each row on the watch is, from the last list sent to it. */
var sent = { doses: [], habits: [] };
var refreshing = false;

function server() {
  return localStorage.getItem(SERVER_KEY);
}

function token() {
  return localStorage.getItem(TOKEN_KEY);
}

function connected() {
  return !!(server() && token());
}

// ---- Messages to the watch, one at a time (the watch takes one message at a time). ----

var outbox = [];
var sending = false;

function toWatch(message) {
  outbox.push({ message: message, tries: 0 });
  sendNext();
}

function sendNext() {
  if (sending || !outbox.length) return;
  sending = true;
  var item = outbox[0];
  Pebble.sendAppMessage(
    item.message,
    function () {
      outbox.shift();
      sending = false;
      sendNext();
    },
    function () {
      item.tries++;
      sending = false;
      if (item.tries >= 3) outbox.shift();
      setTimeout(sendNext, 400 * item.tries);
    }
  );
}

function status(code, text) {
  var message = { Status: code };
  if (text) message.Message = text;
  toWatch(message);
}

// ---- The server ----

function request(method, path, body, callback) {
  var xhr = new XMLHttpRequest();
  var finished = false;
  function finish(code, text) {
    if (finished) return;
    finished = true;
    callback(code, text);
  }
  try {
    xhr.open(method, server() + path, true);
    xhr.setRequestHeader('Authorization', 'Bearer ' + token());
    xhr.setRequestHeader('Accept', 'application/json');
    if (body) xhr.setRequestHeader('Content-Type', 'application/json');
    xhr.timeout = 15000;
    xhr.onload = function () {
      finish(xhr.status, xhr.responseText);
    };
    xhr.onerror = function () {
      finish(0, null);
    };
    xhr.ontimeout = function () {
      finish(0, null);
    };
    xhr.send(body ? JSON.stringify(body) : null);
  } catch (e) {
    finish(0, null);
  }
}

function signedOut() {
  localStorage.removeItem(TOKEN_KEY);
  status(logic.STATUS.NOT_CONNECTED, 'This watch was disconnected. Open the app\'s settings in the Pebble app to connect again.');
}

function sendQueued() {
  if (!connected()) return;
  logic.drain(
    localStorage,
    function (log, done) {
      request('POST', '/api/watch/log', log, function (code) {
        done(logic.classifyStatus(code));
      });
    },
    Date.now(),
    function (result) {
      if (result.stopped === 'auth') signedOut();
      else if (result.sent > 0) refresh();
    }
  );
}

function refresh() {
  if (!connected()) {
    status(logic.STATUS.NOT_CONNECTED, 'Open this app\'s settings in the Pebble app on your phone to connect it to your server.');
    return;
  }
  if (refreshing) return;
  refreshing = true;
  request('GET', '/api/watch/today', null, function (code, text) {
    refreshing = false;
    var outcome = logic.classifyStatus(code);
    if (outcome === 'auth') return signedOut();
    if (outcome !== 'ok') {
      status(logic.STATUS.OFFLINE, code === 0 ? 'Can\'t reach your server right now.' : logic.errorMessage(text, 'Your server had a problem.'));
      return;
    }
    var today;
    try {
      today = JSON.parse(text);
    } catch (e) {
      status(logic.STATUS.OFFLINE, 'Your server sent something the watch can\'t read.');
      return;
    }
    var built = logic.todayToMessage(today);
    sent = built.sent;
    toWatch(built.message);
    sendQueued();
  });
}

function field(payload, name) {
  if (payload[name] !== undefined) return payload[name];
  try {
    var keys = require('message_keys');
    if (keys && payload[keys[name]] !== undefined) return payload[keys[name]];
  } catch (e) {
    // Older phone apps give names only.
  }
  return undefined;
}

function onTap(payload) {
  var log = logic.logFromAction(field(payload, 'Action'), field(payload, 'Value'), field(payload, 'Index'), sent, Date.now());
  if (!log) {
    status(logic.STATUS.FAILED, 'That didn\'t work. Try again after the list updates.');
    refresh();
    return;
  }
  if (!connected()) {
    logic.enqueue(localStorage, log, Date.now());
    status(logic.STATUS.QUEUED, 'Saved on your phone. It\'ll send once the watch is connected.');
    return;
  }
  request('POST', '/api/watch/log', log, function (code, text) {
    var outcome = logic.classifyStatus(code);
    if (outcome === 'ok') {
      status(logic.STATUS.LOGGED);
      refresh();
    } else if (outcome === 'drop') {
      status(logic.STATUS.FAILED, logic.errorMessage(text, 'Your server didn\'t take that.'));
    } else {
      logic.enqueue(localStorage, log, Date.now());
      status(logic.STATUS.QUEUED, 'Saved on your phone. It\'ll send when your server is back.');
      if (outcome === 'auth') signedOut();
    }
  });
}

Pebble.addEventListener('ready', function () {
  refresh();
});

Pebble.addEventListener('appmessage', function (e) {
  var payload = e.payload || {};
  if (field(payload, 'Action') !== undefined) onTap(payload);
  else if (field(payload, 'Refresh') !== undefined) refresh();
});

Pebble.addEventListener('showConfiguration', function () {
  Pebble.openURL(logic.setupPageUrl(server(), connected()));
});

Pebble.addEventListener('webviewclosed', function (e) {
  var result = logic.parseConfigResponse(e && e.response, server());
  if (!result) return;
  if (result.disconnect) {
    localStorage.removeItem(TOKEN_KEY);
    sent = { doses: [], habits: [] };
    status(logic.STATUS.NOT_CONNECTED, 'Disconnected. Open the settings in the Pebble app to connect again.');
    return;
  }
  localStorage.setItem(SERVER_KEY, result.server);
  localStorage.setItem(TOKEN_KEY, result.token);
  refresh();
});
