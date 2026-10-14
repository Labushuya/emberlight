import random, math, sys
import cairosvg

random.seed(7)
W, H = 1600, 560
CX, CY = 1180, 600  # ember core, bottom right, partly below the edge

parts = []

# Faint signal rings around the ember (the local "hotspot")
rings = ""
for i, r in enumerate([150, 250, 360, 480, 610]):
    op = 0.26 - i * 0.04
    rings += f'<circle cx="{CX}" cy="{CY}" r="{r}" fill="none" stroke="#ff9a4d" stroke-opacity="{op:.3f}" stroke-width="1.2" stroke-dasharray="2 9"/>'

# Rising sparks: dots, and a few that are tiny photo frames
sparks = ""
for _ in range(300):
    # bias positions upward from the core, drifting left
    t = random.random() ** 0.8
    y = CY - 60 - t * 620
    spread = 40 + t * 520
    x = CX + random.gauss(-t * 260, spread * 0.45)
    if not (-20 < x < W + 20 and -20 < y < H + 20):
        continue
    heat = 1 - t
    r = 0.7 + random.random() * 2.8 * (0.4 + heat)
    col = random.choice(["#ffd08a", "#ffb35c", "#ff8a3d", "#ff6a2b"]) if heat > 0.3 else random.choice(["#ff8a3d", "#e0552a", "#c9452b"])
    op = min(1, 0.25 + heat * 0.9) * (0.5 + random.random() * 0.5)
    sparks += f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{r:.2f}" fill="{col}" fill-opacity="{op:.2f}" filter="url(#soft)"/>'

frames = ""
frame_spots = [(1040, 330, 26, -14), (1270, 250, 30, 9), (1135, 140, 22, -6),
               (1385, 380, 20, 16), (960, 205, 17, 11), (1230, 70, 15, -18),
               (880, 360, 13, -8), (1450, 160, 14, 6)]
for (x, y, s, rot) in frame_spots:
    h = s * 1.18
    heat = max(0.15, min(1, (y + 50) / 450))
    op = 0.35 + heat * 0.6
    frames += (f'<g transform="translate({x} {y}) rotate({rot})" opacity="{op:.2f}" filter="url(#glow)">'
               f'<rect x="{-s/2:.1f}" y="{-h/2:.1f}" width="{s}" height="{h:.1f}" rx="2" fill="#2a1410" stroke="#ffc27a" stroke-width="1.6"/>'
               f'<rect x="{-s/2+3:.1f}" y="{-h/2+3:.1f}" width="{s-6}" height="{s-6}" rx="1" fill="url(#photo)"/>'
               f'</g>')

svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">
<defs>
  <linearGradient id="bg" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="#0b0810"/>
    <stop offset="0.65" stop-color="#140c12"/>
    <stop offset="1" stop-color="#22110f"/>
  </linearGradient>
  <radialGradient id="core" cx="{CX}" cy="{CY}" r="640" gradientUnits="userSpaceOnUse">
    <stop offset="0" stop-color="#ffcf8a" stop-opacity="0.95"/>
    <stop offset="0.12" stop-color="#ff8a3d" stop-opacity="0.75"/>
    <stop offset="0.38" stop-color="#b8401f" stop-opacity="0.28"/>
    <stop offset="1" stop-color="#3a1410" stop-opacity="0"/>
  </radialGradient>
  <linearGradient id="photo" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="#ffb35c"/>
    <stop offset="1" stop-color="#8a2f1c"/>
  </linearGradient>
  <linearGradient id="word" x1="0" y1="0" x2="1" y2="0">
    <stop offset="0" stop-color="#f6e6d4"/>
    <stop offset="1" stop-color="#ffc58a"/>
  </linearGradient>
  <filter id="soft" x="-200%" y="-200%" width="500%" height="500%"><feGaussianBlur stdDeviation="0.6"/></filter>
  <filter id="glow" x="-100%" y="-100%" width="300%" height="300%">
    <feGaussianBlur stdDeviation="3" result="b"/>
    <feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge>
  </filter>
</defs>
<rect width="{W}" height="{H}" fill="url(#bg)"/>
<rect width="{W}" height="{H}" fill="url(#core)"/>
{rings}
{sparks}
{frames}
<g font-family="Fraunces">
  <text x="120" y="268" font-size="118" font-weight="300" fill="url(#word)" letter-spacing="1">emberlight</text>
  <text x="124" y="336" font-size="31" font-weight="300" fill="#e9cdb3" fill-opacity="0.92" font-style="italic">Teile den Abend mit allen, die dabei waren.</text>
</g>
<g font-family="Inter" font-size="17" fill="#c9a88d" fill-opacity="0.75" letter-spacing="3">
  <text x="126" y="410">GALERIE · EIGENES WLAN · KEINE CLOUD</text>
</g>
</svg>'''

out = sys.argv[1]
open(out + ".svg", "w").write(svg)
cairosvg.svg2png(bytestring=svg.encode(), write_to=out + ".png", output_width=W, output_height=H)
