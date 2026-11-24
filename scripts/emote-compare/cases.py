"""Test lines for the emote comparison, from the site's emotes.css: every emote
whose own rules animate it, and every `A + B` pair rule. usage: cases.py css json out.json"""
import json, re, sys

css = re.sub(r'/\*.*?\*/', '', open(sys.argv[1], encoding='utf-8').read(), flags=re.S)
names = {e['prefix'] for e in json.load(open(sys.argv[2], encoding='utf-8'))}

cases = []
seen = set()
def add(text, why, features=None):
    key = (text, tuple(features or []))
    if key not in seen:
        seen.add(key); cases.append({'text': text, 'why': why, 'features': features or []})

body_re = re.compile(r'([^{}@]+)\{([^{}]*)\}')
# Rules inside @keyframes are skipped: only top-level-ish rules (keyframe steps have % or from/to selectors).
for m in body_re.finditer(css):
    sel_text, body = m.group(1).strip(), m.group(2)
    if 'animation' not in body or sel_text.startswith(('from', 'to')) or re.match(r'^[\d.%,\s]+$', sel_text):
        continue
    for sel in [s.strip() for s in sel_text.split(',')]:
        if ':hover' in sel:
            continue
        own = re.fullmatch(r'(?:\.msg-chat |\.text ?> ?)?\.emote\.([\w-]+)(::?(?:before|after))?', sel)
        if own and own.group(1) in names:
            add(own.group(1), 'own')
            continue
        pair = re.fullmatch(r'(?:\.text ?> ?)?\.emote\.([\w-]+) ?\+ ?\.emote\.([\w-]+)(::?(?:before|after))?', sel)
        if pair and pair.group(1) in names and pair.group(2) in names:
            add(f'{pair.group(1)} {pair.group(2)}', 'pair')
            continue
        flair = re.search(r'\.user\.(flair\d+) ~ \.text \.emote\.([\w-]+)$', sel)
        if flair and flair.group(2) in names:
            add(flair.group(2), 'flair', ['subscriber', flair.group(1)])
            continue
json.dump(cases, open(sys.argv[3], 'w'), indent=0)
print(len(cases), 'cases:', sum(c['why'] == 'own' for c in cases), 'own,', sum(c['why'] == 'pair' for c in cases), 'pairs,',
      sum(c['why'] == 'flair' for c in cases), 'flair')
