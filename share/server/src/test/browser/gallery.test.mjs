// Browser-Test: Medienansicht der Gäste-Seite (ADR 0019, Paket 3.5a-3). Raster mit Namen und Platzhaltern, Filter,
// Detail mit Bild und Video über signierte Adressen, Download-Header, Layout auf Telefon und PC, Zurückziehen mit
// Bestätigung und Restzeit. Der JVM-Test legt fünf Medien an (eigene und fremde, einer entfernt, einer mit Standort).

import assert from "node:assert/strict";
import { test } from "node:test";
import { apiGet, captions, openSpace, tileCount } from "./lib/guest.mjs";
import { expectNoProblems, loadConfig, useBrowser } from "./lib/setup.mjs";

const config = loadConfig();
const browser = useBrowser();
const session = config.session;

/** Die Kachel mit dieser Beschriftung und einem Bild (`img`) oder einem Platzhalter. */
const clickTile = (caption, kind) =>
  `(() => {
    const cell = [...document.querySelectorAll('#grid .cell')].find((node) =>
      node.querySelector('.caption').textContent === ${JSON.stringify(caption)} &&
      ${kind === "image" ? "node.querySelector('img')" : "node.querySelector('.placeholder')"});
    if (!cell) return false;
    cell.querySelector('button.tile').click();
    return true;
  })()`;

/** Prüft die Layoutregeln: kein Querscrollen, Tippziele mindestens 44 px. */
async function checkLayout(page, { tapTargets }) {
  const overflow = await page.eval("document.documentElement.scrollWidth <= window.innerWidth");
  assert.equal(overflow, true, "Querscrollen auf der Seite");
  if (!tapTargets) return;
  const small = await page.eval(`[...document.querySelectorAll('button, a.button-link, button.tile')]
    .filter((node) => node.getClientRects().length > 0)
    .map((node) => ({ name: node.id || node.className || node.textContent.trim().slice(0, 20), rect: node.getBoundingClientRect() }))
    .filter((entry) => entry.rect.width < 44 || entry.rect.height < 44)
    .map((entry) => entry.name)`);
  assert.deepEqual(small, [], "Tippziele unter 44 px");
}

async function galleryChecks(page, width) {
  await openSpace(page, config, session);
  await page.waitFor(`document.querySelectorAll('#grid .tile').length === ${config.expect.total}`, {
    what: "fünf Kacheln",
  });

  // Deutsch, Namen und Platzhalter.
  assert.equal(await page.eval("document.documentElement.lang"), "de");
  const names = await captions(page);
  for (const expected of ["von Lena", "(entfernt)", "von Omar", "von dir"]) {
    assert.ok(names.includes(expected), `Beschriftung fehlt: ${expected} in ${JSON.stringify(names)}`);
  }
  assert.equal(await page.eval("document.querySelectorAll('.badge.loc').length"), 1, "Kennzeichen Standort");
  assert.equal(await page.eval("document.querySelectorAll('.placeholder').length"), 2, "Platzhalter für Video und HEIC");

  // Die Bilder laden über die signierten Adressen, echte Bilder haben eine Breite.
  await page.waitFor(
    "[...document.querySelectorAll('img.thumb')].length === 3 && [...document.querySelectorAll('img.thumb')].every((img) => img.complete && img.naturalWidth > 0)",
    { what: "geladene Vorschaubilder" },
  );

  // Restzeit sichtbar, Speicher mit Anzeige.
  assert.match(await page.eval("document.getElementById('countdown').textContent"), /^Noch 1 h \d+ min$/);
  assert.match(await page.eval("document.getElementById('storage-text').textContent"), /belegt$/);

  await checkLayout(page, { tapTargets: width < 600 });

  // Filter „Meine“ und zurück.
  await page.eval("document.getElementById('filter-mine').click()");
  await page.waitFor(`document.querySelectorAll('#grid .tile').length === ${config.expect.mine}`, { what: "Filter Meine" });
  assert.equal(await page.eval("document.getElementById('filter-mine').getAttribute('aria-pressed')"), "true");
  assert.ok((await captions(page)).every((name) => name === "von dir"), "Filter Meine zeigt Fremdes");
  await page.eval("document.getElementById('filter-all').click()");
  await page.waitFor(`document.querySelectorAll('#grid .tile').length === ${config.expect.total}`, { what: "Filter Alle" });
  expectNoProblems(page, `Galerie ${width}`);
}

