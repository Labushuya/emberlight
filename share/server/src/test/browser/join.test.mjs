// Browser-Test: Beitritt über den Einladungslink, Wiederkehr nach Neuladen, Abmelden (ADR 0018, Paket 3.5a-3).
// Der JVM-Test GuestBrowserTest startet den Server mit einem offenen Space und übergibt Einladung und Name.

import assert from "node:assert/strict";
import { test } from "node:test";
import { expectNoProblems, loadConfig, useBrowser } from "./lib/setup.mjs";

const config = loadConfig();
const browser = useBrowser();

test("Beitritt mit Vorschau, Wiederkehr nach Neuladen und Abmelden", async () => {
  const page = await browser.page({ width: 1280, locale: "en-US" });
  try {
    await page.goto(`${config.base}/#i=${config.invite}`);
    await page.waitFor("!document.getElementById('join-section').hidden", { what: "Beitrittsformular" });
    // Das Geheimnis verlässt die Adresse sofort (ADR 0018).
    assert.equal(await page.eval("location.hash"), "");
    await page.waitFor(
      `document.getElementById('preview').textContent.includes(${JSON.stringify(config.spaceName)})`,
      { what: "Vorschau mit Namen" },
    );

    await page.eval("document.getElementById('name').value = 'Lena'");
    await page.eval("document.getElementById('join-button').click()");
    await page.waitFor("!document.getElementById('space-section').hidden", { what: "Space nach dem Beitritt" });
    assert.equal(await page.eval("document.getElementById('space-name').textContent"), config.spaceName);
    assert.equal(await page.eval("document.getElementById('status').textContent"), "You are in, Lena");

    // Wiederkehr: ohne Einladung zeigt die gespeicherte Sitzung den Space.
    await page.goto(`${config.base}/`);
    await page.waitFor("!document.getElementById('space-section').hidden", { what: "Wiederkehr" });
    assert.equal(await page.eval("document.getElementById('space-name').textContent"), config.spaceName);

    // Abmelden: die Sitzung ist weg, die Seite fragt nach dem QR-Code.
    await page.eval("document.getElementById('leave-button').click()");
    await page.waitFor("document.getElementById('status').textContent === 'You have left.'", { what: "Abmeldung" });
    assert.equal(await page.eval("document.getElementById('space-section').hidden"), true);

    await page.goto(`${config.base}/`);
    await page.waitFor(
      "document.getElementById('status').textContent === \"Scan the host's QR code to join.\"",
      { what: "Hinweis nach Abmeldung" },
    );
    assert.equal(await page.eval("document.getElementById('space-section').hidden"), true);
    expectNoProblems(page, "Beitritt");
  } finally {
    await page.close();
  }
});

test("Ein ungültiger Einladungslink wird ohne Sitzung abgewiesen", async () => {
  const page = await browser.page({ width: 390, locale: "en-US" });
  try {
    const wrong = "A".repeat(43);
    await page.goto(`${config.base}/#i=${wrong}`);
    await page.waitFor(
      "document.getElementById('status').textContent.includes('no longer valid')",
      { what: "Hinweis zur ungültigen Einladung" },
    );
    assert.equal(await page.eval("document.getElementById('join-section').hidden"), true);
    expectNoProblems(page, "Ungültige Einladung");
  } finally {
    await page.close();
  }
});
