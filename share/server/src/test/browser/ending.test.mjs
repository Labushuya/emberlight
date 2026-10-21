// Browser-Tests: Ende-Hinweise der Gäste-Seite (Paket 3.5a-3, ADR 0017 und ADR 0019 Abschnitt 6).
// Die Datei gehört zu einem Server, dessen Space in etwa zwölf Sekunden endet (der JVM-Test legt das Ende fest).
// Geprüft werden: Dialog bei einer Minute, Ende-Ansicht über den Countdown, Merken des Endes nach Neuladen.

import assert from "node:assert/strict";
import { test } from "node:test";
import { openSpace } from "./lib/guest.mjs";
import { expectNoProblems, loadConfig, useBrowser } from "./lib/setup.mjs";

const config = loadConfig();
const browser = useBrowser();

test("Dialog eine Minute vorher, Ende-Ansicht bei Ablauf und nach Neuladen", async () => {
  const page = await browser.page({ width: 390, height: 844, locale: "en-US" });
  try {
    await openSpace(page, config, config.session);

    // Unter einer Minute erscheint der Dialog, er muss bestätigt werden.
    await page.waitFor("!document.getElementById('ending-scrim').hidden", { what: "Dialog eine Minute vorher" });
    assert.equal(await page.eval("document.querySelector('#ending-scrim [role=alertdialog]') !== null"), true);
    assert.equal(await page.eval("document.activeElement.id"), "ending-ok", "Fokus im Dialog");
    assert.equal(await page.eval("!document.getElementById('banner').hidden"), true, "Banner unter einer Minute");
    assert.equal(
      await page.eval("document.getElementById('countdown').classList.contains('urgent')"),
      true,
      "Restzeit hervorgehoben",
    );

    await page.eval("document.getElementById('ending-ok').click()");
    await page.waitFor("document.getElementById('ending-scrim').hidden", { what: "Dialog bestätigt" });

    // Nach Ablauf zeigt die Seite das Ende, ohne eine Anfrage an den Server.
    await page.waitFor("!document.getElementById('ended-section').hidden", {
      what: "Ende-Ansicht nach Ablauf",
      timeout: 40000,
    });
    const text = await page.eval("document.getElementById('ended-text').textContent");
    assert.match(text, /^Space ‚Ende-Test‘ ended at \d{1,2}:\d{2}/);
    assert.match(text, /What you downloaded stays with you\.$/);
    assert.equal(await page.eval("document.getElementById('space-section').hidden"), true);
    expectNoProblems(page, "Ende nach Ablauf");

    // Neuladen: die Seite merkt sich das Ende.
    await page.goto(`${config.base}/`);
    await page.waitFor("!document.getElementById('ended-section').hidden", { what: "Ende nach Neuladen" });
    assert.match(await page.eval("document.getElementById('ended-text').textContent"), /ended at/);
    expectNoProblems(page, "Ende nach Neuladen");
  } finally {
    await page.close();
  }
});
