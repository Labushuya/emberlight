// Browser-Test: Verbindungsverlust ohne bekanntes Ende (Paket 3.5a-3). Der JVM-Test stoppt den Server über
// `control("stop-server")`. Die Seite zeigt den Hinweis, bleibt im Space und versucht es automatisch erneut.

import assert from "node:assert/strict";
import { test } from "node:test";
import { openSpace } from "./lib/guest.mjs";
import { control, expectNoProblems, loadConfig, useBrowser } from "./lib/setup.mjs";

const config = loadConfig();
const browser = useBrowser();

test("Verbindungsverlust: Hinweis mit automatischer Wiederholung, kein Ende", async () => {
  const page = await browser.page({ width: 390, height: 844, locale: "en-US" });
  try {
    await openSpace(page, config, config.session);
    await page.waitFor("!document.getElementById('list-empty').hidden", { what: "geladene Liste" });
    assert.equal(await page.eval("document.getElementById('lost').hidden"), true, "Hinweis vor dem Verlust");

    await control("stop-server");
    await page.waitFor("!document.getElementById('lost').hidden", { what: "Hinweis bei Verbindungsverlust", timeout: 40000 });
    assert.match(
      await page.eval("document.getElementById('lost').textContent"),
      /^No connection to the space\. It may have ended, or the host's phone is not reachable right now\. Your downloads stay with you\./,
    );
    assert.equal(await page.eval("document.getElementById('ended-section').hidden"), true, "Kein Ende ohne bekanntes Ende");
    assert.equal(await page.eval("document.getElementById('space-section').hidden"), false, "Die Ansicht bleibt");
    expectNoProblems(page, "Verbindungsverlust");
  } finally {
    await page.close();
  }
});