async function detailChecks(page) {
  // Bild: signierte Adresse, Download mit Anhang-Kopf.
  assert.equal(await page.eval(clickTile("von dir", "image")), true);
  await page.waitFor("!document.getElementById('detail-view').hidden", { what: "Detailansicht" });
  assert.equal(await page.eval("document.getElementById('detail-title').textContent"), "von dir");
  await page.waitFor("document.getElementById('viewer-image').naturalWidth > 0", { what: "Bild im Detail" });
  const href = await page.eval("document.getElementById('download-link').getAttribute('href')");
  assert.match(href, /^\/api\/v1\/media\/[A-Za-z0-9_-]{22}\/content\?pid=[A-Za-z0-9_-]{22}&exp=\d+&sig=[A-Za-z0-9_-]+$/);
  assert.equal(await page.eval("document.getElementById('download-link').hasAttribute('download')"), true);
  assert.equal(await page.eval("document.getElementById('withdraw-button').hidden"), false, "Zurückziehen bei eigenem Upload");

  const download = await fetch(`${config.base}${href}`, { redirect: "error" });
  assert.equal(download.status, 200);
  assert.match(download.headers.get("content-disposition") ?? "", /^attachment/);
  assert.equal(download.headers.get("x-content-type-options"), "nosniff");
  assert.equal(download.headers.get("cache-control"), "no-store");
  assert.equal(download.headers.get("content-type"), "image/jpeg");
  await download.arrayBuffer();

  await page.eval("document.getElementById('back-button').click()");
  await page.waitFor("!document.getElementById('list-view').hidden", { what: "Zurück zur Liste" });

  // Video: Wiedergabe über die Stream-Adresse, Metadaten geladen.
  assert.equal(await page.eval(clickTile("von dir", "placeholder")), true);
  await page.waitFor("!document.getElementById('viewer-video').hidden", { what: "Videoansicht" });
  await page.waitFor("document.getElementById('viewer-video').readyState >= 1", { what: "Videometadaten", timeout: 30000 });
  assert.match(await page.eval("document.getElementById('viewer-video').getAttribute('src')"), /\/stream\?pid=/);
  assert.equal(await page.eval("document.getElementById('viewer-video').error === null"), true, "Fehler der Wiedergabe");
  await page.eval("document.getElementById('back-button').click()");
  await page.waitFor("!document.getElementById('list-view').hidden", { what: "Zurück aus dem Video" });
}

test("Galerie auf dem Telefon: Raster, Filter, Detail, Download", async () => {
  const page = await browser.page({ width: 390, height: 844, locale: "de-DE" });
  try {
    await galleryChecks(page, 390);
    await detailChecks(page);
    expectNoProblems(page, "Telefon");
  } finally {
    await page.close();
  }
});

test("Galerie am PC: Raster, Detail und Zurückziehen mit Bestätigung", async () => {
  const page = await browser.page({ width: 1280, height: 900, locale: "de-DE" });
  try {
    await galleryChecks(page, 1280);
    await detailChecks(page);

    // Zurückziehen eigener Uploads: Bestätigung, Esc, Fokus.
    await page.eval(clickTile("von dir", "image"));
    await page.waitFor("!document.getElementById('detail-view').hidden", { what: "Detail vor dem Zurückziehen" });
    await page.waitFor("document.getElementById('viewer-image').naturalWidth > 0", { what: "Bild vor dem Zurückziehen" });
    await page.eval("document.getElementById('withdraw-button').click()");
    await page.waitFor("!document.getElementById('withdraw-scrim').hidden", { what: "Bestätigungsdialog" });
    assert.equal(await page.eval("document.querySelector('#withdraw-scrim [role=alertdialog]') !== null"), true);
    assert.equal(await page.eval("document.activeElement.id"), "withdraw-cancel", "Fokus beim Öffnen");
    await page.press("Tab");
    assert.equal(
      await page.eval("document.getElementById('withdraw-scrim').contains(document.activeElement)"),
      true,
      "Fokus verlässt den Dialog nicht",
    );
    await page.press("Escape");
    await page.waitFor("document.getElementById('withdraw-scrim').hidden", { what: "Esc schließt den Dialog" });
    assert.equal(await page.eval("document.activeElement.id"), "withdraw-button", "Fokus zurück auf den Auslöser");
    assert.equal(await tileCount(page), config.expect.total, "Esc hat nichts zurückgezogen");

    // Bestätigt: das Medium verschwindet für alle.
    await page.eval("document.getElementById('withdraw-button').click()");
    await page.waitFor("!document.getElementById('withdraw-scrim').hidden", { what: "Dialog erneut" });
    await page.eval("document.getElementById('withdraw-confirm').click()");
    await page.waitFor("!document.getElementById('list-view').hidden && document.getElementById('detail-view').hidden", {
      what: "Rückkehr zur Liste nach dem Zurückziehen",
    });
    await page.waitFor(`document.querySelectorAll('#grid .tile').length === ${config.expect.total - 1}`, {
      what: "Eine Kachel weniger",
    });
    assert.equal(await page.eval("document.getElementById('status').textContent"), "Medium zurückgezogen.");
    const remaining = await apiGet(config, "/api/v1/media?limit=100", session.token);
    assert.equal(remaining.status, 200);
    assert.equal(remaining.body.items.length, config.expect.total - 1, "Der Server hat das Medium noch");
    expectNoProblems(page, "PC");
  } finally {
    await page.close();
  }
});
