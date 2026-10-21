/*
 * Gäste-Seite, Beitritt und Einstieg (ADR 0018, W1). Keine Abhängigkeiten, kein Build. Alle Daten werden nur als Text
 * ausgegeben (textContent oder value). HTML-Einfügefunktionen gibt es hier nicht (geprüft in den Tests). Der Kern liegt in
 * guest-core.js, die Space-Ansicht in guest-space.js. Die Ladereihenfolge steht in index.html.
 */
(function () {
  "use strict";

  var core = window.emberlightGuest;
  var text = core.text;
  var INVITE_FRAGMENT = /^#i=([A-Za-z0-9_-]{43})$/;

  // Das Einladungsgeheimnis lebt nur in dieser Variable. Es steht nie im Speicher des Browsers.
  var invite = null;
  // Name und Farbe aus der Vorschau. Sie gehen mit in die gespeicherte Sitzung (für die Ende-Ansicht nach Neuladen).
  var previewName = "";
  var previewAccent = null;

  var el = {
    heading: document.getElementById("heading"),
    joinForm: document.getElementById("join-form"),
    name: document.getElementById("name"),
    nameError: document.getElementById("name-error"),
    joinButton: document.getElementById("join-button"),
    preview: document.getElementById("preview")
  };

  function setNameError(message) {
    el.nameError.textContent = message || "";
    el.nameError.hidden = !message;
  }

  /** Vorschau vor dem Beitritt: „Du trittst ‚<Name>‘ bei“ in der Akzentfarbe. Name nur als Text (ADR 0018). */
  function showPreview(data) {
    el.preview.textContent = text("previewJoin", { name: data.spaceName });
    el.preview.style.color = core.accentPattern.test(data.accentColor) ? data.accentColor : "";
    el.preview.hidden = false;
  }

  function hidePreview() {
    el.preview.textContent = "";
    el.preview.style.color = "";
    el.preview.hidden = true;
  }

  /** Merkt das Ende aus einer gespeicherten Sitzung und zeigt die Ende-Ansicht. */
  function endedFromStored(stored) {
    var record = {
      spaceId: stored.spaceId,
      spaceName: stored.spaceName || "",
      endsAt: stored.endsAt || null
    };
    core.saveEnd(record);
    core.forgetSession(stored.spaceId);
    core.space.showEnded(record);
  }

  function openStored(stored, participant) {
    core.space.open({
      spaceId: stored.spaceId,
      participantId: participant.participantId,
      displayName: participant.displayName,
      token: stored.token,
      spaceName: stored.spaceName || "",
      accentColor: stored.accentColor || null,
      endsAt: stored.endsAt || null
    });
  }

  // Wartezeiten zwischen den Versuchen der Sitzungsprüfung in Millisekunden: erst kurz, dann alle fünf Sekunden.
  var RESUME_BACKOFF_MS = [500, 1000, 2000, 4000];
  var RESUME_STEADY_MS = 5000;

  /**
   * Prüft gespeicherte Sitzungen mit `GET /api/v1/me`. Gibt `true` zurück, wenn die Seite den Space oder das Ende zeigt.
   * Eine Sitzung wird nur bei 401 verworfen (ADR 0018). Vorübergehende Fehler (503, 429, 5xx, Netzfehler, unlesbare
   * Antwort) wiederholt die Seite mit Wartezeit. Dabei zeigt sie nie den QR-Hinweis, solange die Sitzung besteht.
   */
  function resumeStored() {
    var entries = core.storedSessions();
    if (entries.length === 0) return Promise.resolve(false);
    var stored = entries[0].value;
    var ended = core.endRecord(stored.spaceId);
    if (ended) {
      core.forgetSession(stored.spaceId);
      core.space.showEnded(ended);
      return Promise.resolve(true);
    }
    return checkStored(stored, 0);
  }

  function checkStored(stored, attempt) {
    return core.api("GET", "/api/v1/me", stored.token).then(
      function (result) {
        var status = result.status;
        if (status === 200 && !result.unreadable && result.data && result.data.spaceId === stored.spaceId) {
          openStored(stored, result.data);
          return true;
        }
        if (status === 410) {
          endedFromStored(stored);
          return true;
        }
        if (status === 401) {
          core.forgetSession(stored.spaceId);
          return false;
        }
        if (status === 429 || status >= 500 || (status === 200 && result.unreadable)) {
          return retryStored(stored, attempt, "connecting");
        }
        if (status === 403) {
          // Der Server lehnt Ort oder Adresse ab (falsches WLAN, andere Adresse). Die Sitzung bleibt.
          return retryStored(stored, attempt, "network");
        }
        // Alles andere ist unerwartet (etwa ein 200 für einen anderen Space). Die Sitzung bleibt, die Seite prüft weiter.
        return retryStored(stored, attempt, "generic");
      },
      function () {
        return retryStored(stored, attempt, "connecting");
      }
    );
  }

  /** Zeigt den Grund in der Statuszeile (nur wenn er sich ändert) und prüft nach der Wartezeit erneut. */
  function retryStored(stored, attempt, reason) {
    var message = reason === "network" ? text("network") : reason === "generic" ? text("genericError") : text("connecting");
    if (core.el.status.textContent !== message) core.setStatus(message);
    var delay = attempt < RESUME_BACKOFF_MS.length ? RESUME_BACKOFF_MS[attempt] : RESUME_STEADY_MS;
    return new Promise(function (resolve) {
      setTimeout(resolve, delay);
    }).then(function () {
      return checkStored(stored, attempt + 1);
    });
  }

  /**
   * Prüft das Geheimnis vor dem Beitritt (ADR 0018). Erst bei Erfolg zeigt die Seite Name und Farbe. Bei 403 ist die
   * Einladung verbraucht, bei 429 bleibt sie für einen späteren Versuch erhalten.
   */
  function previewInvite() {
    hidePreview();
    return core.api("POST", "/api/v1/join/preview", null, { inviteSecret: invite })
      .then(function (result) {
        if (result.status === 200 && result.data && typeof result.data.spaceName === "string") {
          previewName = result.data.spaceName;
          previewAccent = core.accentPattern.test(result.data.accentColor) ? result.data.accentColor : null;
          showPreview(result.data);
          return;
        }
        if (result.status === 429) {
          core.showSection("none");
          core.setStatus(text("rateLimited", { seconds: result.retryAfter || "" }));
          return;
        }
        invite = null;
        core.showSection("none");
        if (result.status === 403) core.setStatus(text("inviteInvalid"));
        else if (result.status === 410) core.setStatus(text("spaceClosed"));
        else core.setStatus(text("genericError"));
      })
      .catch(function () {
        core.setStatus(text("network"));
      });
  }

  function join(name) {
    el.joinButton.disabled = true;
    setNameError("");
    return core.api("POST", "/api/v1/join", null, { inviteSecret: invite, displayName: name })
      .then(function (result) {
        if (result.status === 201 && result.data) {
          invite = null;
          var joined = {
            spaceId: result.data.spaceId,
            participantId: result.data.participantId,
            displayName: result.data.displayName,
            token: result.data.token,
            spaceName: previewName,
            accentColor: previewAccent,
            endsAt: null
          };
          core.saveSession(joined);
          hidePreview();
          core.space.open(joined);
          core.setStatus(text("joinedAs", { name: joined.displayName }));
          return;
        }
        showJoinError(result);
      })
      .catch(function () {
        core.setStatus(text("network"));
      })
      .then(function () {
        el.joinButton.disabled = false;
      });
  }

  function showJoinError(result) {
    var data = result.data || {};
    if (result.status === 403) {
      invite = null;
      core.showSection("none");
      core.setStatus(text("inviteInvalid"));
    } else if (result.status === 409 && data.error === "name_taken") {
      setNameError(text("nameTaken", { suggestion: data.suggestion || "" }));
      el.name.value = data.suggestion || "";
      el.name.focus();
    } else if (result.status === 409) {
      core.showSection("none");
      core.setStatus(text("spaceFull"));
    } else if (result.status === 422) {
      var reason = data.reason === "too_long" ? "nameTooLong" : data.reason === "forbidden_character" ? "nameForbidden" : "nameEmpty";
      setNameError(text(reason));
      el.name.focus();
    } else if (result.status === 429) {
      core.setStatus(text("rateLimited", { seconds: result.retryAfter || "" }));
    } else if (result.status === 410) {
      invite = null;
      core.showSection("none");
      core.setStatus(text("spaceClosed"));
    } else {
      core.setStatus(text("genericError"));
    }
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
        core.showSection("none");
        core.setStatus(text("noInvite"));
        return;
      }
      join(name);
    });

    core.loadTexts().then(function () {
      core.applyTexts();
      // Eine gespeicherte Sitzung gewinnt. Ein Fragment wird dann ignoriert (ADR 0018).
      return resumeStored().then(function (resumed) {
        if (resumed) return;
        if (invite) {
          core.showSection("join");
          el.name.focus();
          previewInvite();
          return;
        }
        // Ohne Einladung zeigt die Seite ein gemerktes Ende des Space, sonst den Hinweis zum Scannen.
        var ended = core.lastEnded();
        if (ended) {
          core.space.showEnded(ended);
          return;
        }
        core.showSection("none");
        core.setStatus(text("noInvite"));
      });
    });
  }

  boot();
})();
