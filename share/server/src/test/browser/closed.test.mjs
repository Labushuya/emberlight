// Browser-Test: Ende durch den Gastgeber, Antwort 410 (ADR 0017, ADR 0019 Abschnitt 6). Der JVM-Test schaltet den Space auf
// „beendet“, sobald die Seite über `control("close-space")` bittet. Danach muss die Ende-Ansicht erscheinen und bleiben.

import assert from "node:assert/strict";
import { test } from "node:test";
import { openSpace } from "./lib/guest.mjs";
import { control, expectNoProblems, loadConfig, useBrowser } from "./lib/setup.mjs";

const config = loadConfig();
const browser = useBrowser();

test("Ende nach 410: die Seite zeigt das Ende und merkt es sich", async () => {
  const page = await browser.page({ width: 1280, height: 900, locale: "en-US" });
  try {
    await openSpace(page, config, config.session);
    await page.waitFor("!document.getElementById('list-empty').hidden", { what: "geladene Liste" });

    // Das Ende kommt vom Gastgeber, also liegt die Stoppzeit zwischen diesen beiden Zeitpunkten (nicht das geplante Ende).
    const before = Date.now();
    await control("close-space");
    await page.waitFor("!document.getElementById('ended-section').hidden", {
      what: "Ende-Ansicht nach 410",
      timeout: 40000,
    });
    const after = Date.now();
    const text = await page.eval("document.getElementById('ended-text').textContent");
    assert.match(text, /^Space ‚Sommerfest im Garten‘ ended at /);
    assert.equal(await page.eval("document.getElementById('space-section').hidden"), true);

    // Die gemerkte Stoppzeit ist das `endedAt` der 410-Antwort und stimmt mit der angezeigten Uhrzeit überein.
    const key = `emberlight.guest-end:${config.base}:${config.session.spaceId}`;
    const stored = JSON.parse(await page.eval(`localStorage.getItem(${JSON.stringify(key)})`));
    const endedMs = Date.parse(stored.endsAt);
    assert.ok(endedMs >= before - 1000 && endedMs <= after + 1000, `Stoppzeit ${stored.endsAt} im Fenster des Endes`);
    const shown = new Date(endedMs).toLocaleTimeString("en-US", { hour: "2-digit", minute: "2-digit" });
    assert.ok(text.includes(shown), `angezeigte Uhrzeit „${shown}“ in „${text}“`);
    expectNoProblems(page, "Ende nach 410");

    await page.goto(`${config.base}/`);
    await page.waitFor("!document.getElementById('ended-section').hidden", { what: "Ende nach Neuladen" });
    assert.equal(await page.eval("document.getElementById('space-section').hidden"), true, "Keine Space-Ansicht nach Ende");
    expectNoProblems(page, "Ende nach Neuladen");
  } finally {
    await page.close();
  }
});
