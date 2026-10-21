/*
 * Gäste-Seite, Space-Ansicht (ADR 0019, Abschnitte 1, 2, 6 und 7; Paket 3.5a-3). Raster, Filter „Meine“, Detail, Download,
 * Video mit stiller Erneuerung der Adresse, Zurückziehen eigener Uploads, Restzeit, Vorwarnungen und Ende. Nutzt
 * guest-core.js. Daten nur als Text (textContent) und Attribute aus geprüften Werten. Keine Inline-Stile (ADR 0018).
 */
(function () {
  "use strict";

  var core = window.emberlightGuest;
  var t = core.text;

  var PAGE_SIZE = 24;
  // Abfrage alle 10 bis 15 Sekunden, nur bei sichtbarer Seite. Bei Fehlern wächst die Pause bis 60 Sekunden.
  var POLL_MIN_MS = 10000;
  var POLL_MAX_MS = 15000;
  var BACKOFF_MAX_MS = 60000;
  var TICK_MS = 1000;
  // Vorwarnungen (Paket 3.5a-3): Banner ab 10 Minuten, Dialog ab 1 Minute.
  var WARN_BANNER_SECONDS = 600;
  var WARN_DIALOG_SECONDS = 60;
  // Gültigkeit der signierten Adressen (ADR 0019): Video 30 Minuten, sonst `urlsExpireInSeconds` (Vorgabe 10 Minuten).
  var STREAM_TTL_MS = 30 * 60 * 1000;
  var DEFAULT_TTL_MS = 10 * 60 * 1000;
  // Erneuerung kurz vor Ablauf. Zwischen zwei Versuchen liegen mindestens 15 Sekunden.
  var REFRESH_MARGIN_MS = 60 * 1000;
  var REFRESH_RETRY_MS = 15 * 1000;
  var ID_PATTERN = /^[A-Za-z0-9_-]{22}$/;
  // Bildladungen des Rasters (Paket 3.5a-4): höchstens vier gleichzeitig, nur für Kacheln nahe am Sichtbereich, damit
  // das Abfragen des Space nicht verdrängt wird (Chrome erlaubt je Host nur sechs Verbindungen). Ein Fehler lädt einmal
  // neu, mit frischer Adresse und 1 bis 2 Sekunden Wartezeit.
  var IMAGE_CONCURRENCY = 4;
  var IMAGE_NEAR_MARGIN = "300px 0px";
  var RETRY_MIN_MS = 1000;
  var RETRY_JITTER_MS = 1000;
  var VIDEO_CODECS = { avc1: "avc1.42E01E", hvc1: "hvc1.1.6.L93.B0" };
  var TYPE_NAMES = {
    "image/jpeg": "JPEG",
    "image/png": "PNG",
    "image/heic": "HEIC",
    "image/heif": "HEIF",
    "image/avif": "AVIF",
    "video/mp4": "MP4",
    "video/quicktime": "MOV",
    "video/3gpp": "3GP"
  };

  function byId(id) {
    return document.getElementById(id);
  }

  var el = {
    spaceHead: byId("space-head"),
    spaceName: byId("space-name"),
    countdown: byId("countdown"),
    leaveButton: byId("leave-button"),
    banner: byId("banner"),
    extendedBanner: byId("extended-banner"),
    extendedText: byId("extended-text"),
    lost: byId("lost"),
    listView: byId("list-view"),
    storageBar: byId("storage-bar"),
    storageFill: byId("storage-fill"),
    storageText: byId("storage-text"),
    storageWarning: byId("storage-warning"),
    filterAll: byId("filter-all"),
    filterMine: byId("filter-mine"),
    listEmpty: byId("list-empty"),
    grid: byId("grid"),
    loadMore: byId("load-more"),
    sentinel: byId("sentinel"),
    detailView: byId("detail-view"),
    backButton: byId("back-button"),
    detailTitle: byId("detail-title"),
    detailTime: byId("detail-time"),
    viewerImage: byId("viewer-image"),
    viewerVideo: byId("viewer-video"),
    viewerNote: byId("viewer-note"),
    factUploader: byId("fact-uploader"),
    factTime: byId("fact-time"),
    factSize: byId("fact-size"),
    factLocation: byId("fact-location"),
    downloadLink: byId("download-link"),
    withdrawButton: byId("withdraw-button"),
    withdrawHint: byId("withdraw-hint"),
    endingScrim: byId("ending-scrim"),
    endingOk: byId("ending-ok"),
    withdrawScrim: byId("withdraw-scrim"),
    withdrawCancel: byId("withdraw-cancel"),
    withdrawConfirm: byId("withdraw-confirm"),
    endedTitle: byId("ended-title"),
    endedText: byId("ended-text")
  };

  var state = {
    session: null,
    active: false,
    ended: false,
    spaceName: "",
    accentColor: null,
    endsAt: null,
    // Restzeit in Sekunden laut letzter Antwort (oder aus dem gespeicherten Ende), `null` ohne Ende.
    remaining: null,
    remainingBase: 0,
    revision: null,
    listRevision: null,
    usedBytes: 0,
    limitBytes: 0,
    imageMaxBytes: 0,
    videoMaxBytes: 0,
    storageWarning: false,
    items: [],
    nextCursor: null,
    listLoaded: false,
    listLoading: false,
    cycling: false,
    filter: "all",
    failures: 0,
    pollTimer: null,
    tickTimer: null,
    bannerShown: false,
    dialogShown: false,
    releaseEnding: null,
    releaseWithdraw: null,
    withdrawTrigger: null,
    openId: null,
    openTrigger: null,
    videoRetried: false,
    videoSwapping: false,
    lastStreamRefresh: 0,
    pendingSeek: null,
    pendingPlay: false,
    lastCountdown: null
  };

  function noop() {}

  function hasOwn(object, key) {
    return Object.prototype.hasOwnProperty.call(object, key);
  }

  function token() {
    return state.session ? state.session.token : null;
  }

  function isVideo(item) {
    return item.type.indexOf("video/") === 0;
  }

  function isHeic(item) {
    return item.type === "image/heic" || item.type === "image/heif";
  }

  function typeName(type) {
    return hasOwn(TYPE_NAMES, type) ? TYPE_NAMES[type] : type;
  }

  function uploaderOf(item) {
    if (item.mine) return t("youFact");
    if (item.uploaderRemoved || item.uploader === "") return t("uploaderRemoved");
    return item.uploader;
  }

  function captionOf(item) {
    if (item.mine) return t("byMe");
    if (item.uploaderRemoved || item.uploader === "") return t("uploaderRemoved");
    return t("byUploader", { name: item.uploader });
  }

  /** Eintrag aus der Antwort. Ungültige Einträge werden übergangen, damit eine fehlerhafte Antwort nichts zerstört. */
  function toItem(raw) {
    if (!raw || typeof raw !== "object" || typeof raw.id !== "string" || !ID_PATTERN.test(raw.id)) return null;
    var now = Date.now();
    var ttl =
      typeof raw.urlsExpireInSeconds === "number" && raw.urlsExpireInSeconds > 0
        ? raw.urlsExpireInSeconds * 1000
        : DEFAULT_TTL_MS;
    return {
      id: raw.id,
      mine: raw.mine === true,
      uploader: typeof raw.uploader === "string" ? raw.uploader : "",
      uploaderRemoved: raw.uploaderRemoved === true,
      type: typeof raw.type === "string" ? raw.type : "",
      size: typeof raw.size === "number" ? raw.size : 0,
      createdAt: typeof raw.createdAt === "number" ? raw.createdAt : null,
      locationKept: raw.locationKept === true,
      previewUrl: core.mediaPath(raw.previewUrl),
      previewState: typeof raw.previewState === "string" ? raw.previewState : null,
      viewUrl: core.mediaPath(raw.viewUrl),
      viewState: typeof raw.viewState === "string" ? raw.viewState : null,
      downloadUrl: core.mediaPath(raw.downloadUrl),
      streamUrl: core.mediaPath(raw.streamUrl),
      videoCodec: typeof raw.videoCodec === "string" ? raw.videoCodec : null,
      urlsUntil: now + ttl,
      streamUntil: now + STREAM_TTL_MS
    };
  }

  function needsRefresh(untilMs) {
    return Date.now() > untilMs - REFRESH_MARGIN_MS;
  }

  function findItem(id) {
    for (var i = 0; i < state.items.length; i++) {
      if (state.items[i].id === id) return state.items[i];
    }
    return null;
  }

  function dropItem(id) {
    state.items = state.items.filter(function (item) {
      return item.id !== id;
    });
  }

  function openItem() {
    return state.openId === null ? null : findItem(state.openId);
  }

  function matchesFilter(item) {
    return state.filter === "all" || item.mine;
  }

  function visibleCount() {
    return state.items.filter(matchesFilter).length;
  }

  /** Restzeit in Sekunden, lokal weitergezählt zwischen zwei Abfragen (monotone Uhr). */
  function remainingNow() {
    if (state.remaining === null) return null;
    return state.remaining - (performance.now() - state.remainingBase) / 1000;
  }

  function knownEndPassed() {
    var rem = remainingNow();
    if (rem !== null && rem <= 0) return true;
    return state.endsAt !== null && Date.parse(state.endsAt) <= Date.now();
  }

  // Ablauf der Zeitgeber

  function clearPollTimer() {
    if (state.pollTimer !== null) {
      clearTimeout(state.pollTimer);
      state.pollTimer = null;
    }
  }

  function stopTimers() {
    clearPollTimer();
    if (state.tickTimer !== null) {
      clearInterval(state.tickTimer);
      state.tickTimer = null;
    }
    state.active = false;
  }

  function nextDelay() {
    if (state.failures === 0) return POLL_MIN_MS + Math.random() * (POLL_MAX_MS - POLL_MIN_MS);
    return Math.min(BACKOFF_MAX_MS, POLL_MIN_MS * Math.pow(2, state.failures - 1));
  }

  function schedulePoll() {
    clearPollTimer();
    state.pollTimer = setTimeout(cycle, nextDelay());
  }

  /** Ein Abfragezyklus: Space-Stand, bei geänderter Revision die Liste. Läuft nie doppelt. */
  function cycle() {
    clearPollTimer();
    if (!state.active || state.ended || document.hidden || state.cycling) return;
    state.cycling = true;
    function done() {
      state.cycling = false;
      if (state.active && !state.ended && !document.hidden) schedulePoll();
    }
    refreshSpace()
      .then(function (outcome) {
        if (outcome === "ok" && state.revision !== state.listRevision) return refreshList();
        return undefined;
      })
      .then(done, done);
  }

  // Fehlerbehandlung

  function noteSuccess() {
    state.failures = 0;
    el.lost.hidden = true;
  }

  /** Verbindung weg. Ist das Ende bekannt und vorbei, ist der Space beendet (ADR 0019, Abschnitt 6). */
  function noteFailure() {
    if (!state.active || state.ended) return;
    state.failures += 1;
    if (knownEndPassed()) {
      endSpace();
      return;
    }
    el.lost.hidden = false;
    core.announce(t("connectionLost"));
  }

  function handleFailure(status, data) {
    if (status === 410) endSpace(false, data);
    else if (status === 401) sessionLost();
    else noteFailure();
  }

  // Lebenszyklus

  /** Öffnet die Space-Ansicht für eine Sitzung. Der Aufrufer zeigt die Statuszeile. */
  function open(session) {
    stopTimers();
    state.session = session;
    state.active = true;
    state.ended = false;
    state.failures = 0;
    state.items = [];
    state.nextCursor = null;
    state.listLoaded = false;
    state.listRevision = null;
    state.revision = null;
    state.spaceName = session.spaceName || "";
    state.accentColor = session.accentColor || null;
    state.endsAt = session.endsAt || null;
    state.remaining = null;
    if (state.endsAt) {
      var until = Date.parse(state.endsAt);
      if (!isNaN(until)) {
        state.remaining = (until - Date.now()) / 1000;
        state.remainingBase = performance.now();
      }
    }
    state.bannerShown = false;
    state.dialogShown = false;
    state.lastCountdown = null;
    closeDetail(false);
    hideDialogs();
    el.lost.hidden = true;
    renderHeader();
    renderCountdown(remainingNow());
    renderStorage();
    renderGrid();
    core.showSection("space");
    state.tickTimer = setInterval(tick, TICK_MS);
    cycle();
  }

  function forgetLocally(spaceId) {
    stopTimers();
    if (core.upload) core.upload.cancelAll();
    closeDetail(false);
    hideDialogs();
    state.session = null;
    if (spaceId) core.forgetSession(spaceId);
  }

  /**
   * Das Ende ist da (Countdown, 410 oder bekanntes Ende nach Verbindungsverlust). Das merkt sich die Seite. Nennt die
   * Antwort 410 ein `endedAt` (ADR 0014, Nachtrag), zeigt die Seite diese Stoppzeit.
   */
  function endSpace(atPlannedEnd, serverData) {
    if (state.ended || !state.session) return;
    state.ended = true;
    // Die Uhrzeit ist das geplante Ende, wenn der Countdown es erreicht hat. Hat der Gastgeber früher beendet, ist es der
    // Zeitpunkt, zu dem die Seite das Ende bemerkt hat (das Ende selbst meldet die API nicht, ADR 0017).
    var reached = atPlannedEnd === true && state.endsAt !== null;
    var serverEnd = serverData && typeof serverData.endedAt === "string" ? serverData.endedAt : null;
    var known = serverEnd && core.formatTime(serverEnd) ? serverEnd : null;
    var record = {
      spaceId: state.session.spaceId,
      spaceName: state.spaceName || state.session.spaceName || "",
      endsAt: reached ? state.endsAt : known || new Date().toISOString()
    };
    core.saveEnd(record);
    forgetLocally(record.spaceId);
    showEnded(record);
  }

  /** Ganzseitige Ende-Ansicht (W5). Auch nach Neuladen, aus dem gemerkten Ende. */
  function showEnded(record) {
    stopTimers();
    state.ended = true;
    closeDetail(false);
    hideDialogs();
    var name = record.spaceName || t("spaceUnnamed");
    var time = core.formatTime(record.endsAt);
    el.endedText.textContent = time ? t("endedWithTime", { name: name, time: time }) : t("endedNoTime", { name: name });
    core.showSection("ended");
    el.endedTitle.focus();
  }

  /** Die Sitzung ist für diesen Browser verloren (401). Die Seite zeigt nur die Meldung. */
  function sessionLost() {
    var spaceId = state.session ? state.session.spaceId : null;
    forgetLocally(spaceId);
    core.showSection("none");
    core.setStatus(t("sessionEnded"));
  }

  function leave() {
    var session = state.session;
    if (!session) return;
    el.leaveButton.disabled = true;
    core.api("DELETE", "/api/v1/me", session.token).then(
      function (result) {
        if (result.status === 204 || result.status === 401) {
          forgetLocally(session.spaceId);
          core.showSection("none");
          core.setStatus(t("left"));
        } else if (result.status === 410) {
          endSpace(false, result.data);
        } else {
          core.setStatus(t("genericError"));
        }
      },
      function () {
        core.setStatus(t("network"));
      }
    ).then(function () {
      el.leaveButton.disabled = false;
    });
  }

  // Space-Angaben und Kopfbereich

  /** Übernimmt die Angaben aus `GET /api/v1/space`. Liefert `false` bei ungültiger Antwort. */
  function applySpace(data) {
    if (!data || typeof data.name !== "string" || typeof data.revision !== "number") return false;
    var previousEnd = state.endsAt;
    state.spaceName = data.name;
    state.endsAt = typeof data.endsAt === "string" ? data.endsAt : null;
    state.remaining = typeof data.remainingSeconds === "number" ? data.remainingSeconds : null;
    state.remainingBase = performance.now();
    state.revision = data.revision;
    state.usedBytes = typeof data.usedBytes === "number" ? data.usedBytes : 0;
    state.limitBytes = typeof data.storageLimitBytes === "number" ? data.storageLimitBytes : 0;
    state.storageWarning = data.warning === true;
    var limits = data.limits || {};
    state.imageMaxBytes = typeof limits.imageMaxBytes === "number" ? limits.imageMaxBytes : 0;
    state.videoMaxBytes = typeof limits.videoMaxBytes === "number" ? limits.videoMaxBytes : 0;
    if (state.session) {
      state.session.spaceName = state.spaceName;
      state.session.endsAt = state.endsAt;
      state.session.accentColor = state.accentColor;
      core.saveSession(state.session);
    }
    renderHeader();
    renderCountdown(remainingNow());
    renderStorage();
    // Ein späteres Ende heißt: der Gastgeber hat verlängert (Paket 3.6a).
    if (previousEnd && state.endsAt && Date.parse(state.endsAt) > Date.parse(previousEnd)) {
      showExtended(state.endsAt);
    }
    return true;
  }

  /**
   * Der Space ist bis zu einer späteren Zeit verlängert. Die Seite zeigt das Banner in einer Live-Region, schließt eine offene
   * Vorwarnung und setzt die Merkungen zurück, damit die Warnungen zur neuen Endzeit wieder gelten. Die Restzeit rechnet
   * [renderCountdown] mit dem neuen Wert, auch die Hervorhebung fällt dort weg.
   */
  function showExtended(endsAt) {
    hideEndingDialog();
    el.banner.hidden = true;
    state.bannerShown = false;
    state.dialogShown = false;
    var time = core.formatTime(endsAt);
    el.extendedText.textContent = time ? t("extendedWithTime", { time: time }) : t("extendedNoTime");
    el.extendedBanner.hidden = false;
  }

  function refreshSpace() {
    var session = state.session;
    if (!session) return Promise.resolve("stopped");
    return core.api("GET", "/api/v1/space", session.token).then(
      function (result) {
        if (state.session !== session || state.ended) return "stopped";
        if (result.status === 200 && applySpace(result.data)) {
          noteSuccess();
          return "ok";
        }
        handleFailure(result.status, result.data);
        return "failed";
      },
      function () {
        if (state.session !== session) return "stopped";
        noteFailure();
        return "failed";
      }
    );
  }

  function renderHeader() {
    el.spaceName.textContent = state.spaceName;
    el.spaceHead.style.borderTopColor =
      state.accentColor && core.accentPattern.test(state.accentColor) ? state.accentColor : "";
  }

  function renderCountdown(rem) {
    var text;
    var urgent = false;
    if (rem === null || rem === undefined) {
      text = t("noEnd");
    } else {
      var secs = Math.max(0, Math.floor(rem));
      urgent = rem <= WARN_BANNER_SECONDS;
      if (secs >= 3600) {
        text = t("remainingHoursMinutes", { h: Math.floor(secs / 3600), m: Math.floor((secs % 3600) / 60) });
      } else if (secs >= 60) {
        text = core.plural("remainingMinutes", Math.floor(secs / 60));
      } else {
        text = core.plural("remainingSeconds", secs);
      }
    }
    if (text !== state.lastCountdown) {
      el.countdown.textContent = text;
      state.lastCountdown = text;
    }
    el.countdown.classList.toggle("urgent", urgent);
  }

  function renderStorage() {
    var hasLimit = state.limitBytes > 0;
    var label = hasLimit
      ? t("storageUsed", { used: core.formatBytes(state.usedBytes), limit: core.formatBytes(state.limitBytes) })
      : "";
    var percent = hasLimit ? Math.min(100, Math.round((state.usedBytes * 100) / state.limitBytes)) : 0;
    el.storageText.textContent = label;
    el.storageBar.setAttribute("aria-label", label);
    el.storageFill.style.width = percent + "%";
    el.storageWarning.hidden = !state.storageWarning;
  }

  /** Takt jede Sekunde: Restzeit, Vorwarnungen, Ende und stille Erneuerung der Video-Adresse. */
  function tick() {
    if (!state.active || state.ended) return;
    var rem = remainingNow();
    if (rem !== null) {
      if (rem <= 0) {
        endSpace(true);
        return;
      }
      renderCountdown(rem);
      var showBanner = rem <= WARN_BANNER_SECONDS;
      if (el.banner.hidden === showBanner) el.banner.hidden = !showBanner;
      if (showBanner && !state.bannerShown) {
        state.bannerShown = true;
        core.announce(t("endingBanner"));
      }
      if (rem <= WARN_DIALOG_SECONDS && !state.dialogShown) showEndingDialog();
    }
    refreshVideoIfExpiring();
  }

  // Vorwarnungen

  function showEndingDialog() {
    state.dialogShown = true;
    el.endingScrim.hidden = false;
    // Die Bestätigung ist Pflicht. Esc schließt den Dialog nicht, nur der Knopf.
    state.releaseEnding = core.trapFocus(el.endingScrim, null);
    el.endingOk.focus();
    core.announce(t("endingDialogTitle"));
  }

  function hideEndingDialog() {
    if (state.releaseEnding) {
      state.releaseEnding();
      state.releaseEnding = null;
    }
    el.endingScrim.hidden = true;
  }

  function hideDialogs() {
    hideEndingDialog();
    closeWithdraw(false);
  }

  // Liste

  function requestPage(cursor) {
    var session = state.session;
    var path = "/api/v1/media?limit=" + PAGE_SIZE + (cursor ? "&cursor=" + encodeURIComponent(cursor) : "");
    return core.api("GET", path, session ? session.token : null).then(
      function (result) {
        if (result.status === 200 && result.data && Array.isArray(result.data.items)) {
          noteSuccess();
          return {
            ok: true,
            items: result.data.items.map(toItem).filter(Boolean),
            nextCursor: typeof result.data.nextCursor === "string" ? result.data.nextCursor : null
          };
        }
        handleFailure(result.status, result.data);
        return { ok: false, items: [], nextCursor: null };
      },
      function () {
        noteFailure();
        return { ok: false, items: [], nextCursor: null };
      }
    );
  }

  /** Lädt die Liste neu, bis die bisherige Länge wieder erreicht ist. Nur bei geänderter Revision. */
  function refreshList() {
    if (state.listLoading || !state.session) return Promise.resolve();
    state.listLoading = true;
    var session = state.session;
    var revision = state.revision;
    var wanted = Math.max(PAGE_SIZE, state.items.length);
    var collected = [];

    function step(cursor) {
      return requestPage(cursor).then(function (page) {
        if (state.session !== session) return false;
        if (!page.ok) return false;
        collected = collected.concat(page.items);
        if (page.nextCursor && collected.length < wanted) return step(page.nextCursor);
        state.items = collected;
        state.nextCursor = page.nextCursor;
        state.listRevision = revision;
        state.listLoaded = true;
        state.listLoading = false;
        syncGrid();
        ensureVisible();
        return true;
      });
    }

    return step(null).then(
      function () {
        state.listLoading = false;
      },
      function () {
        state.listLoading = false;
      }
    );
  }

  var sentinelObserver = null;

  /** Ein Observer meldet nur Änderungen. Nach dem Anhängen den Sentinel neu beobachten, damit er bei Sichtbarkeit erneut meldet. */
  function recheckSentinel() {
    if (!sentinelObserver) return;
    sentinelObserver.unobserve(el.sentinel);
    sentinelObserver.observe(el.sentinel);
  }

  /** Weitere Seite anhängen. Mit Filter „Meine“ wird so lange nachgeladen, bis etwas Eigenes sichtbar ist. */
  function loadMore() {
    if (state.listLoading || !state.nextCursor || !state.session || state.ended) return Promise.resolve();
    state.listLoading = true;
    var session = state.session;
    var cursor = state.nextCursor;
    return requestPage(cursor).then(function (page) {
      state.listLoading = false;
      if (state.session !== session || !page.ok) return undefined;
      var known = {};
      state.items.forEach(function (item) {
        known[item.id] = true;
      });
      var fresh = page.items.filter(function (item) {
        return !known[item.id];
      });
      state.items = state.items.concat(fresh);
      state.nextCursor = page.nextCursor;
      syncGrid();
      recheckSentinel();
      if (visibleCount() === 0 && state.nextCursor) return loadMore();
      return undefined;
    });
  }

  function setFilter(value) {
    state.filter = value;
    el.filterAll.setAttribute("aria-pressed", value === "all" ? "true" : "false");
    el.filterMine.setAttribute("aria-pressed", value === "mine" ? "true" : "false");
    syncGrid();
    ensureVisible();
  }

  /** Mit Filter „Meine“ wird so lange nachgeladen, bis etwas Eigenes sichtbar ist. */
  function ensureVisible() {
    if (visibleCount() === 0 && state.nextCursor) loadMore();
  }

  /**
   * Baut das Raster neu auf. Nur beim Öffnen eines Space, sonst ändert [syncGrid] das Raster gezielt (Paket 3.5a-4).
   */
  function renderGrid() {
    Array.from(tiles.keys()).forEach(removeTile);
    while (el.grid.firstChild) el.grid.removeChild(el.grid.firstChild);
    syncGrid();
  }

  /**
   * Gleicht das Raster mit `state.items` ab. Neue Kacheln kommen an ihre Sortierposition, entfernte gehen, geänderte
   * werden gezielt aktualisiert. Ein vorhandenes Bild bleibt unangetastet, solange sein Bild gleich ist (Paket 3.5a-4).
   */
  function syncGrid() {
    var wanted = {};
    state.items.forEach(function (item) {
      wanted[item.id] = true;
    });
    Array.from(tiles.keys()).forEach(function (id) {
      if (!wanted[id]) removeTile(id);
    });
    var seen = {};
    var prev = null;
    state.items.forEach(function (item) {
      if (seen[item.id]) return;
      seen[item.id] = true;
      var entry = tiles.get(item.id) || createTile(item);
      updateTile(entry, item);
      entry.li.hidden = !matchesFilter(item);
      var expected = prev ? prev.nextSibling : el.grid.firstChild;
      if (entry.li !== expected) el.grid.insertBefore(entry.li, expected);
      prev = entry.li;
    });
    updateListState();
    // Liegt der Sentinel nach dem Laden schon im Rand, meldet der Observer keine Änderung. Darum neu beobachten.
    recheckSentinel();
  }

  function updateListState() {
    var count = visibleCount();
    el.loadMore.hidden = !state.nextCursor;
    if (!state.listLoaded) {
      el.listEmpty.textContent = t("loadingList");
      el.listEmpty.hidden = false;
      return;
    }
    el.listEmpty.textContent = state.filter === "mine" ? t("emptyMine") : t("emptyAll");
    el.listEmpty.hidden = count > 0 || state.nextCursor !== null;
  }

  /** Kacheln je Medien-ID. Eine Kachel bleibt, solange ihr Medium in der Liste steht (Paket 3.5a-4). */
  var tiles = new Map();

  /** Eine neue Kachel ohne Medium. Das Medium setzt [updateTile] (Bild oder Platzhalter). */
  function createTile(item) {
    var li = document.createElement("li");
    li.className = "cell";
    li.setAttribute("data-id", item.id);
    var button = document.createElement("button");
    button.type = "button";
    button.className = "tile";
    var caption = document.createElement("p");
    caption.className = "caption";
    li.appendChild(button);
    li.appendChild(caption);
    var entry = {
      id: item.id,
      li: li,
      button: button,
      caption: caption,
      media: null,
      mediaKind: null,
      placeholderState: null,
      imageKey: null,
      slot: null,
      queued: false,
      loaded: false,
      retried: false,
      exhausted: false,
      retrying: false,
      locBadge: null,
      vidBadge: null
    };
    if (isVideo(item)) {
      entry.vidBadge = badge("vid", t("videoBadge"));
      button.appendChild(entry.vidBadge);
    }
    button.addEventListener("click", function () {
      openDetail(entry.id, button);
    });
    tiles.set(item.id, entry);
    return entry;
  }

  /**
   * Gleicht eine Kachel mit dem Eintrag ab. Ein vorhandenes, erfolgreich geladenes Bild wird nicht neu gesetzt. Neue
   * Signaturen allein sind keine Änderung, nur ein anderes Bild (andere Route) ersetzt es.
   */
  function updateTile(entry, item) {
    var caption = captionOf(item);
    if (entry.caption.textContent !== caption) entry.caption.textContent = caption;
    var label = t(isVideo(item) ? "altVideo" : "altPhoto", { caption: caption });
    if (item.locationKept && entry.locBadge === null) {
      entry.locBadge = badge("loc", t("locationKept"));
      entry.button.insertBefore(entry.locBadge, entry.vidBadge);
    } else if (!item.locationKept && entry.locBadge !== null) {
      entry.locBadge.remove();
      entry.locBadge = null;
    }
    if (!entry.retrying) {
      var key = item.previewUrl && item.previewState === "ready" ? previewKey(item.previewUrl) : null;
      if (key !== null) {
        if (entry.imageKey !== key) {
          entry.imageKey = key;
          entry.retried = false;
          entry.exhausted = false;
          showTileImage(entry);
        } else if (entry.mediaKind !== "img" && !entry.exhausted) {
          showTileImage(entry);
        }
      } else {
        entry.imageKey = null;
        entry.exhausted = false;
        entry.retried = false;
        var pending = item.previewState === "failed" ? "failed" : "pending";
        if (entry.mediaKind !== "placeholder" || entry.placeholderState !== pending) {
          showPlaceholder(entry, item, pending);
        }
      }
    }
    applyLabel(entry, label);
  }

  /** Pfad ohne Abfrage: die Route der Vorschau. Die Signatur wechselt bei jeder Ausgabe, das Bild nicht. */
  function previewKey(url) {
    var cut = url.indexOf("?");
    return cut < 0 ? url : url.slice(0, cut);
  }

  function applyLabel(entry, label) {
    var node = entry.media;
    if (!node) return;
    var attribute = node.tagName === "IMG" ? "alt" : "aria-label";
    if (node.getAttribute(attribute) !== label) node.setAttribute(attribute, label);
  }

  /** Ersetzt das Medium der Kachel. Ein laufender Ladeplatz und ein Eintrag in der Warteschlange gehen dabei frei. */
  function replaceMedia(entry, node) {
    var old = entry.media;
    if (old) {
      if (entry.queued) {
        entry.queued = false;
        imageQueue = imageQueue.filter(function (queued) {
          return queued !== entry;
        });
      }
      if (entry.slot === old) releaseSlot(entry, old);
      entry.button.replaceChild(node, old);
    } else {
      entry.button.insertBefore(node, entry.button.firstChild);
    }
    entry.media = node;
  }

  /**
   * Raster: nur die Vorschau (`previewUrl`), sobald `ready`. Sonst Platzhalter. Das Original (`viewUrl`) lädt
   * das Raster nie, nur die Detailansicht (ADR 0019, Nachtrag 3.5a-4). Die Adresse setzt die Warteschlange.
   */
  function showTileImage(entry) {
    var img = document.createElement("img");
    img.className = "thumb";
    img.setAttribute("decoding", "async");
    img.addEventListener("load", function () {
      onImageLoad(entry, img);
    });
    img.addEventListener("error", function () {
      onImageError(entry, img);
    });
    entry.mediaKind = "img";
    entry.placeholderState = null;
    entry.loaded = false;
    replaceMedia(entry, img);
    watch(entry);
  }

  /** `pending`: „Vorschau folgt“. `failed`: „Keine Vorschau“. Die Kennzeichnung des Videos bleibt am Kachelrand. */
  function showPlaceholder(entry, item, kind) {
    var span = document.createElement("span");
    span.className = "placeholder";
    span.setAttribute("role", "img");
    var key = isVideo(item) ? "placeholderVideo" : "placeholderPhoto";
    if (kind === "failed") key += "Failed";
    span.textContent = t(key);
    entry.mediaKind = "placeholder";
    entry.placeholderState = kind;
    replaceMedia(entry, span);
  }

  // Warteschlange der Bildladungen

  var imageQueue = [];
  var imagesInFlight = 0;
  var nearObserver = null;

  function setupImageQueue() {
    // Ohne IntersectionObserver lädt die Warteschlange sofort. Die Obergrenze gilt trotzdem.
    if (!("IntersectionObserver" in window)) return;
    nearObserver = new IntersectionObserver(
      function (entries) {
        entries.forEach(function (entry) {
          if (!entry.isIntersecting) return;
          nearObserver.unobserve(entry.target);
          var tile = tiles.get(entry.target.getAttribute("data-id"));
          if (tile) enqueue(tile);
        });
      },
      { rootMargin: IMAGE_NEAR_MARGIN }
    );
  }

  /** Meldet die Kachel, sobald sie in die Nähe des Sichtbereichs kommt. Ohne Beobachter sofort. */
  function watch(entry) {
    if (entry.mediaKind !== "img" || entry.loaded) return;
    if (nearObserver === null) enqueue(entry);
    else nearObserver.observe(entry.li);
  }

  function enqueue(entry) {
    if (entry.queued || entry.slot || entry.loaded || entry.mediaKind !== "img") return;
    entry.queued = true;
    imageQueue.push(entry);
    pumpImages();
  }

  function pumpImages() {
    while (imagesInFlight < IMAGE_CONCURRENCY && imageQueue.length > 0) {
      var entry = imageQueue.shift();
      entry.queued = false;
      if (entry.mediaKind === "img" && entry.media && !entry.slot && !entry.loaded) startImage(entry);
    }
  }

  /** Gibt den Ladeplatz frei. Mit `img` nur, wenn der Platz zu diesem Bild gehört. */
  function releaseSlot(entry, img) {
    if (!entry.slot || (img && entry.slot !== img)) return;
    entry.slot = null;
    imagesInFlight -= 1;
    pumpImages();
  }

  /**
   * Startet den Abruf eines Bildes. Läuft die Adresse bald ab, wird sie vorher erneuert. Der Ladeplatz bleibt belegt,
   * bis das Bild fertig ist oder die Kachel das Medium wechselt.
   */
  function startImage(entry) {
    var img = entry.media;
    var item = findItem(entry.id);
    if (!item || !item.previewUrl) return;
    entry.slot = img;
    imagesInFlight += 1;
    if (!needsRefresh(item.urlsUntil)) {
      img.setAttribute("src", item.previewUrl);
      return;
    }
    refreshItem(entry.id).then(function () {
      if (entry.media !== img) return;
      var current = findItem(entry.id);
      if (current && current.previewUrl) img.setAttribute("src", current.previewUrl);
      else releaseSlot(entry, img);
    });
  }

  function onImageLoad(entry, img) {
    releaseSlot(entry, img);
    if (entry.media !== img) return;
    entry.loaded = true;
    entry.retried = false;
    entry.exhausted = false;
    if (nearObserver !== null) nearObserver.unobserve(entry.li);
  }

  /** Ein Fehler lädt einmal neu, mit frischer Adresse. Schlägt auch das fehl, bleibt der Platzhalter „Keine Vorschau“. */
  function onImageError(entry, img) {
    releaseSlot(entry, img);
    if (entry.media !== img) return;
    if (entry.retried) {
      exhaust(entry);
      return;
    }
    entry.retried = true;
    entry.retrying = true;
    var item = findItem(entry.id);
    if (!item) return;
    showPlaceholder(entry, item, "pending");
    setTimeout(
      function () {
        retryTile(entry);
      },
      RETRY_MIN_MS + Math.random() * RETRY_JITTER_MS
    );
  }

  function retryTile(entry) {
    var id = entry.id;
    refreshItem(id).then(function (fresh) {
      if (tiles.get(id) !== entry) return;
      entry.retrying = false;
      var item = findItem(id);
      if (!item) return;
      if (fresh && item.previewUrl && item.previewState === "ready") {
        showTileImage(entry);
        applyLabel(entry, t(isVideo(item) ? "altVideo" : "altPhoto", { caption: captionOf(item) }));
      } else if (fresh) {
        entry.imageKey = null;
        updateTile(entry, item);
      } else {
        exhaust(entry);
      }
    });
  }

  function exhaust(entry) {
    entry.exhausted = true;
    entry.retrying = false;
    var item = findItem(entry.id);
    if (item) showPlaceholder(entry, item, "failed");
  }

  /** Entfernt eine Kachel samt Beobachtung und Ladeplatz. */
  function removeTile(id) {
    var entry = tiles.get(id);
    if (!entry) return;
    if (nearObserver !== null) nearObserver.unobserve(entry.li);
    if (entry.queued) {
      entry.queued = false;
      imageQueue = imageQueue.filter(function (queued) {
        return queued !== entry;
      });
    }
    if (entry.slot) releaseSlot(entry);
    entry.retrying = false;
    tiles.delete(id);
    entry.li.remove();
  }

  function badge(kind, text) {
    var span = document.createElement("span");
    span.className = "badge " + kind;
    span.textContent = text;
    return span;
  }

  /** Aktualisiert eine Adresse über `GET /api/v1/media/{id}` (ADR 0019, Erneuerung). */
  function refreshItem(id) {
    var session = state.session;
    if (!session || !ID_PATTERN.test(id)) return Promise.resolve(null);
    return core.api("GET", "/api/v1/media/" + id, session.token).then(
      function (result) {
        if (state.session !== session) return null;
        if (result.status === 200) {
          var fresh = toItem(result.data);
          var existing = findItem(id);
          if (!fresh || !existing) return null;
          Object.assign(existing, fresh);
          noteSuccess();
          syncGrid();
          return existing;
        }
        if (result.status === 404) {
          dropItem(id);
          if (state.openId === id) closeDetail(true);
          syncGrid();
          core.setStatus(t("notFound"));
          return null;
        }
        handleFailure(result.status, result.data);
        return null;
      },
      function () {
        noteFailure();
        return null;
      }
    );
  }

  // Detail

  function openDetail(id, trigger) {
    var item = findItem(id);
    if (!item) {
      core.setStatus(t("notFound"));
      return;
    }
    state.openId = id;
    state.openTrigger = trigger || null;
    state.videoRetried = false;
    state.pendingSeek = null;
    state.pendingPlay = false;
    el.listView.hidden = true;
    el.detailView.hidden = false;
    renderDetail(item);
    el.detailTitle.focus();
    if (needsRefresh(item.urlsUntil)) refreshItem(id);
  }

  function closeDetail(restoreFocus) {
    if (state.openId === null && el.detailView.hidden) return;
    stopVideo();
    var trigger = state.openTrigger;
    state.openId = null;
    state.openTrigger = null;
    el.viewerImage.removeAttribute("src");
    el.viewerImage.hidden = true;
    el.detailView.hidden = true;
    el.listView.hidden = false;
    if (restoreFocus && trigger && trigger.isConnected) trigger.focus();
  }

  function renderDetail(item) {
    var label = t(isVideo(item) ? "altVideo" : "altPhoto", { caption: captionOf(item) });
    el.detailTitle.textContent = captionOf(item);
    el.detailTime.textContent = core.formatTime(item.createdAt) || "";
    el.viewerImage.hidden = true;
    el.viewerNote.hidden = true;
    el.viewerVideo.hidden = true;
    if (isVideo(item)) {
      el.viewerVideo.setAttribute("aria-label", label);
      if (item.streamUrl && canPlayVideo(item)) setVideoSource(item.streamUrl);
      else showVideoOnly();
    } else if (isHeic(item)) {
      // HEIC zeigt der Browser nicht. Die Ansicht gibt es nur, wenn der Server sie bereitgestellt hat (ADR 0019).
      if (item.viewState === "ready" && item.viewUrl) showImage(item.viewUrl, label);
      else showNote("previewUnavailable");
    } else if (item.viewUrl) {
      showImage(item.viewUrl, label);
    } else {
      showNote("previewFollows");
    }
    el.factUploader.textContent = uploaderOf(item);
    el.factTime.textContent = core.formatTime(item.createdAt) || "";
    el.factSize.textContent = core.formatBytes(item.size) + " · " + typeName(item.type);
    el.factLocation.textContent = item.locationKept ? t("locationKeptValue") : t("locationRemovedValue");
    if (item.downloadUrl) {
      el.downloadLink.setAttribute("href", item.downloadUrl);
      el.downloadLink.hidden = false;
    } else {
      el.downloadLink.removeAttribute("href");
      el.downloadLink.hidden = true;
    }
    el.withdrawButton.hidden = !item.mine;
    el.withdrawHint.hidden = !item.mine;
  }

  function showImage(url, label) {
    el.viewerImage.setAttribute("alt", label);
    el.viewerImage.setAttribute("src", url);
    el.viewerImage.hidden = false;
  }

  function showNote(key) {
    el.viewerNote.textContent = t(key);
    el.viewerNote.hidden = false;
  }

  el.viewerImage.addEventListener("error", function () {
    if (el.viewerImage.hidden) return;
    el.viewerImage.hidden = true;
    el.viewerImage.removeAttribute("src");
    showNote("previewUnavailable");
  });

  /**
   * Prüft den Codec gegen den Browser (ADR 0019, Abschnitt 2). Bei `null` (noch nicht erkannt) wird es versucht. Schlägt
   * die Wiedergabe fehl, bleibt nur der Download.
   */
  function canPlayVideo(item) {
    var codec = item.videoCodec;
    if (codec === null) return true;
    var coded = hasOwn(VIDEO_CODECS, codec) ? VIDEO_CODECS[codec] : /^[A-Za-z0-9.]{1,32}$/.test(codec) ? codec : null;
    if (coded === null || item.type === "") return false;
    var probe = document.createElement("video");
    return probe.canPlayType(item.type + '; codecs="' + coded + '"') !== "";
  }

  function setVideoSource(url) {
    el.viewerNote.hidden = true;
    el.viewerVideo.hidden = false;
    el.viewerVideo.setAttribute("src", url);
    el.viewerVideo.load();
  }

  function showVideoOnly() {
    stopVideo();
    el.viewerVideo.hidden = true;
    showNote("videoUnsupported");
  }

  function stopVideo() {
    el.viewerVideo.pause();
    el.viewerVideo.removeAttribute("src");
    el.viewerVideo.load();
    el.viewerVideo.hidden = true;
    state.pendingSeek = null;
    state.pendingPlay = false;
  }

  /** Setzt eine erneuerte Adresse ein und setzt die Wiedergabe an derselben Stelle fort. */
  function swapVideo(url) {
    var video = el.viewerVideo;
    state.pendingSeek = video.readyState > 0 ? video.currentTime : null;
    state.pendingPlay = !video.paused;
    setVideoSource(url);
  }

  el.viewerVideo.addEventListener("loadedmetadata", function () {
    state.videoRetried = false;
    if (state.pendingSeek !== null && isFinite(state.pendingSeek)) {
      try {
        el.viewerVideo.currentTime = state.pendingSeek;
      } catch (ignored) {
        // Manche Browser erlauben das Setzen erst später. Dann startet die Wiedergabe vorn.
      }
    }
    state.pendingSeek = null;
    if (state.pendingPlay) {
      state.pendingPlay = false;
      el.viewerVideo.play().catch(noop);
    }
  });

  /** Fehler der Wiedergabe: einmal mit neuer Adresse versuchen, danach nur Download (ADR 0019). */
  el.viewerVideo.addEventListener("error", function () {
    var item = openItem();
    if (!item || !el.viewerVideo.getAttribute("src")) return;
    if (state.videoRetried) {
      showVideoOnly();
      return;
    }
    state.videoRetried = true;
    state.pendingSeek = el.viewerVideo.currentTime || 0;
    state.pendingPlay = false;
    refreshItem(item.id).then(function (fresh) {
      if (!fresh || state.openId !== item.id) return;
      if (fresh.streamUrl) setVideoSource(fresh.streamUrl);
      else showVideoOnly();
    });
  });

  /** Stille Erneuerung der Stream-Adresse kurz vor dem Ablauf (30 Minuten, ADR 0019). */
  function refreshVideoIfExpiring() {
    var item = openItem();
    if (!item || !isVideo(item) || !item.streamUrl || state.videoSwapping) return;
    if (!needsRefresh(item.streamUntil)) return;
    if (Date.now() - state.lastStreamRefresh < REFRESH_RETRY_MS) return;
    state.lastStreamRefresh = Date.now();
    state.videoSwapping = true;
    var previous = item.streamUrl;
    refreshItem(item.id).then(function (fresh) {
      state.videoSwapping = false;
      if (fresh && fresh.streamUrl && fresh.streamUrl !== previous && state.openId === item.id) {
        swapVideo(fresh.streamUrl);
      }
    });
  }

  // Herunterladen und Zurückziehen

  /** Einzel-Download per Tipp. Ist die Adresse abgelaufen, wird sie vorher erneuert. */
  function onDownloadClick(event) {
    var item = openItem();
    if (!item || !needsRefresh(item.urlsUntil)) return;
    event.preventDefault();
    refreshItem(item.id).then(function (fresh) {
      if (fresh && fresh.downloadUrl) {
        el.downloadLink.setAttribute("href", fresh.downloadUrl);
        el.downloadLink.click();
      } else {
        core.setStatus(t("genericError"));
      }
    });
  }

  function openWithdraw() {
    if (!openItem()) return;
    state.withdrawTrigger = el.withdrawButton;
    el.withdrawScrim.hidden = false;
    state.releaseWithdraw = core.trapFocus(el.withdrawScrim, function () {
      closeWithdraw(true);
    });
    el.withdrawCancel.focus();
  }

  function closeWithdraw(restoreFocus) {
    if (state.releaseWithdraw) {
      state.releaseWithdraw();
      state.releaseWithdraw = null;
    }
    el.withdrawScrim.hidden = true;
    var trigger = state.withdrawTrigger;
    state.withdrawTrigger = null;
    if (restoreFocus && trigger && trigger.isConnected && !trigger.hidden) trigger.focus();
  }

  function confirmWithdraw() {
    var item = openItem();
    if (!item || !item.mine) {
      closeWithdraw(true);
      return;
    }
    var id = item.id;
    el.withdrawConfirm.disabled = true;
    core.api("DELETE", "/api/v1/media/" + id, token()).then(
      function (result) {
        el.withdrawConfirm.disabled = false;
        if (result.status === 204 || result.status === 404) {
          closeWithdraw(false);
          dropItem(id);
          if (state.openId === id) closeDetail(true);
          syncGrid();
          core.setStatus(result.status === 204 ? t("withdrawn") : t("notFound"));
          refreshSpace();
          return;
        }
        closeWithdraw(true);
        if (result.status === 403) core.setStatus(t("withdrawForbidden"));
        else handleFailure(result.status, result.data);
      },
      function () {
        el.withdrawConfirm.disabled = false;
        closeWithdraw(true);
        noteFailure();
      }
    );
  }

  // Ereignisse

  function init() {
    el.leaveButton.addEventListener("click", leave);
    el.filterAll.addEventListener("click", function () {
      setFilter("all");
    });
    el.filterMine.addEventListener("click", function () {
      setFilter("mine");
    });
    el.loadMore.addEventListener("click", function () {
      loadMore();
    });
    el.backButton.addEventListener("click", function () {
      closeDetail(true);
    });
    el.downloadLink.addEventListener("click", onDownloadClick);
    el.withdrawButton.addEventListener("click", openWithdraw);
    el.withdrawCancel.addEventListener("click", function () {
      closeWithdraw(true);
    });
    el.withdrawConfirm.addEventListener("click", confirmWithdraw);
    el.endingOk.addEventListener("click", hideEndingDialog);
    setupImageQueue();

    // Liste wird beim Scrollen nachgeladen. Ohne IntersectionObserver bleibt der Knopf „Mehr laden“.
    if ("IntersectionObserver" in window) {
      sentinelObserver = new IntersectionObserver(
        function (entries) {
          if (entries.some(function (entry) {
            return entry.isIntersecting;
          })) {
            loadMore();
          }
        },
        { rootMargin: "240px 0px" }
      );
      sentinelObserver.observe(el.sentinel);
    }

    // Abfrage nur bei sichtbarer Seite. Kehrt der Tab zurück, sofort aktualisieren.
    document.addEventListener("visibilitychange", function () {
      if (!document.hidden && state.active && !state.ended) cycle();
    });
  }

  /**
   * Zeigt den Eintrag mit der Medien-ID (Dublette beim Upload). Ist er nicht geladen, lädt die Liste neu.
   * Liefert ein Promise, das nach dem Versuch aufgelöst wird.
   */
  function focusItem(id) {
    if (!ID_PATTERN.test(id) || !state.session) return Promise.resolve(false);
    function locate() {
      var tile = el.grid.querySelector('li[data-id="' + id + '"] .tile');
      if (!tile) return false;
      tile.scrollIntoView({ block: "center" });
      tile.focus();
      return true;
    }
    if (locate()) return Promise.resolve(true);
    return refreshList().then(function () {
      return locate();
    });
  }

  init();

  // Schnittstelle für guest-upload.js (Paket 3.5b): lesen und anstoßen, keine inneren Zustände.
  core.space = {
    open: open,
    showEnded: showEnded,
    session: function () {
      return state.session;
    },
    limits: function () {
      return {
        imageMaxBytes: state.imageMaxBytes,
        videoMaxBytes: state.videoMaxBytes,
        freeBytes: Math.max(0, state.limitBytes - state.usedBytes)
      };
    },
    refresh: function () {
      return refreshSpace();
    },
    reloadList: function () {
      return refreshList();
    },
    endedByServer: function (endedAt) {
      if (state.session) endSpace(false, { endedAt: endedAt });
    },
    focusItem: function (id) {
      return focusItem(id);
    },
    // Nur für Tests: sofort ein Abfragezyklus, wie der Timer ihn auslöst. Kein zusätzlicher Netzwerkzugriff.
    pollNow: function () {
      clearPollTimer();
      cycle();
    }
  };
})();
