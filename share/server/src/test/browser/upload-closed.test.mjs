// Browser-Test: Der Space endet während ein Upload wartet (Paket 3.5b, ADR 0017, 410 mit endedAt). Der JVM-Test markiert das
// Ende. Der nächste Versuch des Uploads bekommt `410` mit `endedAt`. Die Seite zeigt dann die Ende-Ansicht mit genau
// dieser Uhrzeit (nicht mit der Uhrzeit der Erkennung).

import assert from "node:assert/strict";
import { test } from "node:test";
import { openSpace, apiGet } from "./lib/guest.mjs";
import { control, expectNoProblems, loadConfig, useBrowser } from "./lib/setup.mjs";
import { selectFiles, waitForItem } from "./lib/upload.mjs";

const config = loadConfig();
const browser = useBrowser();

test("410 während des Uploads zeigt die Ende-Ansicht mit der Uhrzeit aus endedAt", async () => {
  const page = await browser.page({ width: 390, height: 844, locale: "en-US" });
  try {
    await page.enableNetwork();
    await openSpace(page, config, config.session);
    await control("hold-uploads");
    await selectFiles(page, [config.fixtures.foto]);
    await waitForItem(page, "foto.jpg", "retrying", 15000);

    await control("close-space");
    await control("release-uploads");
    await page.waitFor(`!document.getElementById('ended-section').hidden`, {
      what: "Ende-Ansicht",
      timeout: 40000,
    });

    // Die Antwort des Servers nennt die Stoppzeit. Die Seite muss dieselbe Uhrzeit zeigen.
    const response = await apiGet(config, "/api/v1/space", config.session.token);
    assert.equal(response.status, 410);
    assert.equal(typeof response.body.endedAt, "string", "410 nennt endedAt");
    const time = new Date(response.body.endedAt).toLocaleTimeString("en", { hour: "2-digit", minute: "2-digit" });
    const text = (await page.eval("document.getElementById('ended-text').textContent")).replace(/\s+/g, " ");
    assert.ok(text.includes(time.replace(/\s+/g, " ")), `Ende-Text „${text}“ nennt ${time}`);
    assert.equal(await page.eval("document.getElementById('space-section').hidden"), true);
    expectNoProblems(page, "Ende während des Uploads");
  } finally {
    await control("release-uploads").catch(() => {});
    await page.close();
  }
});
