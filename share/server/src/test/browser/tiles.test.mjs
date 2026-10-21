// Browser-Test: Raster der Gäste-Seite beim Abfragen (Paket 3.5a-4, ADR 0019, Nachtrag). Kacheln bleiben bei einem neuen
// Stand erhalten, nur der neue Eintrag entsteht. Bilder laden höchstens vier gleichzeitig, nie Originale im Raster.
// Ein Bildfehler lädt einmal mit frischer Adresse neu. Layout und Haupt-Thread bleiben ruhig.
// Der JVM-Test legt 40 Einträge an: 38 Fotos mit Vorschau, ein Video ohne Vorschau, ein Foto ohne Vorschau.

import assert from "node:assert/strict";
import { test } from "node:test";
import { apiGet, openSpace } from "./lib/guest.mjs";
import { control, expectNoProblems, loadConfig, useBrowser } from "./lib/setup.mjs";

const config = loadConfig();
const browser = useBrowser();
const session = config.session;

const PREVIEW = /\/api\/v1\/media\/[A-Za-z0-9_-]{22}\/preview\?/;
const VIEW = /\/api\/v1\/media\/[A-Za-z0-9_-]{22}\/view\?/;
// Schwellen: Keine Kachel darf sich bewegen, ein Wert ab 0,01 wäre schon eine sichtbare Verschiebung auf dem Telefon.
const CLS_LIMIT = 0.01;
// Ein Haupt-Thread-Stück über 50 ms macht die Seite beim Scrollen ruckelig (Long-Task-Grenze).
const LONG_TASK_MS = 50;

/** Alle Einträge der Liste, seitenweise. */
async function allItems() {
  const items = [];
  let cursor = null;
  do {
    const path = `/api/v1/media?limit=100${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`;
    const response = await apiGet(config, path, session.token);
    assert.equal(response.status, 200, "Liste der Medien");
    items.push(...response.body.items);
    cursor = response.body.nextCursor;
  } while (cursor);
  return items;
}

const hasPreview = (item) => item.previewUrl !== null && item.previewState === "ready";

/** Scrollt in Schritten bis zum Ende, bis alle Kacheln da sind. Bilder laden erst in Sichtnähe. */
function scrollAll(total, { pollEvery = 0 } = {}) {
  return `(async () => {
    for (let i = 0; i < 600; i++) {
      window.scrollBy(0, 400);
      ${pollEvery > 0 ? `if (i % ${pollEvery} === 0) window.emberlightGuest.space.pollNow();` : ""}
      await new Promise((resolve) => setTimeout(resolve, 60));
      const cells = document.querySelectorAll('#grid .cell').length;
      if (cells >= ${total} && window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 4) return true;
    }
    return false;
  })()`;
}

/** Wartet, bis jedes Vorschaubild geladen ist. */
function imagesLoaded(count) {
  return `document.querySelectorAll('#grid img.thumb').length === ${count} &&
    [...document.querySelectorAll('#grid img.thumb')].every((img) => img.complete && img.naturalWidth > 0)`;
}

/** Öffnet den Space und lädt alle Kacheln und Vorschauen. Liefert die Einträge. */
async function openAll(page) {
  const items = await allItems();
  await openSpace(page, config, session);
  const scrolled = await page.eval(scrollAll(items.length));
  if (scrolled !== true) {
    const state = await page.eval(`({
      cells: document.querySelectorAll('#grid .cell').length,
      scrollY: window.scrollY,
      scrollHeight: document.documentElement.scrollHeight,
      innerHeight: window.innerHeight,
      sentinel: document.getElementById('sentinel').getBoundingClientRect().top,
      loadMoreHidden: document.getElementById('load-more').hidden,
      listEmptyHidden: document.getElementById('list-empty').hidden,
    })`);
    assert.fail(`Alle Kacheln erreichbar (erwartet ${items.length}): ${JSON.stringify(state)}`);
  }
  await page.waitFor(imagesLoaded(items.filter(hasPreview).length), { what: "alle Vorschauen", timeout: 30000 }).catch(
    async (failure) => {
      const stats = await page.eval(`(() => {
        const imgs = [...document.querySelectorAll('#grid img.thumb')];
        return {
          cells: document.querySelectorAll('#grid .cell').length,
          thumbs: imgs.length,
          withSrc: imgs.filter((img) => img.getAttribute('src')).length,
          loaded: imgs.filter((img) => img.complete && img.naturalWidth > 0).length,
          broken: imgs.filter((img) => img.complete && img.naturalWidth === 0 && img.getAttribute('src')).length,
          placeholders: document.querySelectorAll('#grid .placeholder').length,
          scrollY: window.scrollY,
        };
      })()`);
      throw new Error(`${failure.message}\nBilder: ${JSON.stringify(stats)} (erwartet ${items.filter(hasPreview).length})`);
    },
  );
  return items;
}

