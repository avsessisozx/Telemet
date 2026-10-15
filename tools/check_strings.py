"""Checks that every translation has all strings and the same placeholders as English."""
import glob, re, sys, xml.etree.ElementTree as ET

PH = re.compile(r"%(\d+)\$([-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z])")
def load(path):
    return {e.get("name"): (e.text or "") for e in ET.parse(path).getroot().findall("string")}

base = load("app/src/main/res/values/strings.xml")
errors = []
for path in sorted(glob.glob("app/src/main/res/values-*/strings.xml")):
    tr = load(path)
    for k, v in base.items():
        if k == "app_name":
            continue
        if k not in tr:
            errors.append(f"{path}: missing {k}")
        elif sorted(PH.findall(tr[k])) != sorted(PH.findall(v)):
            errors.append(f"{path}: {k} placeholders differ")
    for k in tr:
        if k not in base:
            errors.append(f"{path}: unknown {k}")
print(f"{len(base)} strings, {len(glob.glob('app/src/main/res/values-*/strings.xml'))} translations")
if errors:
    print("\n".join(errors))
    sys.exit(1)
print("All translations complete")
