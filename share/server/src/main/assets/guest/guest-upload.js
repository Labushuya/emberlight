/*
 * Gäste-Upload im Browser (ADR 0014, ADR 0015, ADR 0019; Paket 3.5b). Auswahl und Ablage am PC, Warteschlange mit zwei
 * gleichzeitigen Uploads, Fortschritt je Datei (XHR), Vorabprüfung von Format, Größe und freiem Speicher, Wiederholung bei
 * Busy- und Netzfehlern, Standort-Rückfrage pro Datei. Nutzt guest-core.js und die Schnittstelle `core.space` aus
 * guest-space.js. Daten nur als Text (textContent). Breiten nur über `element.style` (CSP ohne unsafe-inline).
 * Die Signaturen stehen in `formats.json` und müssen zu ContentInspector passen (Test GuestFormatsParityTest).
 */
(function () {
  "use strict";

  var core = window.emberlightGuest;
  var t = core.text;
  var space = core.space;

  // Zwei gleichzeitige Uploads je Gast (ADR 0013, Grenzwerte). Die Server-Grenze liegt ebenfalls bei zwei je Teilnehmer.
  var CONCURRENCY = 2;
  // Netzfehler, 408 und 5xx: Wartezeit vor dem nächsten Versuch. Drei Wiederholungen, danach ist Schluss.
  var NETWORK_BACKOFF_MS = [1000, 3000, 10000];
  // 429 und 503 mit Retry-After (ohne Fehlermeldung wiederholt). Ohne Angabe 5 s, höchstens 60 s je Warten.
  var RETRY_AFTER_DEFAULT_S = 5;
  var RETRY_AFTER_MAX_S = 60;
  var BUSY_WAITS_MAX = 10;
  var HEAD_BYTES = 32;
  var ID_PATTERN = /^[A-Za-z0-9_-]{22}$/;

  var ST = {
    CHECKING: "checking",
    WAITING: "waiting",
    UPLOADING: "uploading",
    RETRYING: "retrying",
    LOCATION: "location",
    DONE: "done",
    DUPLICATE: "duplicate",
    REJECTED: "rejected",
    ABORTED: "aborted",
    FAILED: "failed"
  };
  var PENDING = [ST.WAITING, ST.UPLOADING, ST.RETRYING, ST.LOCATION];
  var COUNTED = PENDING.concat([ST.DONE, ST.DUPLICATE, ST.FAILED]);
  var FINAL = [ST.DONE, ST.DUPLICATE, ST.REJECTED, ST.ABORTED, ST.FAILED];

  var REJECT_KEYS = {
    format: "uploadRejectFormat",
    size: "uploadRejectSize",
    space: "uploadRejectSpace",
    unavailable: "uploadRejectUnavailable"
  };
  var ERROR_KEYS = {
    payload_too_large: "uploadErrorTooLarge",
    unsupported_media_type: "uploadErrorFormat",
    storage_full: "uploadErrorStorageFull",
    device_storage_low: "uploadErrorDeviceStorage",
    forbidden: "uploadErrorForbidden",
    network: "uploadErrorNetwork",
    request_timeout: "uploadErrorNetwork",
    incomplete_body: "uploadErrorNetwork",
    busy: "uploadErrorBusy",
    bad_filename: "uploadErrorFilename"
  };

  function byId(id) {
    return document.getElementById(id);
  }

  var el = {
    section: byId("upload-section"),
    pick: byId("upload-pick"),
    input: byId("file-input"),
    zone: byId("drop-zone"),
    notice: byId("upload-notice"),
    total: byId("upload-total"),
    totalBar: byId("upload-total-bar"),
    totalFill: byId("upload-total-fill"),
    keepOpen: byId("upload-keepopen"),
    resume: byId("upload-resume"),
    queue: byId("upload-queue"),
    locationScrim: byId("location-scrim"),
    locationDialog: byId("location-dialog"),
    locationName: byId("location-name"),
    locationKeep: byId("location-keep"),
    locationSkip: byId("location-skip")
  };

  var rules = null;
  var items = [];
  var rows = {};
  var nextUid = 1;
  // Stopp der Warteschlange: "storage_full", "device_storage_low" oder "closed". `null`, wenn sie läuft.
  var halted = null;
  var questions = [];
  var dialogItem = null;
  var releaseTrap = null;

  // Regeln für die Signaturprüfung. Ohne sie wird nichts gesendet (fail closed).
  var rulesReady = fetch("/formats.json", { cache: "no-store", credentials: "omit" })
    .then(function (response) {
      if (!response.ok) throw new Error("formats.json");
      return response.json();
    })
    .then(function (loaded) {
      if (!loaded || !Array.isArray(loaded.signatures) || !loaded.ftyp) throw new Error("formats.json ungültig");
      rules = loaded;
    })
    .catch(function () {
      rules = null;
    });

  // Signaturprüfung

  function hexBytes(hex) {
    var out = [];
    for (var i = 0; i + 1 < hex.length; i += 2) out.push(parseInt(hex.substr(i, 2), 16));
    return out;
  }

  function asciiBytes(text) {
    var out = [];
    for (var i = 0; i < text.length; i++) out.push(text.charCodeAt(i) & 0xff);
    return out;
  }

  function matchesAt(head, check) {
    var expected = check.hex !== undefined ? hexBytes(check.hex) : asciiBytes(check.ascii);
    if (check.offset + expected.length > head.length) return false;
    for (var i = 0; i < expected.length; i++) {
      if (head[check.offset + i] !== expected[i]) return false;
    }
    return true;
  }

  function asciiAt(head, offset, length) {
    if (offset + length > head.length) return null;
    var text = "";
    for (var i = 0; i < length; i++) text += String.fromCharCode(head[offset + i]);
    return text;
  }

  /** Erkennt das Format an den ersten Bytes (Regeln aus formats.json). `null` bei unbekanntem Format. */
  function identify(head) {
    if (!rules) return null;
    for (var i = 0; i < rules.signatures.length; i++) {
      var signature = rules.signatures[i];
      var matched = signature.checks.every(function (check) {
        return matchesAt(head, check);
      });
      if (matched) return { mime: signature.mime, kind: signature.kind };
    }
    var ftyp = rules.ftyp;
    if (!matchesAt(head, ftyp)) return null;
    var brand = asciiAt(head, ftyp.brandOffset, 4);
    if (brand === null || !Object.prototype.hasOwnProperty.call(ftyp.brands, brand)) return null;
    var entry = ftyp.brands[brand];
    return { mime: entry.mime, kind: entry.kind };
  }

  function readHead(file) {
    return file.slice(0, HEAD_BYTES).arrayBuffer().then(function (buffer) {
      return new Uint8Array(buffer);
    });
  }

  // Hilfen

  function parseJson(text) {
    try {
      var value = JSON.parse(text);
      return value && typeof value === "object" ? value : {};
    } catch (ignored) {
      return {};
    }
  }

  /** Sekunden aus `Retry-After`, `null` ohne Angabe. Begrenzt, damit ein Gerät nicht ewig wartet. */
  function retryAfterSeconds(xhr) {
    var raw = xhr.getResponseHeader("Retry-After");
    if (raw === null) return null;
    var seconds = parseInt(raw, 10);
    if (!(seconds >= 0)) return RETRY_AFTER_DEFAULT_S;
    return Math.min(seconds, RETRY_AFTER_MAX_S);
  }

  function hasFiles(event) {
    var types = event.dataTransfer && event.dataTransfer.types;
    return !!types && Array.prototype.indexOf.call(types, "Files") !== -1;
  }

  function countState(list) {
    return items.filter(function (item) {
      return list.indexOf(item.state) !== -1;
    }).length;
  }

  function pendingBytes() {
    return items.reduce(function (sum, item) {
      return PENDING.indexOf(item.state) !== -1 ? sum + item.size : sum;
    }, 0);
  }

  function percentOf(item) {
    if (!(item.size > 0)) return 0;
    return Math.min(99, Math.floor((item.sent * 100) / item.size));
  }

  function clearTimer(item) {
    if (item.timer !== null) {
      clearTimeout(item.timer);
      item.timer = null;
    }
  }

  // Vorabprüfung und Aufnahme

  function rejectItem(item, reason, limit) {
    item.state = ST.REJECTED;
    item.reason = reason;
    item.limit = limit || 0;
    renderRow(item);
  }

  /** Prüft Format, Größe und freien Speicher, bevor etwas gesendet wird (Summe der Auswahl zählt mit). */
  function checkItem(item) {
    if (!rules || !space.session()) {
      rejectItem(item, "unavailable");
      return Promise.resolve();
    }
    return readHead(item.file).then(
      function (head) {
        var format = identify(head);
        if (!format) {
          rejectItem(item, "format");
          return;
        }
        var limits = space.limits();
        var limit = format.kind === "video" ? limits.videoMaxBytes : limits.imageMaxBytes;
        if (!(limit > 0)) {
          rejectItem(item, "unavailable");
          return;
        }
        if (item.size > limit) {
          rejectItem(item, "size", limit);
          return;
        }
        if (item.size > limits.freeBytes - pendingBytes()) {
          rejectItem(item, "space");
          return;
        }
        item.mime = format.mime;
        item.kind = format.kind;
        item.state = ST.WAITING;
        renderRow(item);
      },
      function () {
        rejectItem(item, "unavailable");
      }
    );
  }

  /** Nimmt die Auswahl in die Warteschlange auf. Jede Datei wird einzeln geprüft, bevor sie gesendet wird. */
  function addFiles(fileList) {
    if (!fileList || fileList.length === 0 || !space.session()) return;
    var added = Array.prototype.map.call(fileList, function (file) {
      var item = {
        uid: nextUid++,
        file: file,
        name: file.name || t("uploadUnnamed"),
        size: file.size,
        state: ST.CHECKING,
        mime: null,
        kind: null,
        sent: 0,
        keepLocation: false,
        networkRetries: 0,
        busyWaits: 0,
        timer: null,
        xhr: null,
        mediaId: null,
        locationKept: false,
        reason: null,
        limit: 0
      };
      items.push(item);
      renderRow(item);
      return item;
    });
    updateTotals();
    setNotice("");
    rulesReady
      .then(function () {
        return added.reduce(function (chain, item) {
          return chain.then(function () {
            return checkItem(item);
          });
        }, Promise.resolve());
      })
      .then(function () {
        var rejected = added.filter(function (item) {
          return item.state === ST.REJECTED;
        }).length;
        if (rejected > 0) setNotice(core.plural("uploadRejectedCount", rejected));
        pump();
      });
  }

  // Warteschlange

  /** Startet wartende Dateien, solange weniger als zwei laufen. Ohne Stopp und ohne Sitzung passiert nichts. */
  function pump() {
    if (halted === null && space.session()) {
      var running = countState([ST.UPLOADING]);
      for (var i = 0; i < items.length && running < CONCURRENCY; i++) {
        if (items[i].state === ST.WAITING) {
          start(items[i]);
          running += 1;
        }
      }
    }
    updateTotals();
  }

  function start(item) {
    var session = space.session();
    if (!session) return;
    item.state = ST.UPLOADING;
    item.sent = 0;
    var xhr = new XMLHttpRequest();
    item.xhr = xhr;
    xhr.open("POST", "/api/v1/media", true);
    xhr.responseType = "text";
    xhr.setRequestHeader("Authorization", "Bearer " + session.token);
    xhr.setRequestHeader("Content-Type", "application/octet-stream");
    xhr.setRequestHeader("X-Emberlight-Filename", encodeURIComponent(item.name));
    if (item.keepLocation) xhr.setRequestHeader("X-Emberlight-Keep-Location", "1");
    xhr.upload.onprogress = function (event) {
      if (item.xhr !== xhr || !event.lengthComputable) return;
      item.sent = event.loaded;
      updateProgress(item);
      updateTotals();
    };
    xhr.onload = function () {
      if (item.xhr !== xhr) return;
      item.xhr = null;
      onResponse(item, xhr);
    };
    xhr.onerror = function () {
      if (item.xhr !== xhr) return;
      item.xhr = null;
      onNetworkFailure(item);
    };
    xhr.ontimeout = xhr.onerror;
    renderRow(item);
    try {
      xhr.send(item.file);
    } catch (ignored) {
      item.xhr = null;
      onNetworkFailure(item);
    }
  }

  function onResponse(item, xhr) {
    var status = xhr.status;
    var data = parseJson(xhr.responseText);
    if (status === 201 || status === 200) {
      finish(item, data, status === 200 && data.duplicate === true);
      return;
    }
    if (status === 410) {
      closedByServer(data.endedAt);
      return;
    }
    if (status === 422 && data.error === "location_not_removable") {
      askLocation(item);
      return;
    }
    if (status === 507) {
      halt(data.error === "device_storage_low" ? "device_storage_low" : "storage_full");
      fail(item, data.error === "device_storage_low" ? "device_storage_low" : "storage_full");
      return;
    }
    var after = retryAfterSeconds(xhr);
    if (status === 429 || (status === 503 && after !== null)) {
      busyRetry(item, after === null ? RETRY_AFTER_DEFAULT_S : after);
      return;
    }
    if (status === 408 || status >= 500) {
      onNetworkFailure(item);
      return;
    }
    if (status === 401 || status === 403) {
      fail(item, "forbidden");
      return;
    }
    if (status === 413) {
      fail(item, "payload_too_large", data.limit);
      return;
    }
    fail(item, typeof data.error === "string" ? data.error : "generic");
  }

  function finish(item, data, duplicate) {
    item.sent = item.size;
    item.mediaId = typeof data.id === "string" && ID_PATTERN.test(data.id) ? data.id : null;
    item.locationKept = data.locationKept === true;
    item.state = duplicate ? ST.DUPLICATE : ST.DONE;
    renderRow(item);
    core.announce(
      duplicate ? t("uploadAnnounceDuplicate", { name: item.name }) : t("uploadAnnounceDone", { name: item.name })
    );
    space.refresh();
    space.reloadList();
    pump();
  }

  function busyRetry(item, seconds) {
    item.busyWaits += 1;
    if (item.busyWaits > BUSY_WAITS_MAX) {
      fail(item, "busy");
      return;
    }
    scheduleRetry(item, seconds * 1000);
  }

  function onNetworkFailure(item) {
    if (item.networkRetries >= NETWORK_BACKOFF_MS.length) {
      fail(item, "network");
      return;
    }
    var delay = NETWORK_BACKOFF_MS[item.networkRetries];
    item.networkRetries += 1;
    scheduleRetry(item, delay);
  }

  function scheduleRetry(item, delayMs) {
    clearTimer(item);
    item.state = ST.RETRYING;
    item.sent = 0;
    item.timer = setTimeout(function () {
      item.timer = null;
      if (item.state === ST.RETRYING) item.state = ST.WAITING;
      pump();
    }, delayMs);
    renderRow(item);
    updateTotals();
  }

  function fail(item, code, limit) {
    clearTimer(item);
    item.xhr = null;
    item.state = ST.FAILED;
    item.reason = code;
    item.limit = limit || 0;
    renderRow(item);
    core.announce(t("uploadAnnounceFailed", { name: item.name }));
    pump();
  }

  /** Stoppt die Warteschlange (507). Laufende Uploads enden von selbst, neue starten nicht. */
  function halt(code) {
    halted = code;
    setNotice(t(code === "device_storage_low" ? "uploadHaltDevice" : "uploadHaltStorage"));
    core.announce(t(code === "device_storage_low" ? "uploadHaltDevice" : "uploadHaltStorage"));
    items.forEach(renderRow);
  }

  function closedByServer(endedAt) {
    halted = "closed";
    cancelAll();
    space.endedByServer(typeof endedAt === "string" ? endedAt : null);
  }

  // Standort-Rückfrage (ADR 0015): pro Datei, nie für alle zusammen

  function askLocation(item) {
    clearTimer(item);
    item.xhr = null;
    item.state = ST.LOCATION;
    renderRow(item);
    questions.push(item);
    showNextQuestion();
    updateTotals();
  }

  function showNextQuestion() {
    if (dialogItem !== null || questions.length === 0) return;
    dialogItem = questions.shift();
    el.locationName.textContent = dialogItem.name;
    el.locationScrim.hidden = false;
    releaseTrap = core.trapFocus(el.locationDialog, function () {
      answerLocation(false);
    });
    el.locationSkip.focus();
    core.announce(t("uploadAnnounceLocation", { name: dialogItem.name }));
  }

  function closeDialog() {
    if (releaseTrap) {
      releaseTrap();
      releaseTrap = null;
    }
    el.locationScrim.hidden = true;
    dialogItem = null;
  }

  function answerLocation(keep) {
    var item = dialogItem;
    if (item === null) return;
    closeDialog();
    if (keep) {
      item.keepLocation = true;
      item.state = ST.WAITING;
    } else {
      item.state = ST.ABORTED;
    }
    renderRow(item);
    showNextQuestion();
    pump();
    el.queue.focus();
  }

  // Abbrechen, Ausblenden, Fortsetzen

  function stopItem(item) {
    clearTimer(item);
    var xhr = item.xhr;
    item.xhr = null;
    if (xhr) {
      try {
        xhr.abort();
      } catch (ignored) {
        // Der Upload ist schon beendet.
      }
    }
  }

  function abortItem(item) {
    if (PENDING.indexOf(item.state) === -1) return;
    if (item.state === ST.LOCATION) {
      questions = questions.filter(function (queued) {
        return queued !== item;
      });
      if (dialogItem === item) closeDialog();
    }
    stopItem(item);
    item.state = ST.ABORTED;
    renderRow(item);
    showNextQuestion();
    pump();
    el.queue.focus();
  }

  /** Alles abbrechen, was noch läuft oder wartet. Ein Gast, der den Space verlässt, sendet nichts mehr. */
  function cancelAll() {
    items.forEach(function (item) {
      if (PENDING.indexOf(item.state) !== -1 || item.state === ST.CHECKING) {
        stopItem(item);
        item.state = ST.ABORTED;
        renderRow(item);
      }
    });
    questions = [];
    closeDialog();
    halted = null;
    updateTotals();
  }

  function dismissItem(item) {
    if (FINAL.indexOf(item.state) === -1) return;
    items = items.filter(function (existing) {
      return existing !== item;
    });
    var row = rows[item.uid];
    if (row) {
      if (row.li.parentNode) row.li.parentNode.removeChild(row.li);
      delete rows[item.uid];
    }
    updateTotals();
    el.pick.focus();
  }

  function resume() {
    if (halted === null || halted === "closed") return;
    halted = null;
    setNotice("");
    items.forEach(renderRow);
    pump();
  }

  // Anzeige

  function setNotice(message) {
    el.notice.textContent = message || "";
  }

  function statusText(item) {
    switch (item.state) {
      case ST.CHECKING:
        return t("uploadChecking");
      case ST.WAITING:
        return halted !== null && halted !== "closed" ? t("uploadHalted") : t("uploadWaiting");
      case ST.UPLOADING:
        return t("uploadRunning", { percent: percentOf(item) });
      case ST.RETRYING:
        return t("uploadRetrying");
      case ST.LOCATION:
        return t("uploadLocation");
      case ST.DONE:
        return item.locationKept ? t("uploadDoneLocation") : t("uploadDone");
      case ST.DUPLICATE:
        return t("uploadDuplicate");
      case ST.REJECTED:
        return t(REJECT_KEYS[item.reason] || "uploadRejectUnavailable", { limit: core.formatBytes(item.limit) });
      case ST.ABORTED:
        return t("uploadAborted");
      case ST.FAILED:
        return t(ERROR_KEYS[item.reason] || "uploadErrorGeneric", { limit: core.formatBytes(item.limit) });
      default:
        return "";
    }
  }

  function actionsFor(item) {
    if (item.state === ST.DUPLICATE) return ["open", "dismiss"];
    if (FINAL.indexOf(item.state) !== -1) return ["dismiss"];
    if (item.state === ST.CHECKING || item.state === ST.DONE) return [];
    return ["cancel"];
  }

  function buildButton(label, onClick) {
    var button = document.createElement("button");
    button.type = "button";
    button.className = "secondary";
    button.textContent = label;
    button.addEventListener("click", onClick);
    return button;
  }

  function renderRow(item) {
    var row = rows[item.uid];
    if (!row) {
      var li = document.createElement("li");
      li.className = "q-item";
      var name = document.createElement("p");
      name.className = "q-name";
      var status = document.createElement("p");
      status.className = "q-status";
      var bar = document.createElement("div");
      bar.className = "bar";
      bar.setAttribute("role", "progressbar");
      bar.setAttribute("aria-valuemin", "0");
      bar.setAttribute("aria-valuemax", "100");
      bar.setAttribute("aria-valuenow", "0");
      bar.setAttribute("aria-label", item.name);
      var fill = document.createElement("i");
      bar.appendChild(fill);
      var actions = document.createElement("div");
      actions.className = "q-actions";
      li.appendChild(name);
      li.appendChild(status);
      li.appendChild(bar);
      li.appendChild(actions);
      el.queue.appendChild(li);
      row = { li: li, name: name, status: status, bar: bar, fill: fill, actions: actions, actionsKey: null };
      rows[item.uid] = row;
    }
    row.name.textContent = item.name + " (" + core.formatBytes(item.size) + ")";
    row.status.textContent = statusText(item);
    row.li.setAttribute("data-state", item.state);
    row.bar.hidden = item.state !== ST.UPLOADING;
    updateProgress(item);
    var key = actionsFor(item).join(",");
    if (key !== row.actionsKey) {
      row.actionsKey = key;
      row.actions.textContent = "";
      actionsFor(item).forEach(function (action) {
        if (action === "cancel") row.actions.appendChild(buildButton(t("uploadCancel"), function () { abortItem(item); }));
        if (action === "dismiss") row.actions.appendChild(buildButton(t("uploadDismiss"), function () { dismissItem(item); }));
        if (action === "open") {
          row.actions.appendChild(buildButton(t("uploadOpenItem"), function () { space.focusItem(item.mediaId); }));
        }
      });
    }
  }

  function updateProgress(item) {
    var row = rows[item.uid];
    if (!row) return;
    var percent = percentOf(item);
    row.fill.style.width = percent + "%";
    row.bar.setAttribute("aria-valuenow", String(percent));
  }

  function updateTotals() {
    var counted = items.filter(function (item) {
      return COUNTED.indexOf(item.state) !== -1;
    });
    var totalBytes = 0;
    var doneBytes = 0;
    var doneCount = 0;
    counted.forEach(function (item) {
      totalBytes += item.size;
      if (item.state === ST.DONE || item.state === ST.DUPLICATE) {
        doneCount += 1;
        doneBytes += item.size;
      } else if (item.state === ST.UPLOADING) {
        doneBytes += item.sent;
      }
    });
    var overall = totalBytes > 0 ? Math.min(100, Math.floor((doneBytes * 100) / totalBytes)) : 0;
    el.total.textContent = core.plural("uploadTotal", counted.length, { done: doneCount });
    el.totalFill.style.width = overall + "%";
    el.totalBar.setAttribute("aria-valuenow", String(overall));
    el.totalBar.hidden = counted.length === 0;
    var active = countState(PENDING) > 0;
    el.keepOpen.hidden = !active;
    el.resume.hidden = halted === null || halted === "closed";
    el.queue.hidden = items.length === 0;
  }

  function updateAvailability() {
    var usable = rules !== null;
    el.pick.disabled = !usable;
    el.input.disabled = !usable;
    el.zone.classList.toggle("is-disabled", !usable);
    if (!usable) setNotice(t("uploadUnavailable"));
  }

  function bind() {
    el.pick.addEventListener("click", function () {
      el.input.click();
    });
    el.input.addEventListener("change", function () {
      addFiles(el.input.files);
      el.input.value = "";
    });
    el.zone.addEventListener("dragover", function (event) {
      if (!hasFiles(event)) return;
      event.preventDefault();
      el.zone.classList.add("is-over");
    });
    el.zone.addEventListener("dragleave", function () {
      el.zone.classList.remove("is-over");
    });
    el.zone.addEventListener("drop", function (event) {
      if (!hasFiles(event)) return;
      event.preventDefault();
      el.zone.classList.remove("is-over");
      addFiles(event.dataTransfer.files);
    });
    // Eine Datei, die daneben fällt, soll den Browser nicht zum Öffnen der Datei bringen.
    window.addEventListener("dragover", function (event) {
      if (hasFiles(event)) event.preventDefault();
    });
    window.addEventListener("drop", function (event) {
      if (hasFiles(event)) event.preventDefault();
    });
    el.resume.addEventListener("click", resume);
    el.locationKeep.addEventListener("click", function () {
      answerLocation(true);
    });
    el.locationSkip.addEventListener("click", function () {
      answerLocation(false);
    });
  }

  if (el.section) {
    bind();
    updateTotals();
    // Bis die Regeln da sind, bleibt die Auswahl gesperrt (der Knopf ist im HTML zunächst deaktiviert).
    rulesReady.then(function () {
      updateAvailability();
      updateTotals();
    });
  }

  core.upload = {
    ready: function () {
      return rulesReady;
    },
    identify: identify,
    cancelAll: cancelAll
  };
})();
