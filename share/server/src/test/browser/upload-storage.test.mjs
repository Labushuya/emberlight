// Browser-Test: Speicher voll während des Uploads (Paket 3.5b, ADR 0014, 507). Der Server hat ein Speicherlimit von
// 300 000 Bytes. Drei Dateien zu je 100 000 Bytes passen bei der Vorabprüfung. Danach füllt der JVM-Test den Speicher,
// und der erste Upload bekommt 507 (`storage_full`). Die Warteschlange muss dann anhalten.

import assert from "node:assert/strict";
import { test } from "node:test";
import { openSpace } from "./lib/guest.mjs";
import { control, expectNoProblems, loadConfig, useBrowser } from "./lib/setup.mjs";
import { queue, selectFiles, sleep, uploadRequests } from "./lib/upload.mjs";

const config = loadConfig();
const browser = useBrowser();

test("507 storage_full hält die Warteschlange an und sendet nicht weiter", async () => {
  const page = await browser.page({ width: 1280, height: 900, locale: "en-US" });
  try {
    await page.enableNetwork();
    await openSpace(page, config, config.session);
    await control("hold-uploads");
    await selectFiles(page, [config.fixtures.eins, config.fixtures.zwei, config.fixtures.drei]);
    await page.waitFor(`document.querySelectorAll('#upload-queue .q-item[data-state="retrying"]').length >= 2`, {
      what: "zwei Uploads warten auf den Platz (429)",
      timeout: 20000,
    });

    await control("fill-storage");
    await control("release-uploads");
    await page.waitFor(`!document.getElementById('upload-resume').hidden`, {
      what: "Warteschlange angehalten",
      timeout: 40000,
    });

    const list = await queue(page);
    assert.ok(
      list.some((item) => item.state === "failed" && /full/.test(item.status)),
      `ein Eintrag mit „Speicher voll“: ${JSON.stringify(list)}`,
    );
    assert.ok(list.every((item) => item.state !== "done"), "nichts gespeichert");
    assert.match(await page.eval("document.getElementById('upload-notice').textContent"), /full/);
    for (const item of list.filter((entry) => entry.state === "waiting")) {
      assert.equal(item.status, "Paused", "wartende Dateien zeigen „Angehalten“");
    }

    const requests = uploadRequests(page);
    await sleep(7000);
    assert.equal(uploadRequests(page), requests, "die angehaltene Warteschlange sendet nicht weiter");
    expectNoProblems(page, "507");
  } finally {
    await control("release-uploads").catch(() => {});
    await page.close();
  }
});