test("Raster mit 40 Einträgen: höchstens vier Bildanfragen gleichzeitig, nie ein Original, kein 503", async () => {
  const page = await browser.page({ width: 390, height: 844, locale: "de-DE" });
  try {
    await page.enableNetwork();
    const previews = page.track(PREVIEW);
    const views = page.track(VIEW);
    const items = await openAll(page);
    assert.ok(items.length >= 40, `Erwartet 40 Einträge, es sind ${items.length}`);

    // E: Gleichzeitigkeit, Antworten, jede Vorschau genau einmal.
    assert.ok(previews.peak <= 4, `Gleichzeitige Bildanfragen: ${previews.peak} (höchstens 4)`);
    assert.deepEqual(Object.keys(previews.statuses), ["200"], `Antwortcodes der Vorschauen: ${JSON.stringify(previews.statuses)}`);
    assert.equal(previews.count, items.filter(hasPreview).length, "Jede Vorschau genau einmal angefragt");

    // C: Kein Original im Raster, auch nicht für Einträge ohne fertige Vorschau.
    assert.equal(views.count, 0, "Anfragen an /view? aus dem Raster");
    assert.equal(
      await page.eval("document.querySelectorAll('.placeholder').length"),
      items.filter((item) => !hasPreview(item)).length,
      "Platzhalter entsprechen der API",
    );
    assert.equal(
      await page.eval("[...document.querySelectorAll('.placeholder')].filter((node) => node.textContent.includes('keine Vorschau')).length"),
      items.filter((item) => item.previewState === "failed").length,
      "„Keine Vorschau“ nur für fehlgeschlagene Vorschauen",
    );
    console.log(`Messung: E/C Vorschauanfragen ${previews.count}, gleichzeitig höchstens ${previews.peak} (Grenze 4), Antworten ${JSON.stringify(previews.statuses)}, Originale ${views.count}`);
    expectNoProblems(page, "Raster 40 Einträge");
  } finally {
    await page.close();
  }
});

test("Scrollen und Abfragen: keine Verschiebung (CLS) und keine Long Tasks über 50 ms", async () => {
  const page = await browser.page({ width: 390, height: 844, locale: "de-DE" });
  try {
    const items = await openAll(page);
    // D: Layout-Verschiebungen ohne Eingabe, während gescrollt und abgefragt wird.
    await page.eval(`(() => {
      window.__cls = 0;
      window.__tasks = [];
      new PerformanceObserver((list) => {
        for (const entry of list.getEntries()) if (!entry.hadRecentInput) window.__cls += entry.value;
      }).observe({ type: "layout-shift" });
      new PerformanceObserver((list) => {
        for (const entry of list.getEntries()) window.__tasks.push(entry.duration);
      }).observe({ type: "longtask" });
      window.scrollTo(0, 0);
      return true;
    })()`);
    const scrolled = await page.eval(scrollAll(items.length, { pollEvery: 4 }));
    assert.equal(scrolled, true, "Scrollen bis zum Ende");
    await page.eval("window.scrollTo(0, 0)");
    await new Promise((resolve) => setTimeout(resolve, 300));
    const cls = await page.eval("window.__cls");
    assert.ok(cls < CLS_LIMIT, `CLS ${cls} (Grenze ${CLS_LIMIT})`);
    console.log(`Messung: CLS beim Scrollen und Abfragen ${cls.toFixed(4)} (Grenze ${CLS_LIMIT})`);
    // Gegenprobe: Eine absichtliche Verschiebung muss der Beobachter erfassen. Sonst misst er nichts.
    await page.eval(`(() => {
      const probe = document.createElement('div');
      probe.id = 'probe';
      probe.style.height = '40px';
      document.body.prepend(probe);
      return true;
    })()`);
    await new Promise((resolve) => setTimeout(resolve, 200));
    const probed = await page.eval("window.__cls");
    await page.eval("document.getElementById('probe').remove()");
    assert.ok(probed > cls, "Gegenprobe: Der Beobachter erfasst eine Verschiebung nicht");

    // F: Ein Abfragezyklus mit neuem Eintrag, danach zwei ohne Änderung. Keine Task über 50 ms.
    await page.eval("window.__tasks.length = 0");
    await control("add-photo");
    await page.eval("window.emberlightGuest.space.pollNow()");
    await page.waitFor(`document.querySelectorAll('#grid .cell').length === ${items.length + 1}`, { what: "neuer Eintrag" });
    await page.eval("window.emberlightGuest.space.pollNow()");
    await new Promise((resolve) => setTimeout(resolve, 1500));
    await page.eval("window.emberlightGuest.space.pollNow()");
    await new Promise((resolve) => setTimeout(resolve, 1500));
    const tasks = await page.eval("window.__tasks.slice()");
    const longest = tasks.length === 0 ? 0 : Math.max(...tasks);
    assert.ok(longest <= LONG_TASK_MS, `Längste Task ${longest.toFixed(1)} ms (Grenze ${LONG_TASK_MS} ms)`);
    console.log(`Messung: Long Tasks im Zyklus ${tasks.length}, längste ${longest.toFixed(1)} ms (Grenze ${LONG_TASK_MS} ms)`);
    // Gegenprobe: Ein absichtlich langer Block muss als Long Task erscheinen.
    await page.eval("window.__tasks.length = 0");
    // Die Schleife läuft als Seiten-Aufgabe (setTimeout). Eine Auswertung über CDP zählt der Beobachter nicht mit.
    await page.eval("setTimeout(() => { const end = performance.now() + 150; while (performance.now() < end) {} }, 0); true");
    await new Promise((resolve) => setTimeout(resolve, 400));
    const probeTasks = await page.eval("window.__tasks.slice()");
    assert.ok(probeTasks.some((duration) => duration >= 100), "Gegenprobe: Der Beobachter erfasst Long Tasks nicht");
    expectNoProblems(page, "Scrollen und Abfragen");
  } finally {
    await page.close();
  }
});

