#!/usr/bin/env python3
"""Impagina gli screenshot dell'emulatore (1080x2400, troppo lunghi per Play) in immagini 1080x1920 con una didascalia."""
import base64, json, os, sys
from playwright.sync_api import sync_playwright
HERE = os.path.dirname(os.path.abspath(__file__))
SRC = sys.argv[1]; OUT = sys.argv[2]; LANG = sys.argv[3] if len(sys.argv) > 3 else 'it'
os.makedirs(OUT, exist_ok=True)
CAP = json.load(open(os.path.join(HERE, 'didascalie.json'), encoding='utf-8'))[LANG]
HTML = """<!doctype html><html><head><meta charset="utf-8"><style>
html,body{margin:0;width:1080px;height:1920px;background:#0B0B0C;font-family:Inter,sans-serif;overflow:hidden}
.band{position:absolute;left:0;top:0;width:1080px;height:14px;background:#E10A1E}
.t{position:absolute;left:80px;right:80px;top:96px;color:#fff;font-weight:800;font-size:66px;line-height:1.08;letter-spacing:-1.2px}
.t b{color:#E10A1E;font-weight:800}
.s{position:absolute;left:82px;right:80px;top:%(subtop)dpx;color:#B5B5BA;font-size:34px;line-height:1.3;font-weight:500}
.ph{position:absolute;left:50%%;transform:translateX(-50%%);bottom:-40px;width:%(pw)dpx;border-radius:56px;overflow:hidden;border:10px solid #2A2A2D;box-shadow:0 30px 80px rgba(0,0,0,.6)}
.ph img{display:block;width:100%%}
</style></head><body><div class="band"></div><div class="t">%(title)s</div><div class="s">%(sub)s</div>
<div class="ph"><img src="data:image/png;base64,%(img)s"></div></body></html>"""
with sync_playwright() as p:
    b = p.chromium.launch(); pg = b.new_page(viewport={'width': 1080, 'height': 1920})
    for i, c in enumerate(CAP):
        f = os.path.join(SRC, c['file'])
        if not os.path.exists(f): print('manca', f); continue
        img = base64.b64encode(open(f, 'rb').read()).decode()
        lines = 2 if len(c['title']) > 26 else 1
        pg.set_content(HTML % dict(title=c['title'], sub=c['sub'], img=img, pw=c.get('w', 700), subtop=96 + lines * 72 + 22))
        pg.wait_for_timeout(200)
        out = os.path.join(OUT, '%02d_%s.png' % (i + 1, c['file'].rsplit('.', 1)[0].split('_', 1)[-1]))
        pg.screenshot(path=out); print(out)
    b.close()
