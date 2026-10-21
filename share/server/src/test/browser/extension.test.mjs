// Browser-Tests: Verlängern während der Ein-Minuten-Warnung (Paket 3.6a, ADR 0017 und ADR 0019 Abschnitt 6).
// Die Datei gehört zu einem Server, dessen Space in etwa 45 Sekunden endet, der Dialog erscheint also sofort. Der Gastgeber
// verlängert über die Steuerung „extend-hour“ (der JVM-Test ändert die Endzeit und die Revision am Server).
// Geprüft werden: Der Dialog schließt, das Banner erscheint in einer Live-Region, die Vorwarnung und die Hervorhebung fallen
// weg, der Countdown rechnet mit dem neuen Ende, und nach dem alten Ende gibt es keine Ende-Ansicht.

import assert from "node:assert/strict";
import { test } from "node:test";
import { openSpace } from "./lib/guest.mjs";
import { control, expectNoProblems, loadConfig, useBrowser } from "./lib/setup.mjs";

const config = loadConfig();
const browser = useBrowser();

/** Restzeit in Minuten aus dem englischen Countdown, `null` bei einem anderen Text. */
function minutesLeft(text) {
  const hoursMinutes = /^(\d+) h (\d+) min left$/.exec(text);
  if (hoursMinutes) return Number(hoursMinutes[1]) * 60 + Number(hoursMinutes[2]);
  const minutes = /^(\d+) minutes? left$/.exec(text);
  return minutes ? Number(minutes[1]) : null;
}

test("Verlängern schließt die Warnung, zeigt das Banner und der Countdown läuft mit dem neuen Ende", async () => {
  const page = await browser.page({ width: 390, height: 844, locale: "en-US" });
  try {
    const originalEnd = Date.parse(config.session.endsAt);
    await openSpace(page, config, config.session);

    await page.waitFor("!document.getElementById('ending-scrim').hidden", { what: "Dialog eine Minute vorher" });
    assert.equal(await page.eval("document.activeElement.id"), "ending-ok", "Fokus im Dialog");

    await control("extend-hour");

    // Die Seite erfährt das beim nächsten Abruf (10 bis 15 Sekunden), also vor dem alten Ende.
    await page.waitFor("!document.getElementById('extended-banner').hidden", {
      what: "Banner nach dem Verlängern",
      timeout: 40000,
    });
    assert.equal(await page.eval("document.getElementById('extended-banner').getAttribute('role')"), "status");
    assert.equal(await page.eval("document.getElementById('extended-banner').getAttribute('aria-live')"), "polite");
    assert.match(
      await page.eval("document.getElementById('extended-text').textContent"),
      /^The space has been extended until .+\.$/,
    );
    assert.equal(await page.eval("document.getElementById('ending-scrim').hidden"), true, "Dialog geschlossen");
    assert.equal(await page.eval("document.getElementById('banner').hidden"), true, "Vorwarnung geschlossen");
    assert.equal(
      await page.eval("document.getElementById('countdown').classList.contains('urgent')"),
      false,
      "Hervorhebung aufgehoben",
    );

    // Der Countdown rechnet mit dem neuen Ende: etwa eine Stunde, nicht die Sekunden bis zum alten Ende.
    const left = minutesLeft(await page.eval("document.getElementById('countdown').textContent"));
    assert.ok(left !== null && left >= 55 && left <= 61, `Countdown mit dem neuen Ende, angezeigt: ${left} Minuten`);

    // Nach dem alten Ende läuft der Space weiter: keine Ende-Ansicht, kein Dialog.
    await new Promise((resolve) => setTimeout(resolve, Math.max(0, originalEnd + 4000 - Date.now())));
    assert.equal(await page.eval("document.getElementById('ended-section').hidden"), true, "keine Ende-Ansicht");
    assert.equal(await page.eval("document.getElementById('ending-scrim').hidden"), true, "kein Dialog mehr");
    assert.equal(await page.eval("document.getElementById('space-section').hidden"), false, "Space bleibt offen");
    expectNoProblems(page, "Verlängern");
  } finally {
    await page.close();
  }
});
