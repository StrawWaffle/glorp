"""Combos side by side: the site's combo line (chat-gui's markup and combo CSS
with the real emotes.css) next to the app's, at six moments after the combo
ended, for a few counts and emotes. Run in the work folder after compare.sh
has fetched emotes.css; needs the dev build on the phone.

    combos.py            renders both sides and writes combo-sheet.png
"""
import asyncio, base64, json, os, re, subprocess, sys, tempfile, time, urllib.request
import websockets
from PIL import Image, ImageDraw

CHROME = os.environ.get("CHROME", r"C:\Program Files\Google\Chrome\Application\chrome.exe")
CASES = [("PEPE", 2), ("PEPE", 5), ("GIGACHAD", 10), ("EatFresh", 12), ("PEPE", 20), ("PEPE", 50)]
TIMES = [0, 300, 700, 1200, 2700, 4000]   # ms after the combo ended
here = os.path.dirname(os.path.abspath(__file__))


def splat_url():
    page = urllib.request.urlopen(urllib.request.Request("https://www.destiny.gg/embed/chat", headers={"User-Agent": "Mozilla/5.0"})).read().decode()
    css = re.search(r'href="(https?://[^"]*chat[^"]*\.css)"', page).group(1)
    sheet = urllib.request.urlopen(urllib.request.Request(css, headers={"User-Agent": "Mozilla/5.0"})).read().decode()
    path = re.search(r'url\(["\']?([^)"\']*emote-splat[^)"\']*)', sheet).group(1)
    return path if path.startswith("http") else css.rsplit("/", 1)[0] + "/" + path


def group(n):
    return "x50" if n >= 50 else "x30" if n >= 30 else "x20" if n >= 20 else "x10" if n >= 10 else "x5" if n >= 5 else "x2"


def site(out):
    splat = splat_url()
    rows = []
    for c, (emote, n) in enumerate(CASES):
        g = group(n)
        for k, t in enumerate(TIMES):
            rows.append(f'<div class="msg-chat msg-emote" data-combo="{n}" data-combo-group="{g}" data-t="{t}" id="r{c}-{k}">'
                        f'<span class="text"><div title="{emote}" class="emote {emote}">{emote} </div></span>'
                        f'<span class="chat-combo {g} combo-complete"><i class="count">{n}</i> <i class="x">X</i> '
                        f'<i class="hit">Hits</i> <i class="combo">C-C-C-COMBO</i></span></div>')
    # chat-gui's combo CSS (messages/emote/_combo.scss), compiled by hand.
    combo_css = open(os.path.join(here, "combo.css"), encoding="utf-8").read().replace("SPLAT", splat)
    html = f'''<!doctype html><html><head><meta charset="utf-8">
<link rel="stylesheet" href="https://r2cdn.destiny.gg/emotes/emotes.css">
<style>
body {{ background: #111; color: #b9b9b9; font: 14px Roboto, Helvetica, sans-serif; margin: 0; width: 412px; }}
.chat-lines {{ line-height: 1.65em; }}
.msg-chat {{ position: relative; word-wrap: break-word; padding: 0.2em 1.2em 0.2em 0.6em; height: 40px; }}
.emote {{ display: inline-block; position: relative; overflow: hidden; text-indent: -999em; }}
*, *::before, *::after {{ animation-play-state: paused !important; }}
{combo_css}
</style></head><body><div class="chat-lines">{"".join(rows)}</div></body></html>'''
    page = os.path.abspath(os.path.join(out, "combo-site.html"))
    open(page, "w", encoding="utf-8").write(html)
    js = '''(() => { for (const row of document.querySelectorAll('.msg-chat')) { const t = +row.dataset.t;
      row.getAnimations({subtree: true}).forEach(a => { a.pause(); a.currentTime = t; }); }
      return JSON.stringify([...document.querySelectorAll('.msg-chat')].map(r => { const b = r.getBoundingClientRect(); return [b.left, b.top + scrollY, b.width, b.height]; })); })()'''

    async def run():
        prof = tempfile.mkdtemp()
        proc = subprocess.Popen([CHROME, "--headless=new", "--remote-debugging-port=9336", f"--user-data-dir={prof}", "--hide-scrollbars", "about:blank"])
        try:
            for _ in range(50):
                try:
                    pages = json.load(urllib.request.urlopen("http://localhost:9336/json")); break
                except Exception:
                    time.sleep(0.2)
            url = next(p for p in pages if p["type"] == "page")["webSocketDebuggerUrl"]
            async with websockets.connect(url, max_size=64 << 20) as ws:
                n = 0
                async def call(m, p=None):
                    nonlocal n; n += 1
                    await ws.send(json.dumps({"id": n, "method": m, "params": p or {}}))
                    while True:
                        r = json.loads(await ws.recv())
                        if r.get("id") == n:
                            return r
                await call("Emulation.setDeviceMetricsOverride", {"width": 412, "height": 900, "deviceScaleFactor": 2.625, "mobile": True})
                await call("Page.navigate", {"url": "file:///" + page.replace(chr(92), "/")})
                await asyncio.sleep(8)
                boxes = json.loads((await call("Runtime.evaluate", {"expression": js, "returnByValue": True}))["result"]["result"]["value"])
                await asyncio.sleep(1)
                for i, (x, y, w, h) in enumerate(boxes):
                    shot = await call("Page.captureScreenshot", {"format": "png", "captureBeyondViewport": True, "clip": {"x": x, "y": y - 10, "width": w, "height": h + 10, "scale": 1}})
                    open(os.path.join(out, f"combo-site-{i:02d}.png"), "wb").write(base64.b64decode(shot["result"]["data"]))
        finally:
            proc.kill()
    asyncio.run(run())


