// Erzeugt das Video-Fixture für die Browser-Tests (clip.mp4). Ohne ffmpeg: Chrome nimmt ein Canvas als MP4 auf (MediaRecorder,
// H.264). Die Datei wird einmal erzeugt und eingecheckt. Aufruf mit Node 22 oder neuer:
//   node share/server/src/test/browser/fixtures/make-clip.mjs <Zieldatei>
// Die Datei ist klein (ein bis zwei Sekunden, 160×120). Das Skript schreibt nur die angegebene Zieldatei.

import { writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { findChrome, launchChrome, stopChrome, Cdp, Page } from "../lib/cdp.mjs";

const target = process.argv[2];
if (!target) {
  console.error("Zieldatei fehlt.");
  process.exit(2);
}

const chromePath = findChrome();
if (!chromePath) {
  console.error("Kein Chrome gefunden (EMBERLIGHT_CHROME setzen).");
  process.exit(2);
}

const chrome = await launchChrome({ chromePath, profileBase: process.env.EMBERLIGHT_BROWSER_TMP || tmpdir() });
const cdp = await Cdp.connect(chrome.wsUrl);
try {
  const page = await Page.open(cdp);
  const result = await page.eval(`(async () => {
    const canvas = document.createElement('canvas');
    canvas.width = 160; canvas.height = 120;
    const ctx = canvas.getContext('2d');
    const candidates = ['video/mp4;codecs=avc1.42E01E', 'video/mp4'];
    const mime = candidates.find((type) => MediaRecorder.isTypeSupported(type));
    if (!mime) return { error: 'kein MP4 bei MediaRecorder' };
    const stream = canvas.captureStream(15);
    const recorder = new MediaRecorder(stream, { mimeType: mime });
    const chunks = [];
    recorder.ondataavailable = (event) => { if (event.data.size > 0) chunks.push(event.data); };
    let frame = 0;
    const timer = setInterval(() => {
      ctx.fillStyle = 'hsl(' + ((frame * 12) % 360) + ', 80%, 50%)';
      ctx.fillRect(0, 0, 160, 120);
      frame += 1;
    }, 66);
    const stopped = new Promise((resolve) => { recorder.onstop = resolve; });
    recorder.start(100);
    await new Promise((resolve) => setTimeout(resolve, 1500));
    recorder.stop();
    clearInterval(timer);
    await stopped;
    const bytes = new Uint8Array(await new Blob(chunks, { type: mime }).arrayBuffer());
    let binary = '';
    for (let i = 0; i < bytes.length; i += 8192) {
      binary += String.fromCharCode(...bytes.subarray(i, i + 8192));
    }
    return { mime, size: bytes.length, base64: btoa(binary) };
  })()`);
  if (result.error) throw new Error(result.error);
  writeFileSync(target, Buffer.from(result.base64, "base64"));
  console.log(`geschrieben: ${target} (${result.size} Bytes, ${result.mime})`);
} finally {
  cdp.close();
  await stopChrome(chrome);
}
