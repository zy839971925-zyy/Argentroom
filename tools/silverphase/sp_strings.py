#!/usr/bin/env python3
"""Moves the Silverphase phone-editor literals into string resources (one-shot helper).
English comes from the iOS T(en, zh-Hans, ...) pairs where one exists (Silverphase v1.2.5)."""
import re, json, sys, os
pairs = json.load(open('/tmp/tpairs.json'))
EXTRA = {  # literals with no iOS T() pair: our English
 '宽容度测量图需要 Silverphase 的场景测光探针，Android 引擎暂未提供。': 'The latitude graph needs Silverphase\'s scene metering probe, which the Android engine does not have yet.',
 '“高 / 中 / 低”抗光晕预设需要 Silverphase 的 antihalation_mode，Android 引擎暂缺，暂只提供跟随胶片与关闭。': 'The High / Medium / Low anti-halation presets need Silverphase\'s antihalation_mode, which the Android engine lacks; only Follow film and Off are offered.',
 'Silverphase 的负片片边（齿孔、片门、帧号、边缘雾化、漏光）在负片上物理成像，Android 引擎暂无该阶段。下面是 Android 版现有的导出片框，作为最接近的替代。': 'Silverphase develops the film edge (perforations, gate, frame numbers, edge fog, light leaks) on the negative; the Android engine has no such stage. Below is the Android export frame, the closest available substitute.',
 '未填文字时不打印。日期样式、片边位置和毫米边距依赖 Silverphase 的片边模型，Android 引擎暂缺。': 'Nothing is printed while the text is empty. Date style, edge placement and millimetre margins depend on Silverphase\'s film-edge model, which the Android engine lacks.',
}
def slug(en):
    s = re.sub(r'[^a-z0-9]+', '_', en.lower()).strip('_')
    return ('sp_' + s)[:44].rstrip('_')
def esc(t):
    t = t.replace('&', '&amp;').replace('<', '&lt;').replace("'", "\\'").replace('"', '\\"')
    return t
path = sys.argv[1]
src = open(path).read()
out_lines = []; table = {}
used = set(json.load(open('/tmp/spkeys.json'))) if os.path.exists('/tmp/spkeys.json') else set()
keys = {}
def key_for(zh):
    if zh in keys: return keys[zh]
    en = pairs.get(zh) or EXTRA.get(zh)
    if en is None: raise SystemExit('no english for ' + zh)
    k = slug(en); base = k; i = 2
    while k in used and table.get(k, (None,))[0] != zh: k = f'{base}_{i}'; i += 1
    used.add(k); keys[zh] = k; table[k] = (zh, en); return k
for line in src.split('\n'):
    t = line.strip()
    if t.startswith(('*', '//', '/*')): out_lines.append(line); continue
    def rep(m):
        lit = m.group(1)
        if not re.search(r'[\u4e00-\u9fff]', lit) or '$' in lit: return m.group(0)
        return f'spS(R.string.{key_for(lit)})'
    out_lines.append(re.sub(r'"((?:[^"\\\n]|\\.)*)"', rep, line))
open(path, 'w').write('\n'.join(out_lines))
json.dump(sorted(used), open('/tmp/spkeys.json', 'w'))
old = json.load(open('/tmp/sptable.json')) if os.path.exists('/tmp/sptable.json') else {}
old.update({k: list(v) for k, v in table.items()})
json.dump(old, open('/tmp/sptable.json', 'w'), ensure_ascii=False, indent=0)
print(len(table), 'strings')
