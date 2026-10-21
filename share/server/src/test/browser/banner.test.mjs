// Browser-Test: Vorwarnung zehn Minuten vor dem Ende (Banner, Paket 3.5a-3). Der JVM-Test legt das Ende
// neun bis zehn Minuten in die Zukunft. Dann zeigt die Seite das Banner, aber noch keinen Dialog.

import assert from "node:assert/strict";
import { test } from "node:test";
import { openSpace } from "./lib/guest.mjs";
import { expectNoProblems, loadConfig, useBrowser } from "./lib/setup.mjs";

const config = loadConfig();
const browser = useBrowser();

test("Banner ab zehn Minuten vor dem Ende, ohne Dialog", async () => {
  const page = await browser.page({ width: 1280, height: 900, locale: "en-US" });
  try {
    await openSpace(page, config, config.session);
    await page.waitFor("!document.getElementById('banner').hidden", { what: "Banner" });
    assert.match(
      await page.eval("document.querySelector('#banner p').textContent"),
      /^The space ends in 10 minutes – download now what you want to keep\.$/,
    );
    assert.equal(
      await page.eval("document.getElementById('countdown').classList.contains('urgent')"),
      true,
      "Restzeit hervorgehoben",
    );
    assert.match(await page.eval("document.getElementById('countdown').textContent"), /^9 minutes left$/);
    assert.equal(await page.eval("document.getElementById('ending-scrim').hidden"), true, "Dialog zu früh");
    assert.equal(await page.eval("document.getElementById('ended-section').hidden"), true);
    expectNoProblems(page, "Banner");
  } finally {
    await page.close();
  }
});
