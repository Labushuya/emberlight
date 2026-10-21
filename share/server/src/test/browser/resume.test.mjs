// Browser-Tests: Wiederaufnahme der gespeicherten Sitzung bei vorübergehenden Fehlern (ADR 0018, Paket 3.5a-3).
// Die Seite bekommt die Antwort auf `GET /api/v1/me` abgefangen: zweimal 503 oder zweimal Netzfehler, dann der echte
// Server. Erwartet wird: Die Seite landet im Space, die Beitrittsansicht und der QR-Hinweis erscheinen nie, und der
// Verbindungsstatus war sichtbar.

import assert from "node:assert/strict";
import { test } from "node:test";
import { seedSession } from "./lib/guest.mjs";
import { expectNoProblems, loadConfig, useBrowser } from "./lib/setup.mjs";

const config = loadConfig();
const browser = useBrowser();

// Texte der Seite in Englisch (Locale en-US), wie in en.json.
const CONNECTING = "Connecting to the host…";
const NO_INVITE = "Scan the host's QR code to join.";

// Zeichnet vom ersten Skript des Dokuments an auf, welche Statuszeile und welche Bereiche sichtbar sind. Jede Änderung
// landet in `window.__seen`. Das Dokument ist die Seite nach dem Neuladen, die Vorbereitung hat ein eigenes Dokument.
const OBSERVER = `document.addEventListener('DOMContentLoaded', () => {
  const seen = (window.__seen = []);
  const read = () => ({
    status: document.getElementById('status')?.textContent ?? '',
    join: !document.getElementById('join-section').hidden,
    space: !document.getElementById('space-section').hidden,
  });
  let last = '';
  const record = () => {
    const now = read();
    const key = JSON.stringify(now);
    if (key !== last) { last = key; seen.push(now); }
  };
  new MutationObserver(record).observe(document.body, {
    subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: ['hidden'],
  });
  record();
});`;

/** Eine Wiederaufnahme mit `busy` Fehlern (`fail` = Netzfehler, sonst 503), dann dem echten Server. */
async function resumeAfter(kind, failures) {
  const page = await browser.page({ width: 1280, height: 900, locale: "en-US" });
  try {
    await page.enableNetwork();
    // Ausgangslage: keine Sitzung aus einem früheren Test (der Speicher des Browsers ist für alle Tests einer Datei gleich).
    // Die Sitzung wird auf einer statischen Datei abgelegt, damit keine App-Seite sie vor dem Abfangen liest (siehe seedSession).
    await page.goto(`${config.base}/i18n/en.json`);
    await page.eval("localStorage.clear()");
    await seedSession(page, config, config.session, { at: "/i18n/en.json" });
    let calls = 0;
    await page.intercept("*/api/v1/me", () => {
      calls += 1;
      if (calls > failures) return "continue";
      return kind === "fail" ? "fail" : { status: 503, body: { error: "service_busy" }, headers: [{ name: "Retry-After", value: "5" }] };
    });
    await page.onNewDocument(OBSERVER);
    page.apiEvents.length = 0;
    await page.goto(`${config.base}/`);
    await page.waitFor("!document.getElementById('space-section').hidden", {
      what: "Space nach dem Wiederversuch",
      timeout: 30000,
    });
    assert.equal(await page.eval("document.getElementById('space-name').textContent.length > 0"), true, "Name des Space");

    // Die Seite hat /api/v1/me genau so oft gefragt wie nötig: die Fehler und dann der Erfolg.
    assert.equal(calls, failures + 1, `Anfragen an /api/v1/me: ${calls}`);
    const answers = page.apiEvents.filter((event) => event.event === "antwort" || event.event === "fehler");
    assert.equal(answers.length, failures + 1, `Antworten der Seite: ${JSON.stringify(page.apiEvents)}`);

    const seen = await page.eval("window.__seen");
    assert.equal(
      seen.some((entry) => entry.join),
      false,
      "Die Beitrittsansicht war nie sichtbar",
    );
    assert.equal(
      seen.some((entry) => entry.status === NO_INVITE),
      false,
      "Der QR-Hinweis war nie sichtbar",
    );
    assert.equal(
      seen.some((entry) => entry.status === CONNECTING),
      true,
      `Der Verbindungsstatus war sichtbar (Verlauf: ${JSON.stringify(seen.map((entry) => entry.status))})`,
    );
    expectNoProblems(page, `Wiederaufnahme nach ${failures} Fehlern (${kind})`);
  } finally {
    await page.close();
  }
}

test("Zwei Antworten 503, dann der Space: Verbindungsstatus, kein QR-Hinweis", async () => {
  await resumeAfter("busy", 2);
});

test("Zwei Netzfehler, dann der Space: Verbindungsstatus, kein QR-Hinweis", async () => {
  await resumeAfter("fail", 2);
});
