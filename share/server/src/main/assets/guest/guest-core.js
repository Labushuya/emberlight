/*
 * Gäste-Seite, gemeinsamer Kern (ADR 0018, ADR 0019). Keine Abhängigkeiten, kein Build. Alle Daten werden nur als Text
 * ausgegeben (textContent oder value, nie HTML). Die Datei legt `window.emberlightGuest` an. guest.js (Beitritt) und
 * guest-space.js (Space-Ansicht) nutzen sie. Die Ladereihenfolge steht in index.html.
 */
(function () {
  "use strict";

  var STORAGE_PREFIX = "emberlight.guest:";
  var END_PREFIX = "emberlight.guest-end:";
  // Akzentfarbe aus der Vorschau. Nur ein Hex-Wert wird übernommen, kein freier CSS-Wert (ADR 0018).
  var ACCENT_PATTERN = /^#[0-9A-Fa-f]{6}$/;
  // Signierte Medien-Adressen aus der API (ADR 0019): relativ, feste Route, Teilnehmer, Ablauf und Signatur in der Abfrage.
  var MEDIA_PATH = /^\/api\/v1\/media\/[A-Za-z0-9_-]{22}\/(view|content|stream|preview)\?pid=[A-Za-z0-9_-]{22}&exp=\d{1,12}&sig=[A-Za-z0-9_-]{1,128}$/;
  // Mindestabstand zwischen zwei Ansagen der Live-Region, damit Schirmleser nicht überflutet werden.
  var ANNOUNCE_GAP_MS = 4000;

  var core = {
    accentPattern: ACCENT_PATTERN,
    lang: /^de(\b|-)/i.test(navigator.language || "") ? "de" : "en",
    texts: {},
    el: {}
  };

  core.el = {
    status: document.getElementById("status"),
    live: document.getElementById("live"),
    joinSection: document.getElementById("join-section"),
    spaceSection: document.getElementById("space-section"),
    endedSection: document.getElementById("ended-section")
  };

  /** Prüft, ob eine Medien-Adresse aus der API die erwartete Form hat. Sonst gilt sie als fehlend. */
  core.mediaPath = function (value) {
    return typeof value === "string" && MEDIA_PATH.test(value) ? value : null;
  };

  core.hasText = function (key) {
    return Object.prototype.hasOwnProperty.call(core.texts, key);
  };

  core.text = function (key, values) {
    var out = core.hasText(key) ? core.texts[key] : key;
    if (values) {
      Object.keys(values).forEach(function (name) {
        out = out.split("{" + name + "}").join(String(values[name]));
      });
    }
    return out;
  };

  /** Pluralform: `<base>One` bei genau 1, sonst `<base>Other` (ADR 0019, Pluralformen als eigene Schlüssel). */
  core.plural = function (base, count, values) {
    var merged = Object.assign({}, values || {}, { count: count });
    return core.text(base + (count === 1 ? "One" : "Other"), merged);
  };

  core.applyTexts = function () {
    document.documentElement.lang = core.lang;
    document.querySelectorAll("[data-i18n]").forEach(function (node) {
      var key = node.getAttribute("data-i18n");
      if (core.hasText(key)) node.textContent = core.texts[key];
    });
    document.querySelectorAll("[data-i18n-aria]").forEach(function (node) {
      var key = node.getAttribute("data-i18n-aria");
      if (core.hasText(key)) node.setAttribute("aria-label", core.texts[key]);
    });
    if (core.hasText("title")) document.title = core.texts.title;
  };

  core.loadTexts = function () {
    return fetch("/i18n/" + core.lang + ".json", { cache: "no-store", credentials: "omit" })
      .then(function (response) {
        return response.ok ? response.json() : {};
      })
      .then(function (loaded) {
        core.texts = loaded || {};
      })
      .catch(function () {
        core.texts = {};
      });
  };

  /**
   * Eine Anfrage an die API. Liefert Status, Daten und Retry-After, nie Text als HTML. Ist der Körper kein gültiges JSON,
   * ist `unreadable` gesetzt und `data` leer. Die Aufrufer dürfen dann nicht daraus auf den Inhalt schließen.
   */
  core.api = function (method, path, token, body) {
    var headers = {};
    if (token) headers["Authorization"] = "Bearer " + token;
    if (body !== undefined) headers["Content-Type"] = "application/json";
    return fetch(path, {
      method: method,
      headers: headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      credentials: "omit",
      cache: "no-store",
      redirect: "error",
      referrerPolicy: "no-referrer"
    }).then(function (response) {
      var retryAfter = response.headers.get("Retry-After");
      if (response.status === 204) {
        return { status: 204, data: null, retryAfter: retryAfter, unreadable: false };
      }
      return response.json().then(
        function (data) {
          return { status: response.status, data: data, retryAfter: retryAfter, unreadable: false };
        },
        function () {
          return { status: response.status, data: {}, retryAfter: retryAfter, unreadable: true };
        }
      );
    });
  };

  /** Zeigt nur den gewünschten Hauptbereich: "join", "space", "ended" oder "none" (nur Statuszeile). */
  core.showSection = function (name) {
    core.el.joinSection.hidden = name !== "join";
    core.el.spaceSection.hidden = name !== "space";
    core.el.endedSection.hidden = name !== "ended";
  };

  /** Sichtbare Statuszeile. Zugleich gedrosselt an die Live-Region. */
  core.setStatus = function (message) {
    core.el.status.textContent = message || "";
    core.announce(message);
  };

  var lastAnnounced = 0;
  var pendingAnnounce = null;
  var announceTimer = null;

  core.announce = function (message) {
    if (!message) return;
    pendingAnnounce = message;
    if (announceTimer !== null) return;
    var wait = Math.max(0, lastAnnounced + ANNOUNCE_GAP_MS - Date.now());
    announceTimer = setTimeout(flushAnnounce, wait);
  };

  function flushAnnounce() {
    announceTimer = null;
    lastAnnounced = Date.now();
    core.el.live.textContent = pendingAnnounce || "";
    pendingAnnounce = null;
  }

  // Speicher im Browser. Jeder Zugriff kann scheitern (privates Fenster, voller Speicher), das ist kein Fehler der Seite.

  function prefixFor(prefix) {
    return prefix + window.location.origin + ":";
  }

  function readItem(key) {
    try {
      var value = JSON.parse(window.localStorage.getItem(key));
      return value && typeof value === "object" ? value : null;
    } catch (ignored) {
      return null;
    }
  }

  function writeItem(key, value) {
    try {
      window.localStorage.setItem(key, JSON.stringify(value));
    } catch (ignored) {
      // Ohne Speicher gilt der Beitritt nur bis zum Neuladen der Seite.
    }
  }

  function removeItem(key) {
    try {
      window.localStorage.removeItem(key);
    } catch (ignored) {
      // Nichts zu tun.
    }
  }

  function entriesWith(prefix) {
    var list = [];
    try {
      var base = prefixFor(prefix);
      for (var i = 0; i < window.localStorage.length; i++) {
        var key = window.localStorage.key(i);
        if (key && key.indexOf(base) === 0) {
          var value = readItem(key);
          if (value) list.push({ key: key, value: value });
        }
      }
    } catch (ignored) {
      // Kein Speicher verfügbar, dann gibt es nichts zu finden.
    }
    return list;
  }

  /** Gespeicherte Sitzungen dieser Herkunft. Der Schlüssel trägt die Space-ID. */
  core.storedSessions = function () {
    return entriesWith(STORAGE_PREFIX).filter(function (entry) {
      return typeof entry.value.token === "string";
    });
  };

  /** Speichert Sitzung und die Angaben zum Space (Name, Farbe, Ende) für die Anzeige nach Neuladen. */
  core.saveSession = function (session) {
    writeItem(prefixFor(STORAGE_PREFIX) + session.spaceId, session);
  };

  core.forgetSession = function (spaceId) {
    removeItem(prefixFor(STORAGE_PREFIX) + spaceId);
  };

  /** Merkt das Ende eines Space je Space-ID (ADR 0019, Ende-Hinweise). Es bleibt bei Neuladen erhalten. */
  core.saveEnd = function (record) {
    writeItem(prefixFor(END_PREFIX) + record.spaceId, {
      spaceId: record.spaceId,
      spaceName: record.spaceName || "",
      endsAt: record.endsAt || null,
      storedAt: Date.now()
    });
  };

  core.endRecord = function (spaceId) {
    return readItem(prefixFor(END_PREFIX) + spaceId);
  };

  /** Das jüngste gemerkte Ende dieser Herkunft oder `null`. */
  core.lastEnded = function () {
    var latest = null;
    entriesWith(END_PREFIX).forEach(function (entry) {
      if (!latest || (entry.value.storedAt || 0) > (latest.storedAt || 0)) latest = entry.value;
    });
    return latest;
  };

  /** Uhrzeit HH:MM in der Sprache der Seite, oder `null` bei ungültigen Angaben. */
  core.formatTime = function (value) {
    if (value === null || value === undefined || value === "") return null;
    var date = typeof value === "number" ? new Date(value) : new Date(String(value));
    if (isNaN(date.getTime())) return null;
    return date.toLocaleTimeString(core.lang, { hour: "2-digit", minute: "2-digit" });
  };

  core.formatBytes = function (bytes) {
    var units = [
      ["unitGiB", 1024 * 1024 * 1024],
      ["unitMiB", 1024 * 1024],
      ["unitKiB", 1024]
    ];
    for (var i = 0; i < units.length; i++) {
      if (bytes >= units[i][1]) {
        return formatNumber(bytes / units[i][1]) + " " + core.text(units[i][0]);
      }
    }
    return formatNumber(bytes) + " " + core.text("unitBytes");
  };

  function formatNumber(value) {
    return new Intl.NumberFormat(core.lang, { maximumFractionDigits: 1 }).format(value);
  }

  /**
   * Fokusfalle für einen Dialog (ADR 0019, Barrierefreiheit). Tab und Umschalt-Tab bleiben im Container. Mit
   * `onEscape` schließt Esc den Dialog. Liefert eine Funktion, die die Falle wieder löst.
   */
  core.trapFocus = function (container, onEscape) {
    function focusables() {
      return Array.prototype.filter.call(
        container.querySelectorAll("button, a[href], input, select, textarea"),
        function (node) {
          return !node.disabled && !node.hidden;
        }
      );
    }

    function onKey(event) {
      if (event.key === "Escape" && onEscape) {
        event.preventDefault();
        onEscape();
        return;
      }
      if (event.key !== "Tab") return;
      var items = focusables();
      if (items.length === 0) return;
      var first = items[0];
      var last = items[items.length - 1];
      var inside = container.contains(document.activeElement);
      if (event.shiftKey && (!inside || document.activeElement === first)) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && (!inside || document.activeElement === last)) {
        event.preventDefault();
        first.focus();
      }
    }

    document.addEventListener("keydown", onKey, true);
    return function release() {
      document.removeEventListener("keydown", onKey, true);
    };
  };

  window.emberlightGuest = core;
})();
