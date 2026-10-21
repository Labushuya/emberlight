// Hilfen für die Upload-Tests der Gäste-Seite (Paket 3.5b): Warteschlange lesen, Dateien wählen, Anfragen zählen.
// Die Texte sind englisch (die Tests laufen mit en-US). Die Zustände entsprechen data-state der Warteschlange.

export const UPLOAD_PATH = "/api/v1/media";

export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** Anzahl der Upload-Anfragen seit dem Einschalten des Protokolls. */
export function uploadRequests(page) {
  return page.networkLog.filter((entry) => entry.method === "POST" && entry.url.endsWith(UPLOAD_PATH)).length;
}

/** Alle Zeilen der Warteschlange mit Name, Zustand, Statustext, Fortschritt in Prozent und Knopfbeschriftungen. */
export function queue(page) {
  return page.eval(`[...document.querySelectorAll('#upload-queue .q-item')].map((li) => ({
    name: li.querySelector('.q-name').textContent,
    state: li.getAttribute('data-state'),
    status: li.querySelector('.q-status').textContent,
    percent: parseFloat(li.querySelector('.bar i')?.style.width || '0') || 0,
    buttons: [...li.querySelectorAll('button')].map((button) => button.textContent),
  }))`);
}

/** Wartet, bis eine Zeile mit dem Dateinamen (Anfang des Namens) den Zustand hat. */
export function waitForItem(page, name, state, timeout = 30000) {
  const expression = `[...document.querySelectorAll('#upload-queue .q-item')].some((li) =>
    li.querySelector('.q-name').textContent.startsWith(${JSON.stringify(name)}) &&
    li.getAttribute('data-state') === ${JSON.stringify(state)})`;
  return page.waitFor(expression, { what: `${name} im Zustand ${state}`, timeout });
}

/** Klickt den Knopf mit der Beschriftung in der Zeile der Datei. */
export function clickItemButton(page, name, label) {
  return page.eval(`(() => {
    const li = [...document.querySelectorAll('#upload-queue .q-item')]
      .find((row) => row.querySelector('.q-name').textContent.startsWith(${JSON.stringify(name)}));
    const button = li && [...li.querySelectorAll('button')].find((node) => node.textContent === ${JSON.stringify(label)});
    if (!button) return false;
    button.click();
    return true;
  })()`);
}

/** Wählt Dateien über das Eingabefeld, wie die Auswahl im Dialog. */
export function selectFiles(page, paths) {
  return page.setFiles("#file-input", paths);
}
