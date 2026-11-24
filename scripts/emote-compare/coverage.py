"""Emote CSS coverage: every declaration that can reach an emote in the site's
emotes.css, classified against what Glorp's parser reads (site/Emotes.kt,
Motion.kt, Filter.kt, EmoteContext.kt). Keep the sets below in step with them.

usage: python coverage.py emotes.css emotes.json out.json
"""
import json, re, sys
from collections import defaultdict

css_path, json_path, out_path = sys.argv[1:4]
css = re.sub(r'/\*.*?\*/', '', open(css_path, encoding='utf-8').read(), flags=re.S)
names = {e['prefix'] for e in json.load(open(json_path, encoding='utf-8'))}


def blocks(text):
    out, i, n = [], 0, len(text)
    while i < n:
        j = text.find('{', i)
        if j < 0:
            break
        head = text[i:j].strip()
        depth, k = 1, j + 1
        while k < n and depth:
            depth += {'{': 1, '}': -1}.get(text[k], 0)
            k += 1
        out.append((head, text[j + 1:k - 1]))
        i = k
    return out


def split_top(text, sep=','):
    parts, depth, start = [], 0, 0
    for i, c in enumerate(text):
        if c in '([':
            depth += 1
        elif c in ')]':
            depth -= 1
        elif c == sep and depth == 0:
            parts.append(text[start:i]); start = i + 1
    parts.append(text[start:])
    return [p.strip() for p in parts if p.strip()]


def decls(body):
    out = []
    for d in split_top(body, ';'):
        if ':' in d:
            k, v = d.split(':', 1)
            out.append((k.strip().lower(), v.replace('!important', '').strip()))
    return out


rules, keyframes = [], {}
def walk(text, wrapper=None):
    for head, body in blocks(text):
        if head.startswith('@keyframes'):
            keyframes[head.split()[1]] = body
        elif head.startswith('@'):
            walk(body, head)
        else:
            rules.append((re.sub(r'\s+', ' ', head), body, wrapper))
walk(css)

# --- What Glorp reads -------------------------------------------------------
READ_PROPS = {'animation', 'width', 'height', 'background-position', 'background-size', 'background-image',
              'transform', 'transform-origin', 'filter', 'opacity', 'animation-duration', 'animation-delay',
              'animation-iteration-count', 'animation-direction', 'translate', 'rotate', 'scale',
              'margin', 'margin-left', 'margin-right', 'margin-inline', 'vertical-align', '-webkit-filter'}
# What a ::before or ::after with text is drawn from.
PSEUDO_READ = {'content', 'font-size', 'color', 'top', 'left', 'right', 'transform', 'transform-origin', 'opacity',
               'filter', 'animation', 'animation-delay', 'position', 'text-indent', 'line-height', 'width', 'display'}
TRANSFORM_FNS = {'translate', 'translate3d', 'translatex', 'translatey', 'rotate', 'rotatez', 'scale', 'scalex',
                 'scaley', 'scale3d', 'rotate3d'}
# Animation longhands Glorp reads only as the context-rule timing change, not their own way.
LAYOUT_DONE = {'margin-top', 'top'}  # the standard -height / height/4 pair, drawn by emoteRect
HARMLESS = {'display', 'position', 'overflow', 'background-repeat', 'background-color', 'cursor', 'text-indent',
            'transition', 'transition-duration', 'transition-property', 'transition-timing-function',
            'will-change', 'backface-visibility', 'image-rendering', 'animation-play-state', 'animation-name',
            'pointer-events', 'user-select', 'box-sizing', 'vertical-align'}

own_pseudo = re.compile(r'^(?:\.text ?> ?)?\.emote\.[A-Za-z0-9_-]+::?(?:before|after)$')
own_re = re.compile(r'^(?:\.msg-chat |\.text ?> ?)?\.emote\.([A-Za-z0-9_-]+)$')
fn_re = re.compile(r'([a-zA-Z0-9-]+)\(')


def subject_names(sel):
    flat = sel
    for _ in range(4):
        flat = re.sub(r'\([^()]*\)', '', flat)
    last = re.split(r'\s*[ >+~]\s*', flat.strip())[-1]
    cls = [c for c in re.findall(r'\.([\w-]+)', last) if c in names]
    return cls


def selector_kind(sel):
    if ':hover' in sel:
        return 'hover'
    if '::before' in sel or '::after' in sel or ':before' in sel or ':after' in sel:
        return 'pseudo-element'
    if own_re.match(sel):
        return 'own'
    return 'context'


