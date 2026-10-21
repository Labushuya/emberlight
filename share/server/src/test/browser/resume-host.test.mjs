// Browser-Tests der Wiederaufnahme, Teil 2 (ADR 0018, Nachtrag): ein fremder Space ist eindeutig und verwirft die
// Sitzung. Ein dauerhaft nicht erreichbarer Gastgeber zeigt nach 30 Sekunden den Hinweis und „Neu beitreten“.
// Die Antwort auf `GET /api/v1/me` wird abgefangen (CDP). Die Frist der Seite ist im Test auf ein Zehntel verkürzt:
// ein Hilfsskript vor dem Laden macht `setTimeout` der Seite zehnmal schneller. Im Produktcode gibt es dafür keinen
// Haken, das Skript läuft nur in diesem Test. Die 30 Sekunden stehen also als 3 Sekunden im Test.

import assert from "node:assert/strict";
import { test } from "node:test";
import { seedSession } from "./lib/guest.mjs";
import { expectNoProblems, loadConfig, useBrowser } from "./lib/setup.mjs";

const config = loadConfig();
const browser = useBrowser();

// Texte der Seite in Englisch (Locale en-US), wie in en.json.
const CONNECTING = "Connecting to the host…";
const NO_INVITE = "Scan the host's QR code to join.";
const HINT = "Host not reachable. Are you on the same Wi-Fi? Is the host's app open?";
const REJOIN = "Join again";
const OTHER_SPACE = "fremder-space-ohne-bezug";

// Vor jedem Skript der Seite: Zeitgeber auf ein Zehntel, Bestätigungsdialog im Test (Antwort aus `__confirmAnswer`).
const PREPARE = `(() => {
  const native = window.setTimeout.bind(window);
  window.setTimeout = (fn, delay, ...args) => native(fn, Math.round((Number(delay) || 0) / 10), ...args);
  window.__confirms = [];
  window.__confirmAnswer = true;
  window.confirm = (message) => {
    window.__confirms.push(String(message));
    return window.__confirmAnswer;
  };
})();`;

const storageKey = () => `emberlight.guest:${config.base}:${config.session.spaceId}`;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** Wartet im Test (nicht in der Seite), bis `check()` wahr ist. */
async function until(check, what, timeout = 20000) {
  const started = Date.now();
  while (!(await check())) {
    if (Date.now() - started > timeout) throw new Error(`Zeitüberschreitung: ${what}`);
    await sleep(100);
  }
}

/** Seite mit gespeicherter Sitzung, Abfangen von `/api/v1/me`, dann das Ziel (mit oder ohne Einladung). */
async function openWithStoredSession(page, decide, target = `${config.base}/`) {
  await page.enableNetwork();
  // Sitzung auf einer statischen Datei ablegen: Keine App-Seite läuft noch und liest sie vor dem Abfangen (siehe seedSession).
  // Das Ziel liegt auf der App-Adresse, also ist es ein echter Seitenstart, auch mit Fragment.
  await page.goto(`${config.base}/i18n/en.json`);
  await page.eval("localStorage.clear()");
  await seedSession(page, config, config.session, { at: "/i18n/en.json" });
  const counter = { calls: 0 };
  await page.intercept("*/api/v1/me", () => {
    counter.calls += 1;
    return decide(counter.calls);
  });
  await page.onNewDocument(PREPARE);
  page.apiEvents.length = 0;
  await page.goto(target);
  return counter;
}

const SPACE_ANSWER = (spaceId) => ({ status: 200, body: { spaceId, participantId: "p", displayName: "Lena", role: "GUEST" } });
const BUSY = { status: 503, body: { error: "service_busy" }, headers: [{ name: "Retry-After", value: "5" }] };

test("Fremder Space (200 mit anderer ID): Sitzung weg, Beitrittsansicht, keine weiteren Anfragen", async () => {
  const page = await browser.page({ width: 1280, height: 900, locale: "en-US" });
  try {
    const counter = await openWithStoredSession(page, () => SPACE_ANSWER(OTHER_SPACE), `${config.base}/#i=${config.invite}`);
    await page.waitFor("!document.getElementById('join-section').hidden", { what: "Beitrittsansicht", timeout: 30000 });
    assert.equal(await page.eval("document.getElementById('space-section').hidden"), true, "Kein Space");
    assert.equal(await page.eval(`localStorage.getItem(${JSON.stringify(storageKey())}) === null`), true, "Sitzung entfernt");
    await sleep(1500);
    assert.equal(counter.calls, 1, `Anfragen an /api/v1/me: ${counter.calls}`);
    expectNoProblems(page, "Fremder Space mit Einladung");
  } finally {
    await page.close();
  }
});

