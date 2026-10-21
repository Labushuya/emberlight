// Hilfen für die Gäste-Seite in den Browser-Tests: Sitzung einrichten, Space öffnen, Zustand lesen (ADR 0019, 3.5a-3).

/**
 * Legt eine Sitzung im Browser ab, so wie der Beitritt sie speichert (ADR 0018). Danach lädt die Seite den Space mit
 * `GET /api/v1/me` und zeigt ihn. Die Seite muss vorher einmal auf dem Ursprung geladen sein.
 */
export async function seedSession(page, config, session) {
  const key = `emberlight.guest:${config.base}:${session.spaceId}`;
  await page.goto(`${config.base}/`);
  await page.eval(`localStorage.setItem(${JSON.stringify(key)}, ${JSON.stringify(JSON.stringify(session))})`);
}

/** Öffnet die Seite mit gespeicherter Sitzung und wartet, bis der Space angezeigt wird. */
export async function openSpace(page, config, session) {
  await seedSession(page, config, session);
  await page.goto(`${config.base}/`);
  await page.waitFor("!document.getElementById('space-section').hidden", { what: "Space-Ansicht", timeout: 30000 });
  await page.waitFor("document.getElementById('space-name').textContent.length > 0", { what: "Name des Space" });
}

/** Anzahl sichtbarer Kacheln in der Liste. */
export function tileCount(page) {
  return page.eval("document.querySelectorAll('#grid .tile').length");
}

/** Beschriftungen aller Kacheln, in Reihenfolge. */
export function captions(page) {
  return page.eval("[...document.querySelectorAll('#grid .caption')].map((node) => node.textContent)");
}

/** Liest eine Adresse der API mit dem Token der Gastsitzung (nur für Prüfungen von außen). */
export async function apiGet(config, path, token) {
  const response = await fetch(`${config.base}${path}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    redirect: "error",
  });
  return { status: response.status, headers: response.headers, body: await response.json().catch(() => null) };
}