def adb(*args):
    return subprocess.run(["adb", *args], capture_output=True, check=False).stdout.decode("utf-8", "replace")


def app(out):
    pkg = "io.github.strawwaffle.glorp.dev"
    for c, (emote, n) in enumerate(CASES):
        x = int(adb("shell", "date", "+%s").strip()) * 1000 - 20000
        frames = []
        for k, t in enumerate(TIMES):
            # Each row's combo ended t ms before the frozen moment, its hits 50 ms apart before that.
            ended = x - t
            hits = [ended - 400 - 50 * (n - 1 - j) for j in range(n)]
            for j, at in enumerate(hits):
                frames.append("MSG " + json.dumps({"nick": f"c{k}u{j}", "timestamp": at, "data": emote}))
            frames.append("MSG " + json.dumps({"nick": f"s{k}", "timestamp": ended, "data": f"row {k}"}))
        path = os.path.join(out, "combo-frames")
        open(path, "w", newline="\n").write("\n".join(frames) + "\n")
        subprocess.run(["adb", "push", path, f"/sdcard/Android/data/{pkg}/files/dev-frames"], capture_output=True)
        subprocess.run(["adb", "logcat", "-c"])
        subprocess.run(["adb", "shell", "am", "start", "-S", "-n", f"{pkg}/io.github.strawwaffle.glorp.MainActivity",
                        "--es", "emote-cdn", "https://r2cdn.destiny.gg", "--es", "emote-clock", str(x)], capture_output=True)
        for _ in range(40):
            if "Glorp chat: connected" in adb("logcat", "-d"):
                break
            time.sleep(0.5)
        time.sleep(6)
        png = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout
        open(os.path.join(out, f"combo-app-{c}.png"), "wb").write(png)


def sheet(out):
    cols = []
    for c, (emote, n) in enumerate(CASES):
        site_rows = [Image.open(os.path.join(out, f"combo-site-{c * len(TIMES) + k:02d}.png")).convert("RGB") for k in range(len(TIMES))]
        shot = Image.open(os.path.join(out, f"combo-app-{c}.png")).convert("RGB")
        # The app's: each combo row sits above its "row k" line; 12 rows above the sign-in bar.
        y = 2350
        blue = lambda yy: (lambda p: p[2] > p[0] + 15 and p[2] > 40)(shot.getpixel((540, yy)))
        while y > 1500 and not blue(y): y -= 1
        while y > 1500 and blue(y): y -= 1
        h = 82.2
        app_rows = [shot.crop((0, round(y - (2 * (len(TIMES) - k)) * h - 30), 1080, round(y - (2 * (len(TIMES) - k) - 1) * h + 10))) for k in range(len(TIMES))]
        width = 700
        col = Image.new("RGB", (width * 2 + 10, 24 + 150 * len(TIMES)), (40, 40, 40))
        ImageDraw.Draw(col).text((4, 4), f"{emote} x{n}, ended: " + ", ".join(f"{t}ms" for t in TIMES), fill=(255, 255, 0))
        for k in range(len(TIMES)):
            for j, im in enumerate((site_rows[k], app_rows[k])):
                im = im.crop((0, 0, min(width, im.width), im.height))
                im = im.resize((round(im.width * 140 / im.height), 140))
                col.paste(im, (j * (width + 10), 24 + k * 150))
        cols.append(col)
    out_im = Image.new("RGB", (cols[0].width * 2 + 10, cols[0].height * ((len(cols) + 1) // 2)), (0, 0, 0))
    for i, col in enumerate(cols):
        out_im.paste(col, ((i % 2) * (col.width + 10), (i // 2) * col.height))
    out_im.thumbnail((2400, 2400))
    out_im.save(os.path.join(out, "combo-sheet.png"))


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    if "--sheet" not in sys.argv:
        site(out)
        app(out)
    sheet(out)
    print(os.path.join(out, "combo-sheet.png"))