test("Fremder Space ohne Einladung: Sitzung weg, QR-Hinweis, keine weiteren Anfragen", async () => {
  const page = await browser.page({ width: 1280, height: 900, locale: "en-US" });
  try {
    const counter = await openWithStoredSession(page, () => SPACE_ANSWER(OTHER_SPACE));
    await page.waitFor(`document.getElementById('status').textContent === ${JSON.stringify(NO_INVITE)}`, {
      what: "QR-Hinweis",
      timeout: 30000,
    });
    assert.equal(await page.eval("document.getElementById('join-section').hidden"), true, "Keine Beitrittsansicht ohne Einladung");
    assert.equal(await page.eval(`localStorage.getItem(${JSON.stringify(storageKey())}) === null`), true, "Sitzung entfernt");
    await sleep(1500);
    assert.equal(counter.calls, 1, `Anfragen an /api/v1/me: ${counter.calls}`);
    expectNoProblems(page, "Fremder Space ohne Einladung");
  } finally {
    await page.close();
  }
});

test("Gastgeber dauerhaft 503: Hinweis nach der Frist, „Neu beitreten“ verwirft nur die Sitzung", async () => {
  const page = await browser.page({ width: 1280, height: 900, locale: "en-US" });
  try {
    const counter = await openWithStoredSession(page, () => BUSY, `${config.base}/#i=${config.invite}`);
    // Vor der Frist (30 s, im Test 3 s) erscheint kein Hinweis.
    await until(() => counter.calls >= 4, "vier Anfragen");
    assert.equal(await page.eval("document.getElementById('unreachable').hidden"), true, "Kein Hinweis vor der Frist");

    await page.waitFor("!document.getElementById('unreachable').hidden", { what: "Hinweis nach der Frist", timeout: 20000 });
    assert.equal(await page.eval(`document.getElementById('unreachable').textContent.includes(${JSON.stringify(HINT)})`), true, "Hinweistext");
    assert.equal(await page.eval("document.getElementById('rejoin-button').textContent"), REJOIN);
    assert.equal(await page.eval("document.getElementById('status').textContent"), CONNECTING, "Verbindungsstatus");
    assert.equal(await page.eval("document.getElementById('join-section').hidden"), true, "Die Seite prüft noch, kein Beitritt");

    // Abbrechen im Dialog: nichts passiert, die Prüfung läuft weiter.
    await page.eval("window.__confirmAnswer = false");
    const before = counter.calls;
    await page.eval("document.getElementById('rejoin-button').click()");
    assert.equal(await page.eval("window.__confirms.length"), 1, "Bestätigung wurde abgefragt");
    await sleep(600);
    assert.equal(await page.eval(`localStorage.getItem(${JSON.stringify(storageKey())}) !== null`), true, "Sitzung bleibt nach Abbruch");
    assert.equal(await page.eval("document.getElementById('unreachable').hidden"), false, "Hinweis bleibt nach Abbruch");
    assert.ok(counter.calls > before, "Die Prüfung läuft nach Abbruch weiter");

    // Bestätigen: Sitzung weg, ohne weiteren Serveraufruf, Beitrittsansicht mit der Einladung aus dem Fragment.
    await page.eval("window.__confirmAnswer = true");
    await page.eval("document.getElementById('rejoin-button').click()");
    await page.waitFor("!document.getElementById('join-section').hidden", { what: "Beitrittsansicht", timeout: 10000 });
    await page.waitFor(`document.getElementById('preview').textContent.includes(${JSON.stringify(config.spaceName)})`, {
      what: "Vorschau des Beitritts",
      timeout: 10000,
    });
    assert.equal(await page.eval("document.getElementById('unreachable').hidden"), true, "Hinweis weg nach „Neu beitreten“");
    assert.equal(await page.eval(`localStorage.getItem(${JSON.stringify(storageKey())}) === null`), true, "Sitzung entfernt");
    const after = counter.calls;
    await sleep(1500);
    assert.equal(counter.calls, after, "Keine Anfragen an /api/v1/me nach „Neu beitreten“");
    expectNoProblems(page, "Gastgeber dauerhaft 503, Neu beitreten");
  } finally {
    await page.close();
  }
});

test("Gastgeber ohne Verbindung: Hinweis, dann Wiederherstellung vor dem Klick lässt Hinweis und Knopf verschwinden", async () => {
  const page = await browser.page({ width: 390, height: 844, locale: "en-US" });
  try {
    let recovered = false;
    await openWithStoredSession(page, () => (recovered ? "continue" : "fail"));
    await page.waitFor("!document.getElementById('unreachable').hidden", { what: "Hinweis nach der Frist", timeout: 20000 });
    assert.equal(await page.eval("document.getElementById('unreachable').contains(document.getElementById('rejoin-button'))"), true, "Knopf im Hinweis");

    recovered = true;
    await page.waitFor("!document.getElementById('space-section').hidden", { what: "Space nach der Wiederherstellung", timeout: 20000 });
    await page.waitFor("document.getElementById('unreachable').hidden", { what: "Hinweis verschwunden", timeout: 5000 });
    assert.equal(await page.eval("document.getElementById('unreachable').hidden"), true);
    assert.equal(await page.eval("window.__confirms.length"), 0, "Kein Dialog ohne Klick");
    expectNoProblems(page, "Netzfehler dauerhaft, dann Wiederherstellung");
  } finally {
    await page.close();
  }
});
