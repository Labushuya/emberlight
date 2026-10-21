// Browser-Tests: Upload im Browser (Paket 3.5b, ADR 0014, ADR 0015, ADR 0019). Die Datei gehört zu einem Server mit einem
// Gast (Lena), einem Vorab-Upload von Omar (für die Dublette) und Testdateien unter `config.fixtures`.
// Geprüft werden: Fortschritt bis fertig, Warteschlange mit zwei Uploads, Dublette, Vorabprüfung ohne Anfrage,
// Standort-Rückfrage pro Datei, Abbrechen, Wiederholung bei 429, Ablage am PC und die Tastatur-Alternative.

import assert from "node:assert/strict";
import { test } from "node:test";
import { openSpace, tileCount, apiGet } from "./lib/guest.mjs";
import { control, expectNoProblems, loadConfig, useBrowser } from "./lib/setup.mjs";
import {
  clickItemButton,
  queue,
  selectFiles,
  sleep,
  uploadRequests,
  waitForItem,
} from "./lib/upload.mjs";

const config = loadConfig();
const browser = useBrowser();

// Der Space enthält schon den Vorab-Upload von Omar. Erst wenn die Liste ihn zeigt, zählen die Tests die Kacheln.
async function openUpload() {
  const page = await browser.page({ width: 1280, height: 900, locale: "en-US" });
  await page.enableNetwork();
  await openSpace(page, config, config.session);
  await page.waitFor("document.querySelectorAll('#grid .tile').length >= 1", { what: "Liste geladen", timeout: 30000 });
  return page;
}

test("Bild mit Fortschritt bis fertig, Eintrag im Raster, Live-Region nur bei Abschluss", async () => {
  const page = await openUpload();
  try {
    await page.throttleUpload(150 * 1024);
    const before = await tileCount(page);
    await page.eval(`window.__live = 0;
      new MutationObserver(() => { window.__live += 1; })
        .observe(document.getElementById('live'), { childList: true, characterData: true, subtree: true });`);

    await selectFiles(page, [config.fixtures.gross]);
    await waitForItem(page, "gross.jpg", "uploading");
    await page.waitFor(
      `(() => { const fill = document.querySelector('#upload-queue .q-item .bar i'); return !!fill && parseFloat(fill.style.width) >= 10; })()`,
      { what: "Fortschritt ab 10 %", timeout: 20000 },
    );
    const running = (await queue(page)).find((item) => item.name.startsWith("gross.jpg"));
    assert.match(running.status, /^Uploading, \d+ %$/, "Statustext mit Prozent");
    assert.equal(await page.eval("!document.getElementById('upload-keepopen').hidden"), true, "Hinweis: Seite offen lassen");
    assert.equal(
      await page.eval("!document.getElementById('upload-total-bar').hidden"),
      true,
      "Gesamtfortschritt sichtbar",
    );

    await waitForItem(page, "gross.jpg", "done", 40000);
    await page.waitFor(`document.querySelectorAll('#grid .tile').length === ${before + 1}`, {
      what: "neuer Eintrag im Raster",
      timeout: 30000,
    });
    assert.equal(await page.eval("document.getElementById('upload-keepopen').hidden"), true, "Hinweis danach aus");
    // Pro Fortschrittsereignis darf die Live-Region nicht ansagen. Erlaubt: die Abschlussmeldung und ein Puffer.
    assert.ok((await page.eval("window.__live")) <= 2, "Live-Region gedrosselt");
    expectNoProblems(page, "Upload Bild");
  } finally {
    await page.close();
  }
});

test("Zwei Uploads laufen gleichzeitig, der dritte wartet", async () => {
  const page = await openUpload();
  try {
    await page.throttleUpload(100 * 1024);
    await selectFiles(page, [config.fixtures.gross2, config.fixtures.gross3, config.fixtures.gross4]);
    await page.waitFor(`document.querySelectorAll('#upload-queue .q-item[data-state="uploading"]').length === 2`, {
      what: "zwei laufende Uploads",
      timeout: 20000,
    });
    const states = (await queue(page)).map((item) => item.state).sort();
    assert.deepEqual(states, ["uploading", "uploading", "waiting"]);
    await page.waitFor(`document.querySelectorAll('#upload-queue .q-item[data-state="done"]').length === 3`, {
      what: "alle drei fertig",
      timeout: 90000,
    });
    expectNoProblems(page, "Zwei parallel");
  } finally {
    await page.close();
  }
});

test("Bereits vorhandene Datei: Hinweis und Sprung zum Eintrag, kein Fehler", async () => {
  const page = await openUpload();
  try {
    await selectFiles(page, [config.fixtures.bekannt]);
    await waitForItem(page, "bekannt.jpg", "duplicate");
    const status = (await queue(page)).find((item) => item.name.startsWith("bekannt.jpg")).status;
    assert.equal(status, "Already in the space");
    assert.equal(await clickItemButton(page, "bekannt.jpg", "Go to item"), true, "Knopf „Zum Eintrag“");
    await page.waitFor(
      `document.activeElement?.closest('li')?.getAttribute('data-id') === ${JSON.stringify(config.knownId)}`,
      { what: "Fokus auf dem vorhandenen Eintrag" },
    );
    expectNoProblems(page, "Dublette");
  } finally {
    await page.close();
  }
});

