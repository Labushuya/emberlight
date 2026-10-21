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

    await control("close-space");
    await page.waitFor("!document.getElementById('ended-section').hidden", {
      what: "Ende-Ansicht nach 410",
      timeout: 40000,
    });
    assert.match(
      await page.eval("document.getElementById('ended-text').textContent"),
      /^Space ‚Sommerfest im Garten‘ ended at /,
    );
    assert.equal(await page.eval("document.getElementById('space-section').hidden"), true);
    expectNoProblems(page, "Ende nach 410");

    await page.goto(`${config.base}/`);
    await page.waitFor("!document.getElementById('ended-section').hidden", { what: "Ende nach Neuladen" });
    assert.equal(await page.eval("document.getElementById('space-section').hidden"), true, "Keine Space-Ansicht nach Ende");
    expectNoProblems(page, "Ende nach Neuladen");
  } finally {
    await page.close();
  }
});
