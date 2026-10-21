// Browser-Test: Gleichlauf der Signaturprüfung (Paket 3.5b, Drift vermeiden). Dieselben Fälle wie im JVM-Test
// (src/test/fixtures/guest-formats.json) bewertet die Seite nach guest/formats.json. Das Ergebnis muss genau dem
// Erwarteten entsprechen, das ContentInspector auch liefert (GuestFormatsParityTest).

import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { expectNoProblems, loadConfig, useBrowser } from "./lib/setup.mjs";

const config = loadConfig();
const browser = useBrowser();
const { cases } = JSON.parse(await readFile(new URL("../fixtures/guest-formats.json", import.meta.url), "utf8"));

test("Signaturprüfung im Browser bewertet die Fixtures wie ContentInspector", async () => {
  const page = await browser.page({ width: 1280, height: 900, locale: "en-US" });
  try {
    await page.goto(`${config.base}/`);
    await page.waitFor("!!(window.emberlightGuest && window.emberlightGuest.upload)", { what: "Upload-Modul" });
    await page.eval("window.emberlightGuest.upload.ready()");
    const actual = await page.eval(`(${JSON.stringify(cases)}).map((fixture) => {
      const bytes = new Uint8Array((fixture.head.match(/../g) || []).map((hex) => parseInt(hex, 16)));
      const found = window.emberlightGuest.upload.identify(bytes);
      return { name: fixture.name, mime: found ? found.mime : null, kind: found ? found.kind : null };
    })`);
    const expected = cases.map((fixture) => ({ name: fixture.name, mime: fixture.mime, kind: fixture.kind }));
    assert.deepEqual(actual, expected);
    expectNoProblems(page, "Signaturen");
  } finally {
    await page.close();
  }
});
