"""Renders every case with the site's real emotes.css in headless Chrome, each
case as 12 lines at evenly spaced moments of its animation, and saves a
picture per case plus the moments used (times.json) for the app's side.

usage: site.py cases.json outdir"""
import asyncio, base64, json, os, subprocess, sys, tempfile, time, urllib.request
import websockets

CHROME = os.environ.get("CHROME", r"C:\Program Files\Google\Chrome\Application\chrome.exe")
CSS = "https://r2cdn.destiny.gg/emotes/emotes.css"
FRAMES = 12
cases = json.load(open(sys.argv[1]))
out = sys.argv[2]
os.makedirs(out, exist_ok=True)


def esc(s):
    return s.replace('&', '&amp;').replace('<', '&lt;').replace('"', '&quot;')


def line(case, k):
    words = []
    for w in case['text'].split():
        words.append(f'<div title="{esc(w)}" class="emote {esc(w)}">{esc(w)} </div>')
    feats = ' '.join(case['features'])
    flair = next((f for f in case['features'] if f.startswith('flair')), '')
    return (f'<div class="msg-chat msg-user {feats}" data-username="t{k}">'
            f'<a class="user {flair}">t{k}</a><span class="ctrl">: </span> '
            f'<span class="text">{" ".join(words)} {k}</span></div>')


blocks = []
for i, c in enumerate(cases):
    rows = ''.join(line(c, k) for k in range(FRAMES))
    blocks.append(f'<div class="case" id="c{i}">{rows}</div>')
html = f'''<!doctype html><html><head><meta charset="utf-8">
<link rel="stylesheet" href="{CSS}">
<style>
body {{ background: #111; color: #b9b9b9; font: 14px Roboto, Helvetica, sans-serif; margin: 0; width: 412px; }}
.chat-lines {{ line-height: 1.65em; }}
.case {{ padding: 40px 0 10px; border-bottom: 1px solid #333; }}
.msg-chat {{ position: relative; word-wrap: break-word; padding: 0.2em 1.2em 0.2em 0.6em; }}
.msg-chat .user {{ font-weight: 500; margin-left: 0.3em; color: #fff; }}
*, *::before, *::after {{ animation-play-state: paused !important; }}
.emote {{ display: inline-block; position: relative; overflow: hidden; text-indent: -999em; }}
</style></head><body><div class="chat-lines">{"".join(blocks)}</div></body></html>'''
page = os.path.abspath(os.path.join(out, 'site.html'))
open(page, 'w', encoding='utf-8').write(html)

SET_TIMES = '''(() => {
  const out = [];
  for (const block of document.querySelectorAll('.case')) {
    const rows = [...block.querySelectorAll('.text')];
    const first = rows[0].getAnimations({subtree: true});
    let d = 0;
    for (const a of first) {
      const t = a.effect.getComputedTiming();
      d = Math.max(d, isFinite(t.endTime) ? t.endTime : (t.delay || 0) + t.duration);
    }
    d = Math.min(Math.max(d, 100), 3000);
    const times = rows.map((_, k) => Math.round(k * d / (rows.length - 1)));
    rows.forEach((row, k) => row.getAnimations({subtree: true}).forEach(a => { a.pause(); a.currentTime = times[k]; }));
    const r = block.getBoundingClientRect();
    out.push({times, x: r.left, y: r.top + scrollY, w: r.width, h: r.height, anims: first.length});
  }
  return JSON.stringify(out);
})()'''


async def main():
    profile = tempfile.mkdtemp()
    proc = subprocess.Popen([CHROME, '--headless=new', '--remote-debugging-port=9334', f'--user-data-dir={profile}',
                             '--hide-scrollbars', '--force-device-scale-factor=2.625', 'about:blank'])
    try:
        for _ in range(50):
            try:
                pages = json.load(urllib.request.urlopen('http://localhost:9334/json'))
                break
            except Exception:
                time.sleep(0.2)
        url = next(p for p in pages if p['type'] == 'page')['webSocketDebuggerUrl']
        async with websockets.connect(url, max_size=64 << 20) as ws:
            n = 0
            async def call(method, params=None):
                nonlocal n; n += 1
                await ws.send(json.dumps({'id': n, 'method': method, 'params': params or {}}))
                while True:
                    m = json.loads(await ws.recv())
                    if m.get('id') == n:
                        return m
            await call('Page.enable')
            await call('Emulation.setDeviceMetricsOverride', {'width': 412, 'height': 900, 'deviceScaleFactor': 2.625, 'mobile': True})
            await call('Page.navigate', {'url': 'file:///' + page.replace('\\', '/')})
            await asyncio.sleep(8)  # stylesheet and pictures
            r = await call('Runtime.evaluate', {'expression': SET_TIMES, 'returnByValue': True})
            info = json.loads(r['result']['result']['value'])
            await asyncio.sleep(1)
            for i, b in enumerate(info):
                shot = await call('Page.captureScreenshot', {'format': 'png', 'captureBeyondViewport': True,
                                                             'clip': {'x': b['x'], 'y': b['y'], 'width': b['w'], 'height': b['h'], 'scale': 1}})
                open(os.path.join(out, f'site-{i:03d}.png'), 'wb').write(base64.b64decode(shot['result']['data']))
            json.dump([{'times': b['times'], 'anims': b['anims']} for b in info], open(os.path.join(out, 'times.json'), 'w'))
            print('cases', len(info), 'without animations on the first line:', sum(1 for b in info if b['anims'] == 0))
    finally:
        proc.kill()

asyncio.run(main())
