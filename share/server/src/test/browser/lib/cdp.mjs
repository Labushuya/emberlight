// Minimaler Chrome-DevTools-Client (CDP) für die Browser-Tests der Gäste-Seite (ADR 0019, Paket 3.5a-3).
// Keine npm-Abhängigkeiten. Nutzt das globale WebSocket (Node 22 oder neuer).
//
// Der Client spricht nur mit einem Chrome, den der Test selbst startet: headless, eigenes Profil, Debug-Port frei.

import { spawn } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { join } from "node:path";

const CHROME_CANDIDATES = [
  "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
  "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
  "/usr/bin/google-chrome",
  "/usr/bin/google-chrome-stable",
  "/usr/bin/chromium",
  "/usr/bin/chromium-browser",
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
];

/** Pfad des Chrome: `EMBERLIGHT_CHROME`, sonst die bekannten Orte. `null`, wenn es keinen gibt. */
export function findChrome(env = process.env) {
  const fromEnv = env.EMBERLIGHT_CHROME;
  if (fromEnv) return existsSync(fromEnv) ? fromEnv : null;
  return CHROME_CANDIDATES.find((candidate) => existsSync(candidate)) ?? null;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitUntil(check, timeoutMs, what) {
  const started = Date.now();
  for (;;) {
    const value = check();
    if (value) return value;
    if (Date.now() - started > timeoutMs) throw new Error(`Zeitüberschreitung: ${what}`);
    await sleep(50);
  }
}

/** Startet Chrome headless mit eigenem Profil und liefert die WebSocket-Adresse des Browsers. */
export async function launchChrome({ chromePath, profileBase, noSandbox }) {
  const profile = mkdtempSync(join(profileBase, "emberlight-browser-"));
  const args = [
    "--headless=new",
    "--remote-debugging-port=0",
    `--user-data-dir=${profile}`,
    "--no-first-run",
    "--no-default-browser-check",
    "--disable-gpu",
    "--disable-extensions",
    "--disable-background-networking",
    "--disable-dev-shm-usage",
    "--mute-audio",
    "--window-size=1280,900",
    "about:blank",
  ];
  if (noSandbox) args.push("--no-sandbox");
  const stderr = [];
  const proc = spawn(chromePath, args, { stdio: ["ignore", "ignore", "pipe"] });
  proc.stderr.on("data", (chunk) => {
    stderr.push(String(chunk));
    if (stderr.length > 40) stderr.shift();
  });
  const portFile = join(profile, "DevToolsActivePort");
  const [port, path] = await waitUntil(
    () => {
      if (!existsSync(portFile)) return null;
      const lines = readFileSync(portFile, "utf8").split(/\r?\n/);
      return lines.length >= 2 && lines[0] ? [lines[0], lines[1]] : null;
    },
    20000,
    "Chrome öffnet keinen Debug-Port",
  ).catch((failure) => {
    proc.kill();
    throw new Error(`${failure.message}\n${stderr.join("")}`);
  });
  return { proc, profile, wsUrl: `ws://127.0.0.1:${port}${path}`, stderr };
}

/** Beendet Chrome und entfernt das Profil. Fehler beim Entfernen (gesperrte Dateien) sind unkritisch. */
export async function stopChrome(chrome) {
  if (!chrome) return;
  const exited = new Promise((resolve) => chrome.proc.once("exit", resolve));
  chrome.proc.kill();
  await Promise.race([exited, sleep(5000)]);
  try {
    rmSync(chrome.profile, { recursive: true, force: true });
  } catch {
    // Chrome hält manchmal Dateien offen. Das Profil liegt im Temp-Ordner und wird dort nicht weiter benutzt.
  }
}

/** CDP-Verbindung zum Browser. Ereignisse werden an Abonnenten verteilt, Antworten an den Aufrufer. */
export class Cdp {
  #socket;
  #nextId = 1;
  #pending = new Map();
  #listeners = [];

  static async connect(wsUrl) {
    const cdp = new Cdp();
    cdp.#socket = new WebSocket(wsUrl);
    await new Promise((resolve, reject) => {
      cdp.#socket.addEventListener("open", resolve, { once: true });
      cdp.#socket.addEventListener("error", () => reject(new Error("CDP-Verbindung fehlgeschlagen")), { once: true });
    });
    cdp.#socket.addEventListener("message", (event) => cdp.#dispatch(JSON.parse(String(event.data))));
    return cdp;
  }

  #dispatch(message) {
    if (message.id !== undefined) {
      const waiter = this.#pending.get(message.id);
      if (!waiter) return;
      this.#pending.delete(message.id);
      if (message.error) waiter.reject(new Error(`${waiter.method}: ${message.error.message}`));
      else waiter.resolve(message.result ?? {});
      return;
    }
    for (const listener of this.#listeners) listener(message);
  }

  send(method, params = {}, sessionId) {
    const id = this.#nextId++;
    return new Promise((resolve, reject) => {
      this.#pending.set(id, { resolve, reject, method });
      this.#socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
    });
  }

  subscribe(listener) {
    this.#listeners.push(listener);
  }

  unsubscribe(listener) {
    this.#listeners = this.#listeners.filter((existing) => existing !== listener);
  }

  close() {
    this.#socket?.close();
  }
}

/** Eine Seite (Tab) mit eigener Sitzung. Sammelt Probleme: JavaScript-Fehler und Sicherheitsverstöße (CSP). */
export class Page {
  #cdp;
  #sessionId;
  problems = [];
  #targetId;

  static async open(cdp) {
    const { targetId } = await cdp.send("Target.createTarget", { url: "about:blank" });
    const { sessionId } = await cdp.send("Target.attachToTarget", { targetId, flatten: true });
    const page = new Page(cdp, sessionId, targetId);
    await page.send("Page.enable");
    await page.send("Runtime.enable");
    await page.send("Log.enable");
    page.#listener = (message) => {
      if (message.sessionId !== sessionId) return;
      page.#onEvent(message.method, message.params ?? {});
    };
    cdp.subscribe(page.#listener);
    return page;
  }

  #listener = null;

  constructor(cdp, sessionId, targetId) {
    this.#cdp = cdp;
    this.#sessionId = sessionId;
    this.#targetId = targetId;
  }

  #onEvent(method, params) {
    if (method === "Runtime.exceptionThrown") {
      const details = params.exceptionDetails ?? {};
      this.problems.push(`JavaScript-Fehler: ${details.exception?.description ?? details.text ?? "unbekannt"}`);
    } else if (method === "Log.entryAdded") {
      const entry = params.entry ?? {};
      if (entry.source === "security" || (entry.source === "javascript" && entry.level === "error")) {
        this.problems.push(`${entry.source}: ${entry.text}`);
      }
    }
  }

  send(method, params = {}) {
    return this.#cdp.send(method, params, this.#sessionId);
  }

  async goto(url) {
    const loaded = new Promise((resolve) => {
      const listener = (message) => {
        if (message.sessionId === this.#sessionId && message.method === "Page.loadEventFired") {
          this.#cdp.unsubscribe?.(listener);
          resolve();
        }
      };
      this.#cdp.subscribe(listener);
    });
    await this.send("Page.navigate", { url });
    await Promise.race([loaded, sleep(15000)]);
  }

  /** Auswertung im Seitenkontext, liefert einen JSON-Wert. */
  async eval(expression) {
    const result = await this.send("Runtime.evaluate", {
      expression,
      returnByValue: true,
      awaitPromise: true,
    });
    if (result.exceptionDetails) {
      throw new Error(`Auswertung fehlgeschlagen: ${result.exceptionDetails.exception?.description ?? "?"}`);
    }
    return result.result.value;
  }

  /** Wartet, bis der Ausdruck wahr liefert. Liefert dann den Wert. */
  async waitFor(expression, { timeout = 15000, what = expression } = {}) {
    const started = Date.now();
    for (;;) {
      const value = await this.eval(expression).catch(() => null);
      if (value) return value;
      if (Date.now() - started > timeout) {
        throw new Error(`Zeitüberschreitung (${timeout} ms): ${what}\n${await this.#snapshot()}`);
      }
      await sleep(150);
    }
  }

  /** Zustand für die Fehlermeldung: Adresse, Statuszeile, Anfang des Textes und gesammelte Probleme. */
  async #snapshot() {
    const state = await this.eval(`({
      url: location.pathname,
      status: document.getElementById('status')?.textContent ?? null,
      text: (document.body?.innerText ?? '').slice(0, 300),
    })`).catch((failure) => ({ error: String(failure.message) }));
    return `Seite: ${JSON.stringify(state)}\nProbleme: ${JSON.stringify(this.problems)}`;
  }

  async setViewport(width, height) {
    await this.send("Emulation.setDeviceMetricsOverride", {
      width,
      height,
      deviceScaleFactor: 1,
      mobile: width < 600,
    });
  }

  /**
   * Sprache der Seite. Die Gäste-Seite wählt Deutsch oder Englisch nach `navigator.language` (ADR 0018). Darum setzt der
   * Test die Akzeptanz-Sprache des Browsers, nicht nur die Locale für Intl.
   */
  async setLocale(locale) {
    await this.send("Emulation.setLocaleOverride", { locale }).catch(() => {});
    const userAgent = await this.eval("navigator.userAgent");
    const base = locale.split("-")[0];
    await this.send("Network.setUserAgentOverride", {
      userAgent,
      acceptLanguage: `${locale},${base};q=0.9`,
    });
  }

  /** Taste drücken (Esc, Tab, Umschalt-Tab), wie vom Benutzer. */
  async press(key) {
    const codes = {
      Escape: { code: "Escape", keyCode: 27 },
      Tab: { code: "Tab", keyCode: 9 },
      "Shift+Tab": { code: "Tab", keyCode: 9, modifiers: 8 },
    };
    const spec = codes[key];
    if (!spec) throw new Error(`Unbekannte Taste ${key}`);
    const base = {
      key: key.replace("Shift+", ""),
      code: spec.code,
      windowsVirtualKeyCode: spec.keyCode,
      modifiers: spec.modifiers ?? 0,
    };
    await this.send("Input.dispatchKeyEvent", { type: "keyDown", ...base });
    await this.send("Input.dispatchKeyEvent", { type: "keyUp", ...base });
  }

  async close() {
    if (this.#listener) this.#cdp.unsubscribe(this.#listener);
    this.#listener = null;
    await this.#cdp.send("Target.closeTarget", { targetId: this.#targetId }).catch(() => {});
  }
}
