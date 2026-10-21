/*
 * Gäste-Seite (ADR 0018, W1). Keine Abhängigkeiten, kein Build. Alle Daten werden nur als Text ausgegeben (textContent
 * oder value). HTML-Einfügefunktionen gibt es hier nicht (geprüft in den Tests).
 */
(function () {
  "use strict";

  var STORAGE_PREFIX = "emberlight.guest:";
  var INVITE_FRAGMENT = /^#i=([A-Za-z0-9_-]{43})$/;
  // Akzentfarbe aus der Vorschau. Nur ein Hex-Wert wird übernommen, kein freier CSS-Wert (ADR 0018).
  var ACCENT_PATTERN = /^#[0-9A-Fa-f]{6}$/;

  // Das Einladungsgeheimnis lebt nur in dieser Variable. Es steht nie im Speicher des Browsers.
  var invite = null;
  // Aktive Sitzung: { spaceId, participantId, displayName, token }. Nur im Speicher und im Eintrag des Browsers.
  var session = null;
  var texts = {};
  var lang = /^de(\b|-)/i.test(navigator.language || "") ? "de" : "en";

  var el = {
    heading: document.getElementById("heading"),
    status: document.getElementById("status"),
    joinSection: document.getElementById("join-section"),
    joinForm: document.getElementById("join-form"),
    name: document.getElementById("name"),
    nameError: document.getElementById("name-error"),
    joinButton: document.getElementById("join-button"),
    joinedSection: document.getElementById("joined-section"),
    welcome: document.getElementById("welcome"),
    spaceName: document.getElementById("space-name"),
    leaveButton: document.getElementById("leave-button"),
    preview: document.getElementById("preview")
  };

  function hasText(key) {
    return Object.prototype.hasOwnProperty.call(texts, key);
  }

  function text(key, values) {
    var out = hasText(key) ? texts[key] : key;
    if (values) {
      Object.keys(values).forEach(function (name) {
        out = out.split("{" + name + "}").join(String(values[name]));
      });
    }
    return out;
  }

  function applyTexts() {
    document.documentElement.lang = lang;
    document.querySelectorAll("[data-i18n]").forEach(function (node) {
      var key = node.getAttribute("data-i18n");
      if (hasText(key)) node.textContent = texts[key];
    });
    if (hasText("title")) document.title = texts.title;
  }

  function setStatus(message) {
    el.status.textContent = message || "";
  }

  /** Vorschau vor dem Beitritt: „Du trittst ‚<Name>‘ bei“ in der Akzentfarbe. Name nur als Text (ADR 0018). */
  function showPreview(data) {
    el.preview.textContent = text("previewJoin", { name: data.spaceName });
    el.preview.style.color = ACCENT_PATTERN.test(data.accentColor) ? data.accentColor : "";
    el.preview.hidden = false;
  }

  function hidePreview() {
    el.preview.textContent = "";
    el.preview.style.color = "";
    el.preview.hidden = true;
  }

  function setNameError(message) {
    el.nameError.textContent = message || "";
    el.nameError.hidden = !message;
  }

  function showSection(section) {
    el.joinSection.hidden = section !== "join";
    el.joinedSection.hidden = section !== "joined";
  }

  function storagePrefix() {
    return STORAGE_PREFIX + window.location.origin + ":";
  }

  /** Alle gespeicherten Sitzungen dieser Herkunft. Der Schlüssel trägt die Space-ID. */
  function storedEntries() {
    var entries = [];
    try {
      for (var i = 0; i < window.localStorage.length; i++) {
        var key = window.localStorage.key(i);
        if (key && key.indexOf(storagePrefix()) === 0) {
          var value = JSON.parse(window.localStorage.getItem(key));
          if (value && typeof value.token === "string") entries.push({ key: key, value: value });
        }
      }
    } catch (ignored) {
      // Kein Speicher verfügbar, dann gibt es keine Wiederkehr.
    }
    return entries;
  }

  function saveSession(value) {
    try {
      window.localStorage.setItem(storagePrefix() + value.spaceId, JSON.stringify(value));
    } catch (ignored) {
      // Ohne Speicher gilt der Beitritt nur bis zum Neuladen der Seite.
    }
  }

  function forgetEntry(key) {
    try {
      window.localStorage.removeItem(key);
    } catch (ignored) {
      // Nichts zu tun.
    }
  }

  /** Eine Anfrage an die API. Liefert Status, Daten und Retry-After, nie Text als HTML. */
  function api(method, path, token, body) {
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
        return { status: 204, data: null, retryAfter: retryAfter };
      }
      return response
        .json()
        .catch(function () {
          return {};
        })
        .then(function (data) {
          return { status: response.status, data: data, retryAfter: retryAfter };
        });
    });
  }

  function loadTexts() {
    return fetch("/i18n/" + lang + ".json", { cache: "no-store", credentials: "omit" })
      .then(function (response) {
        return response.ok ? response.json() : {};
      })
      .then(function (loaded) {
        texts = loaded || {};
      })
      .catch(function () {
        texts = {};
      });
  }

  function showJoined(current) {
    session = current;
    hidePreview();
    showSection("joined");
    el.welcome.textContent = text("joinedAs", { name: current.displayName });
    el.spaceName.textContent = "";
    setStatus("");
    api("GET", "/api/v1/space", current.token).then(function (result) {
      if (result.status === 200 && result.data && typeof result.data.name === "string") {
        el.spaceName.textContent = result.data.name;
      } else {
        handleSessionLost(result.status, current);
      }
    }).catch(function () {
      setStatus(text("network"));
    });
  }

  /** Nach 401 oder 410 ist die Sitzung für diesen Browser verloren: lokal löschen (ADR 0018). */
  function handleSessionLost(status, current) {
    if (status === 401 || status === 410) {
      forgetEntry(storagePrefix() + current.spaceId);
      session = null;
      showSection("none");
      setStatus(status === 410 ? text("spaceClosed") : text("sessionEnded"));
      return true;
    }
    return false;
  }

  /** Prüft gespeicherte Sitzungen mit `GET /me`. Gibt `true` zurück, wenn eine gültig ist. */
  function resumeStored() {
    var entries = storedEntries();
    if (entries.length === 0) return Promise.resolve(false);
    var entry = entries[0];
    return api("GET", "/api/v1/me", entry.value.token)
      .then(function (result) {
        if (result.status === 200 && result.data && result.data.spaceId === entry.value.spaceId) {
          showJoined({
            spaceId: entry.value.spaceId,
            participantId: result.data.participantId,
            displayName: result.data.displayName,
            token: entry.value.token
          });
          return true;
        }
        if (result.status === 401 || result.status === 410 || result.status === 200) {
          // Mit 200 und fremder Space-ID: die gespeicherte Sitzung passt nicht zu diesem Space.
          forgetEntry(entry.key);
        }
        if (result.status === 410) setStatus(text("spaceClosed"));
        return false;
      })
      .catch(function () {
        setStatus(text("network"));
        return false;
      });
  }

  /**
   * Prüft das Geheimnis vor dem Beitritt (ADR 0018). Erst bei Erfolg zeigt die Seite Name und Farbe. Bei 403 ist die
   * Einladung verbraucht, bei 429 bleibt sie für einen späteren Versuch erhalten.
   */
  function previewInvite() {
    hidePreview();
    return api("POST", "/api/v1/join/preview", null, { inviteSecret: invite })
      .then(function (result) {
        if (result.status === 200 && result.data && typeof result.data.spaceName === "string") {
          showPreview(result.data);
          return;
        }
        if (result.status === 429) {
          showSection("none");
          setStatus(text("rateLimited", { seconds: result.retryAfter || "" }));
          return;
        }
        invite = null;
        showSection("none");
        if (result.status === 403) setStatus(text("inviteInvalid"));
        else if (result.status === 410) setStatus(text("spaceClosed"));
        else setStatus(text("genericError"));
      })
      .catch(function () {
        setStatus(text("network"));
      });
  }

  function join(name) {
    el.joinButton.disabled = true;
    setNameError("");
    return api("POST", "/api/v1/join", null, { inviteSecret: invite, displayName: name })
      .then(function (result) {
        if (result.status === 201 && result.data) {
          invite = null;
          var joined = {
            spaceId: result.data.spaceId,
            participantId: result.data.participantId,
            displayName: result.data.displayName,
            token: result.data.token
          };
          saveSession(joined);
          showJoined(joined);
          return;
        }
        showJoinError(result);
      })
      .catch(function () {
        setStatus(text("network"));
      })
      .then(function () {
        el.joinButton.disabled = false;
      });
  }

  function showJoinError(result) {
    var data = result.data || {};
    if (result.status === 403) {
      invite = null;
      showSection("none");
      setStatus(text("inviteInvalid"));
    } else if (result.status === 409 && data.error === "name_taken") {
      setNameError(text("nameTaken", { suggestion: data.suggestion || "" }));
      el.name.value = data.suggestion || "";
      el.name.focus();
    } else if (result.status === 409) {
      showSection("none");
      setStatus(text("spaceFull"));
    } else if (result.status === 422) {
      var reason = data.reason === "too_long" ? "nameTooLong" : data.reason === "forbidden_character" ? "nameForbidden" : "nameEmpty";
      setNameError(text(reason));
      el.name.focus();
    } else if (result.status === 429) {
      setStatus(text("rateLimited", { seconds: result.retryAfter || "" }));
    } else if (result.status === 410) {
      invite = null;
      showSection("none");
      setStatus(text("spaceClosed"));
    } else {
      setStatus(text("genericError"));
    }
  }

  function leave() {
    if (!session) return;
    var current = session;
    el.leaveButton.disabled = true;
    api("DELETE", "/api/v1/me", current.token)
      .then(function (result) {
        if (result.status === 204 || result.status === 401 || result.status === 410) {
          forgetEntry(storagePrefix() + current.spaceId);
          session = null;
          showSection("none");
          setStatus(text("left"));
        } else {
          setStatus(text("genericError"));
        }
      })
      .catch(function () {
        setStatus(text("network"));
      })
      .then(function () {
        el.leaveButton.disabled = false;
      });
  }

  function boot() {
    var fragment = window.location.hash || "";
    var match = INVITE_FRAGMENT.exec(fragment);
    if (match) invite = match[1];
    // Das Geheimnis verlässt die Adresse sofort. Es bleibt nicht in Verlauf oder Lesezeichen (ADR 0018).
    if (fragment) window.history.replaceState(null, "", window.location.pathname + window.location.search);

    el.joinForm.addEventListener("submit", function (event) {
      event.preventDefault();
      var name = el.name.value.trim();
      if (!name) {
        setNameError(text("nameEmpty"));
        el.name.focus();
        return;
      }
      if (!invite) {
        showSection("none");
        setStatus(text("noInvite"));
        return;
      }
      join(name);
    });
    el.leaveButton.addEventListener("click", leave);

    loadTexts().then(function () {
      applyTexts();
      // Eine gespeicherte Sitzung gewinnt. Ein Fragment wird dann ignoriert (ADR 0018).
      return resumeStored().then(function (resumed) {
        if (resumed) return;
        if (invite) {
          showSection("join");
          el.name.focus();
          previewInvite();
        } else {
          showSection("none");
          setStatus(text("noInvite"));
        }
      });
    });
  }

  boot();
})();