def value_gaps(prop, value):
    """What part of a read property's value Glorp can't draw."""
    gaps = []
    if 'var(' in value:
        gaps.append(f'{prop}: var()')
    if prop == 'transform':
        bad = {f.lower() for f in fn_re.findall(value)} - TRANSFORM_FNS - {'calc', 'var'}
        gaps += [f'transform: {b}()' for b in sorted(bad)]
        if 'calc(' in value and not re.fullmatch(r'.*calc\(\s*-?[\d.]+\s*[*/]\s*-?[\d.]+\s*\).*', value):
            gaps.append('transform: calc()')
    if prop in ('filter', '-webkit-filter'):
        fns = {f.lower() for f in fn_re.findall(value)} - {'rgba', 'rgb', 'var'}
        drawn = {'brightness', 'contrast', 'saturate', 'grayscale', 'sepia', 'invert', 'opacity', 'hue-rotate', 'drop-shadow'}
        gaps += [f'filter: {f}()' for f in sorted(fns - drawn)]
    if prop == 'background-position':
        for w in value.split():
            if not (w == '0' or w.endswith('px')):
                gaps.append(f'background-position: {w}')
    return gaps


def keyframe_gaps(name, stepped):
    body = keyframes.get(name)
    if body is None:
        return [f'animation: unknown keyframes {name}']
    gaps = []
    for sel, b in blocks(body):
        for k, v in decls(b):
            if k == 'animation-timing-function':
                gaps.append('keyframes: per-keyframe timing')
            elif k == 'background-position':
                if not stepped:
                    gaps += value_gaps(k, v)
            elif k in ('transform', 'opacity', 'filter', '-webkit-filter'):
                gaps += value_gaps(k, v)
            elif k == 'clip-path':
                if not re.fullmatch(r'polygon\([^()]*\)', v.strip()):
                    gaps.append('keyframes: clip-path other than a polygon')
            elif k in ('transform-origin', 'width', 'margin-left', 'top', 'left'):
                pass
            elif k in ('translate', 'rotate', 'scale'):
                gaps.append(f'keyframes: {k} property')
            elif k in HARMLESS:
                pass
            else:
                gaps.append(f'keyframes: {k}')
    return gaps


def animation_gaps(value):
    gaps = []
    for part in split_top(value):
        words = re.sub(r'\([^)]*\)', ' ', part).split()
        kf = [w for w in words if w in keyframes]
        stepped = 'steps(' in part or 'step-start' in part or 'step-end' in part
        if not kf:
            if part not in ('none', 'unset', 'initial', 'inherit'):
                gaps.append('animation: names no keyframes')
            continue
        if any(w in ('reverse', 'alternate-reverse') for w in words) and stepped:
            gaps.append('animation: reversed strip')
        gaps += keyframe_gaps(kf[0], stepped)
    return gaps


per_emote = defaultdict(lambda: defaultdict(set))   # emote -> gap -> {kinds}
gap_emotes = defaultdict(set)
for sel_text, body, wrapper in rules:
    if 'emote' not in sel_text:
        continue
    ds = decls(body)
    for sel in split_top(sel_text):
        kind = selector_kind(sel)
        subj = subject_names(sel)
        if not subj and '.emote' in sel:
            subj = ['*']
        for name in subj:
            for prop, value in ds:
                gaps = []
                if prop.startswith('--'):
                    continue
                if kind == 'pseudo-element' and prop in PSEUDO_READ:
                    gaps = value_gaps(prop, value)
                    if prop == 'animation':
                        gaps += animation_gaps(value)
                    if prop == 'content' and value.strip() in ('""', "''"):
                        gaps.append('content: none (a picture or blend overlay)')
                elif prop in READ_PROPS:
                    gaps = value_gaps(prop, value)
                    if prop == 'animation':
                        gaps += animation_gaps(value)
                elif prop in LAYOUT_DONE and sel.startswith('.msg-chat .emote.'):
                    continue
                elif prop in HARMLESS:
                    continue
                else:
                    gaps = [f'property: {prop}']
                if wrapper:
                    gaps = [f'{g} (inside {wrapper[:30]})' for g in gaps] + ([f'rule inside {wrapper[:30]}'] if not gaps else [])
                for g in set(gaps):
                    key = g if kind in ('own', 'context') else f'[{kind}] {g}'
                    per_emote[name][key].add(kind)
                    gap_emotes[key].add(name)
            if kind == 'pseudo-element' and name != '*' and not own_pseudo.match(sel):
                per_emote[name]['[pseudo-element] depends on its neighbours (not drawn)'].add(kind)
                gap_emotes['[pseudo-element] depends on its neighbours (not drawn)'].add(name)

report = {
    'rules': len(rules), 'keyframes': len(keyframes), 'emotes': len(names),
    'gaps': sorted(((g, len(e), sorted(e)) for g, e in gap_emotes.items()), key=lambda x: -x[1]),
    'per_emote': {n: sorted(g) for n, g in sorted(per_emote.items())},
}
json.dump(report, open(out_path, 'w', encoding='utf-8'), indent=1)
print('rules', report['rules'], 'keyframes', report['keyframes'], 'emotes with gaps (not hover)',
      sum(1 for n, g in per_emote.items() if n != '*' and any(not k.startswith('[hover]') for k in g)))
for g, n, e in report['gaps']:
    if not g.startswith('[hover]'):
        print(f'{n:4d}  {g}  {", ".join(e[:8])}{" ..." if n > 8 else ""}')
