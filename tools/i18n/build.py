import sys, re, os
sys.path.insert(0, os.path.dirname(__file__))
import en
LANGS = {"tr":"values-tr","es":"values-es","fr":"values-fr","it":"values-it","de":"values-de",
         "pt":"values-pt","zh":"values-zh-rCN","ja":"values-ja","ko":"values-ko","ru":"values-ru","ar":"values-ar"}
res = sys.argv[1]
PH = re.compile(r"%(\d+)\$([-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z])")
def esc(v):
    v = v.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
    v = v.replace("\\n","\n").replace("\n","\\n")
    v = v.replace("'","\\'").replace('"','\\"')
    if v.startswith("@") or v.startswith("?"): v = "\\" + v
    return v
def write(folder, d, base=None):
    os.makedirs(os.path.join(res, folder), exist_ok=True)
    lines = ['<?xml version="1.0" encoding="utf-8"?>', '<!-- Bu dosya çeviri betiğiyle üretilir. -->', '<resources>']
    for k in en.S:
        if k not in d: continue
        attr = ' translatable="false"' if k == "app_name" else ""
        lines.append(f'    <string name="{k}"{attr}>{esc(d[k])}</string>')
    lines.append('</resources>')
    open(os.path.join(res, folder, "strings.xml"), "w", encoding="utf-8").write("\n".join(lines) + "\n")
errors = []
write("values", en.S)
for code, folder in LANGS.items():
    m = __import__(code)
    missing = set(en.S) - set(m.S) - {"app_name"}
    if missing: errors.append(f"{code}: missing {missing}")
    for k, v in m.S.items():
        if sorted(PH.findall(v)) != sorted(PH.findall(en.S[k])):
            errors.append(f"{code}.{k}: placeholders {PH.findall(v)} != {PH.findall(en.S[k])}")
    write(folder, m.S)
if errors:
    print("\n".join(errors)); sys.exit(1)
print("ok", len(en.S), "strings,", len(LANGS)+1, "languages")