test("Vorab-Ablehnung: falsches Format und zu große Datei werden nicht gesendet", async () => {
  const page = await openUpload();
  try {
    await selectFiles(page, [config.fixtures.falsch, config.fixtures.zugross]);
    await waitForItem(page, "falsch.jpg", "rejected");
    await waitForItem(page, "zugross.jpg", "rejected");
    const list = await queue(page);
    assert.match(list.find((item) => item.name.startsWith("falsch.jpg")).status, /not allowed/);
    assert.match(list.find((item) => item.name.startsWith("zugross.jpg")).status, /Too large/);
    await sleep(1500);
    assert.equal(uploadRequests(page), 0, "keine Anfrage an den Upload");
    expectNoProblems(page, "Vorab-Ablehnung");
  } finally {
    await page.close();
  }
});

test("Standort-Rückfrage pro Datei: Nein speichert nichts, Ja lädt mit Standort hoch", async () => {
  const page = await openUpload();
  try {
    const before = await tileCount(page);
    await selectFiles(page, [config.fixtures.standort]);
    await page.waitFor(`!document.getElementById('location-scrim').hidden`, { what: "Standort-Rückfrage" });
    assert.match(
      await page.eval("document.getElementById('location-text').textContent"),
      /Location can.t be removed\. Upload with location anyway\?/,
    );
    assert.equal(uploadRequests(page), 1, "bis zur Rückfrage genau eine Anfrage");

    await page.eval("document.getElementById('location-skip').click()");
    await page.waitFor(`document.getElementById('location-scrim').hidden`, { what: "Dialog geschlossen" });
    await waitForItem(page, "standort.mp4", "aborted");
    await sleep(1500);
    assert.equal(uploadRequests(page), 1, "nach „Nicht hochladen“ keine weitere Anfrage");
    assert.equal(await tileCount(page), before, "nichts gespeichert");

    await selectFiles(page, [config.fixtures.standort]);
    await page.waitFor(`!document.getElementById('location-scrim').hidden`, { what: "zweite Rückfrage" });
    await page.eval("document.getElementById('location-keep').click()");
    await waitForItem(page, "standort.mp4", "done", 30000);
    assert.equal(uploadRequests(page), 3, "erste Anfrage, Rückfrage, Wiederholung mit Standort");
    await page.waitFor(`document.querySelectorAll('#grid .tile').length === ${before + 1}`, {
      what: "Eintrag im Raster",
    });
    await page.waitFor(`document.querySelectorAll('#grid .badge.loc').length >= 1`, {
      what: "Kennzeichen Standort enthalten",
    });
    expectNoProblems(page, "Standort-Rückfrage");
  } finally {
    await page.close();
  }
});

test("Abbrechen: ein wartender Upload sendet danach nichts mehr", async () => {
  const page = await openUpload();
  try {
    await control("hold-uploads");
    await selectFiles(page, [config.fixtures.abbruch]);
    await waitForItem(page, "abbruch.jpg", "retrying", 15000);
    assert.equal(await clickItemButton(page, "abbruch.jpg", "Cancel"), true, "Knopf „Abbrechen“");
    await waitForItem(page, "abbruch.jpg", "aborted");
    await control("release-uploads");
    const requests = uploadRequests(page);
    await sleep(7000);
    assert.equal(uploadRequests(page), requests, "nach dem Abbruch keine neue Anfrage");
    expectNoProblems(page, "Abbrechen");
  } finally {
    await control("release-uploads").catch(() => {});
    await page.close();
  }
});

test("429 mit Retry-After wird ohne Fehlermeldung wiederholt", async () => {
  const page = await openUpload();
  try {
    await control("hold-uploads");
    await selectFiles(page, [config.fixtures.wiederholen]);
    await waitForItem(page, "wiederholen.jpg", "retrying", 15000);
    assert.equal(await page.eval("document.getElementById('upload-notice').textContent"), "", "keine Meldung");
    await control("release-uploads");
    await waitForItem(page, "wiederholen.jpg", "done", 20000);
    assert.ok(uploadRequests(page) >= 2, "mindestens zwei Anfragen");
    expectNoProblems(page, "429");
  } finally {
    await control("release-uploads").catch(() => {});
    await page.close();
  }
});

test("Ablage am PC per Drag and Drop, Knopf als Tastatur-Alternative", async () => {
  const page = await openUpload();
  try {
    await page.eval("document.getElementById('upload-pick').focus()");
    assert.equal(await page.eval("document.activeElement.id"), "upload-pick", "Knopf per Tastatur erreichbar");
    assert.equal(await page.eval("document.getElementById('file-input').tabIndex"), -1, "Dateifeld nicht in der Reihenfolge");
    await page.dragFiles("#drop-zone", [config.fixtures.ziehen]);
    await waitForItem(page, "ziehen.jpg", "done", 30000);
    expectNoProblems(page, "Drag and Drop");
  } finally {
    await page.close();
  }
});
