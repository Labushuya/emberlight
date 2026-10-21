// Hilfen für die Gäste-Seite in den Browser-Tests: Sitzung einrichten, Space öffnen, Zustand lesen (ADR 0019, 3.5a-3).

/**
 * Legt eine Sitzung im Browser ab, so wie der Beitritt sie speichert (ADR 0018). Danach lädt die Seite den Space mit
 * `GET /api/v1/me` und zeigt ihn. Die Seite muss vorher einmal auf dem Ursprung geladen sein.
 *
 * `at` ist die Seite, auf der die Sitzung abgelegt wird. Vorgabe ist die App-Seite. Ein Test, der danach Anfragen
 * abfängt, nimmt eine statische Datei (etwa `/i18n/en.json`). Die App-Seite würde sonst noch laufen, wenn die Sitzung
 * schon gesetzt ist, und ihre Anfrage an `/api/v1/me` ginge am Abfangen vorbei.
 */
export async function seedSession(page, config, session, { at = "/" } = {}) {
  const key = `emberlight.guest:${config.base}:${session.spaceId}`;
  await page.goto(`${config.base}${at}`);
  await page.eval(`localStorage.setItem(${JSON.stringify(key)}, ${JSON.stringify(JSON.stringify(session))})`);
}

/** Öffnet die Seite mit gespeicherter Sitzung und wartet, bis der Space angezeigt wird. */
// Protokoll der gespeicherten Sitzungen im Tab (nur Schlüssel und Zeitpunkt, nie Werte). Liegt im sessionStorage des Tabs,
// also über Neuladen hinweg, aber nicht in den anderen Tabs. Dient der Fehlersuche des Beitritts-Flakes.
const STORAGE_LOG = `(() => {
  if (window.__guestStorageLogInstalled) return;
  window.__guestStorageLogInstalled = true;
  const append = (entry) => {
    try {
      const list = JSON.parse(sessionStorage.getItem('__guestStorageLog') || '[]');
      list.push(entry);
      sessionStorage.setItem('__guestStorageLog', JSON.stringify(list.slice(-30)));
    } catch (ignored) {}
  };
  const where = () => ((new Error().stack || '').split('\\n')[3] || '').trim().slice(0, 120);
  const setItem = Storage.prototype.setItem;
  const removeItem = Storage.prototype.removeItem;
  Storage.prototype.setItem = function (key, value) {
    if (String(key).startsWith('emberlight.guest:')) append(['set', Date.now(), where()]);
    return setItem.call(this, key, value);
  };
  Storage.prototype.removeItem = function (key) {
    if (String(key).startsWith('emberlight.guest:')) append(['remove', Date.now(), where()]);
    return removeItem.call(this, key);
  };
})();`;

/** Befund für die Fehlersuche: Antworten der Seite auf /api/v1/me, Speicherprotokoll, Statuszeile. Ohne Tokens. */
async function diagnose(page, config, session) {
  const me = await apiGet(config, "/api/v1/me", session.token).catch((failure) => ({ status: String(failure.message) }));
  const seen = await page.eval(`({
    sessions: Object.keys(localStorage).filter((key) => key.startsWith('emberlight.guest:')).length,
    storageLog: JSON.parse(sessionStorage.getItem('__guestStorageLog') || '[]'),
    status: document.getElementById('status').textContent,
  })`);
  return `Node: /api/v1/me ${me.status}. Seite: ${JSON.stringify(page.apiEvents)}, ${JSON.stringify(seen)}`;
}

export async function openSpace(page, config, session) {
  await page.enableNetwork();
  await page.onNewDocument(STORAGE_LOG);
  await seedSession(page, config, session);
  page.apiEvents.length = 0;
  await page.goto(`${config.base}/`);
  try {
    await page.waitFor("!document.getElementById('space-section').hidden", { what: "Space-Ansicht", timeout: 30000 });
  } catch (failure) {
    // Befund für die Fehlersuche (Messung), dann der Fehler des Tests.
    const finding = await diagnose(page, config, session).catch((error) => `Befund fehlt: ${error.message}`);
    console.log(`Messung: Beitritt statt Space: ${finding}`);
    throw new Error(`${failure.message}\n${finding}`);
  }
  await page.waitFor("document.getElementById('space-name').textContent.length > 0", { what: "Name des Space" });
}

/** Anzahl sichtbarer Kacheln in der Liste. */
export function tileCount(page) {
  return page.eval("document.querySelectorAll('#grid .cell:not([hidden]) .tile').length");
}

/** Beschriftungen aller Kacheln, in Reihenfolge. */
export function captions(page) {
  return page.eval("[...document.querySelectorAll('#grid .cell:not([hidden]) .caption')].map((node) => node.textContent)");
}

/** Liest eine Adresse der API mit dem Token der Gastsitzung (nur für Prüfungen von außen). */
export async function apiGet(config, path, token) {
  const response = await fetch(`${config.base}${path}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    redirect: "error",
  });
  return { status: response.status, headers: response.headers, body: await response.json().catch(() => null) };
}
