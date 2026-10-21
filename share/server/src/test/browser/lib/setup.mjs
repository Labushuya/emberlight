// Gemeinsame Einrichtung der Browser-Tests (ADR 0019, Paket 3.5a-3): Konfiguration vom JVM-Test, Steuerkanal zum
// Server-Prozess und Start des Browsers für eine Testdatei.
//
// Die Tests starten nie selbst einen Server. Der JVM-Test (GuestBrowserTest) startet Server und Daten, legt die
// Konfiguration in `EMBERLIGHT_BROWSER_CONFIG` ab und startet diese Datei. Braucht ein Test eine Änderung am Server
// (Ende, Abschalten), sendet er `EMBERLIGHT-CONTROL <name>` auf stderr und wartet auf eine Zeile `ok` auf stdin.

import { after, before } from "node:test";
import { tmpdir } from "node:os";
import { findChrome, launchChrome, stopChrome, Cdp, Page } from "./cdp.mjs";
import readline from "node:readline";

/** Konfiguration aus dem JVM-Test. Ohne sie läuft nichts. */
export function loadConfig(env = process.env) {
  const raw = env.EMBERLIGHT_BROWSER_CONFIG;
  if (!raw) throw new Error("EMBERLIGHT_BROWSER_CONFIG fehlt. Die Browser-Tests laufen nur über den JVM-Test.");
  const config = JSON.parse(raw);
  if (!config.base || !/^http:\/\/127\.0\.0\.1:\d+$/.test(config.base)) {
    throw new Error(`Ungültige Basisadresse: ${config.base}`);
  }
  return config;
}

// Steuerkanal

let answers = null;
const waiting = [];

function ensureControlChannel() {
  if (answers) return;
  answers = readline.createInterface({ input: process.stdin });
  answers.on("line", (line) => {
    const next = waiting.shift();
    if (next) next(line.trim());
  });
  answers.on("close", () => {
    while (waiting.length > 0) waiting.shift()("kanal geschlossen");
  });
}

/** Bittet den JVM-Test, eine Aktion auszuführen (z. B. den Server stoppen). Wartet auf die Antwort. */
export function control(name) {
  ensureControlChannel();
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`Steuerung ${name} ohne Antwort`)), 20000);
    waiting.push((answer) => {
      clearTimeout(timer);
      if (answer === "ok") resolve();
      else reject(new Error(`Steuerung ${name} fehlgeschlagen: ${answer}`));
    });
    process.stderr.write(`EMBERLIGHT-CONTROL ${name}\n`);
  });
}

function closeControlChannel() {
  answers?.close();
  answers = null;
}

/**
 * Browser für eine Testdatei. Startet Chrome vor allen Tests und beendet ihn danach. Liefert eine Funktion, die je Test
 * eine Seite öffnet. Die Seite meldet am Ende ihre Probleme, siehe [expectNoProblems].
 */
export function useBrowser() {
  const state = { chrome: null, cdp: null };

  before(async () => {
    if (typeof WebSocket !== "function" || typeof fetch !== "function") {
      throw new Error("Node 22 oder neuer nötig (globales WebSocket und fetch).");
    }
    const chromePath = findChrome();
    if (!chromePath) throw new Error("Kein Chrome gefunden (EMBERLIGHT_CHROME setzen).");
    const profileBase = process.env.EMBERLIGHT_BROWSER_TMP || tmpdir();
    state.chrome = await launchChrome({
      chromePath,
      profileBase,
      noSandbox: process.env.EMBERLIGHT_BROWSER_NO_SANDBOX === "1",
    });
    state.cdp = await Cdp.connect(state.chrome.wsUrl);
  });

  after(async () => {
    state.cdp?.close();
    await stopChrome(state.chrome);
    closeControlChannel();
  });

  return {
    /** Öffnet eine neue Seite mit der gewünschten Größe und Sprache. */
    async page({ width = 1280, height = 900, locale = "en-US" } = {}) {
      const page = await Page.open(state.cdp);
      await page.setViewport(width, height);
      await page.setLocale(locale);
      return page;
    },
    chromeStderr() {
      return state.chrome?.stderr.join("") ?? "";
    },
  };
}

/** Meldet Probleme der Seite (JavaScript-Fehler, CSP-Verstöße) als Testfehler. */
export function expectNoProblems(page, label) {
  if (page.problems.length > 0) {
    throw new Error(`${label}: Probleme auf der Seite\n${page.problems.join("\n")}`);
  }
}