test("Neuer Stand: vorhandene Kacheln bleiben, nur der neue Eintrag lädt", async () => {
  const page = await browser.page({ width: 1280, height: 900, locale: "de-DE" });
  try {
    await page.enableNetwork();
    const previews = page.track(PREVIEW);
    const views = page.track(VIEW);
    const items = await openAll(page);
    const before = items.length;
    // A: Markierungen an den vorhandenen Kacheln.
    await page.eval("document.querySelectorAll('#grid .cell').forEach((node) => { node.dataset.mark = '1'; })");
    const previewsBefore = previews.count;
    const viewsBefore = views.count;

    await control("add-photo");
    await page.eval("window.emberlightGuest.space.pollNow()");
    await page.waitFor(`document.querySelectorAll('#grid .cell').length === ${before + 1}`, { what: "neuer Eintrag" });
    await page.waitFor(imagesLoaded(items.filter(hasPreview).length + 1), { what: "Vorschau des neuen Eintrags" });

    assert.equal(
      await page.eval("document.querySelectorAll('#grid .cell[data-mark=\"1\"]').length"),
      before,
      "A: vorhandene Kacheln sind dieselben Knoten geblieben",
    );
    // B: Nur der neue Eintrag hat eine Vorschau angefragt. Die übrigen Adressen bleiben unberührt.
    assert.equal(previews.count - previewsBefore, 1, "B: Vorschauanfragen nach dem neuen Stand");
    assert.equal(views.count - viewsBefore, 0, "B: Original-Anfragen nach dem neuen Stand");
    console.log(`Messung: A/B vorhandene Kacheln ${before} unverändert, neue Vorschauanfragen ${previews.count - previewsBefore}, Originale ${views.count - viewsBefore}`);
    expectNoProblems(page, "Neuer Stand");
  } finally {
    await page.close();
  }
});

test("Bildfehler: genau ein Wiederversuch mit frischer Adresse, dann ein Bild", async () => {
  const page = await browser.page({ width: 1280, height: 900, locale: "de-DE" });
  try {
    await page.enableNetwork();
    const items = await allItems();
    const target = items.find(hasPreview).id;
    const previews = page.track(new RegExp(`/api/v1/media/${target}/preview\\?`));
    const refreshes = page.track(new RegExp(`/api/v1/media/${target}$`));
    // G: Die erste Vorschau dieses Eintrags schlägt fehl. Alle anderen Anfragen laufen normal.
    let failed = false;
    await page.intercept("*/preview*", (url) => {
      if (!failed && url.includes(`/api/v1/media/${target}/preview?`)) {
        failed = true;
        return "fail";
      }
      return "continue";
    });
    await openSpace(page, config, session);
    await page.waitFor(
      `(() => { const img = document.querySelector('li[data-id="${target}"] img.thumb'); return !!img && img.complete && img.naturalWidth > 0; })()`,
      { what: "Vorschau nach dem Wiederversuch", timeout: 30000 },
    );
    await new Promise((resolve) => setTimeout(resolve, 3000));
    assert.equal(failed, true, "Der Fehler wurde ausgelöst");
    assert.equal(previews.count, 2, `Vorschauanfragen: ${previews.count} (erwartet 2: Fehler und ein Wiederversuch)`);
    assert.equal(refreshes.count, 1, `Erneuerungen der Adresse: ${refreshes.count} (erwartet 1)`);
    assert.equal(new Set(previews.urls).size, 2, "Der Wiederversuch nutzt eine andere Adresse");
    assert.equal(
      await page.eval(`document.querySelectorAll('li[data-id="${target}"] .placeholder').length`),
      0,
      "Nach dem Wiederversuch kein Platzhalter",
    );
    console.log(`Messung: G Vorschauanfragen ${previews.count} (erwartet 2), Adressen-Erneuerungen ${refreshes.count} (erwartet 1)`);
    expectNoProblems(page, "Bildfehler");
  } finally {
    await page.close();
  }
});
