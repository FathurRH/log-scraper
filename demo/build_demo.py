"""Builds the static demo pages from the real pages: python3 demo/build_demo.py (run from the repo root).
The demo adds a banner, demo-api.js (recorded answers instead of the server) and plain index.html links."""
import pathlib, shutil

SRC = pathlib.Path('log-scraper/src/main/resources/static')
OUT = pathlib.Path('demo')
CSS = """  .demo-banner { background: #fff7d6; color: #5c4400; border-bottom: 1px solid #e8d48a; padding: 8px 16px; text-align: center; font-size: 14px; }
  @media (prefers-color-scheme: dark) { .demo-banner { background: #3a3000; color: #ffe9a0; border-color: #6b5a10; } }
  .demo-banner button { margin-left: 8px; }
</style>"""
BANNER = ('<body>\n<div class="demo-banner" id="demoBanner">Demo dengan data contoh: tidak ada server asli yang dihubungi. '
          'Coba ID <b>REQ-88231</b>, <b>REQ-88232</b> atau <b>REQ-88233</b>.</div>')

for name in ['index.html', 'settings.html']:
    s = (SRC / name).read_text(encoding='utf-8')
    s = s.replace('</style>', CSS, 1).replace('<body>', BANNER, 1).replace('<a href="./"', '<a href="index.html"')
    i = s.rindex('\n<script>\n')
    s = s[:i] + '\n<script src="demo-api.js"></script>' + s[i:]
    (OUT / name).write_text(s, encoding='utf-8')
shutil.copy(SRC / 'i18n.js', OUT / 'i18n.js')
print('demo pages rebuilt')
