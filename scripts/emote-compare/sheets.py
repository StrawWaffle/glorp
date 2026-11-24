"""Side-by-side sheets: for each case, the site's 12 moments (left) next to the
app's (right), cropped to the start of the line. Run in the work folder.

    sheets.py              every case, six to a sheet (sheet-00.png, ...)
    sheets.py 9 10 14      those cases on one sheet (sheet-picked.png)

The app's side assumes the spare test phone's screen (1080 x 2400, chat over
the sign-in bar); another screen needs `bar_top` and the row height adjusted."""
import json, os, sys
from PIL import Image, ImageDraw

cases = json.load(open('cases.json'))
times = json.load(open('out/times.json'))
ROWS = 12
W = 300            # device px of each line kept (from the left edge)
ROW = 60           # height each line is scaled to in the sheet


def site_rows(i):
    im = Image.open(f'out/site-{i:03d}.png').convert('RGB')
    top, h = round(40 * 2.625), (im.height - round(50 * 2.625)) / ROWS
    return [im.crop((0, round(top + k * h - h * 0.35), W, round(top + (k + 1) * h + h * 0.1))) for k in range(ROWS)]


def bar_top(im):
    # The sign-in bar under chat: find its blue from below, then its top edge.
    blue = lambda y: (lambda p: p[2] > p[0] + 15 and p[2] > 40)(im.getpixel((540, y)))
    y = 2350
    while y > 1500 and not blue(y):
        y -= 1
    while y > 1500 and blue(y):
        y -= 1
    return y + 1


def app_rows(i):
    im = Image.open(f'app/app-{i:03d}.png').convert('RGB')
    bottom = bar_top(im)
    h = 82.2
    top = bottom - ROWS * h
    return [im.crop((0, round(top + k * h - h * 0.35), W, round(top + (k + 1) * h + h * 0.1))) for k in range(ROWS)]


def column(rows):
    hh = round(ROW * 1.45)
    col = Image.new('RGB', (round(W * hh / rows[0].height), hh * ROWS), (17, 17, 17))
    for k, r in enumerate(rows):
        r = r.resize((round(r.width * hh / r.height), hh))
        col.paste(r, (0, k * hh))
    return col



def sheet(ids, name):
    blocks = []
    for i in ids:
        a, b = column(site_rows(i)), column(app_rows(i))
        blk = Image.new('RGB', (a.width + b.width + 6, a.height + 22), (60, 60, 60))
        blk.paste(a, (0, 22)); blk.paste(b, (a.width + 6, 22))
        d = ImageDraw.Draw(blk)
        d.text((4, 4), f"#{i} {cases[i]['text']} ({cases[i]['why']}) to {times[i]['times'][-1]} ms", fill=(255, 255, 0))
        blocks.append(blk)
    out = Image.new('RGB', (sum(b.width for b in blocks) + 4 * len(blocks), max(b.height for b in blocks)), (0, 0, 0))
    x = 0
    for b in blocks:
        out.paste(b, (x, 0)); x += b.width + 4
    scale = 1800 / out.width
    if scale < 1:
        out = out.resize((round(out.width * scale), round(out.height * scale)))
    out.save(name)


if __name__ == '__main__':
    ids = [int(a) for a in sys.argv[1:]]
    if ids:
        sheet(ids, 'sheet-picked.png')
    else:
        for n, start in enumerate(range(0, len(cases), 6)):
            sheet(list(range(start, min(start + 6, len(cases)))), f'sheet-{n:02d}.png')
