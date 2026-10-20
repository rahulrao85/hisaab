#!/usr/bin/env bash
# Print every on-screen text/checkable element with its centre point: tools/ui.sh [filter]
cd "$(dirname "$0")/.."
tools/phone.sh dump > post/raw/ui.xml
python - "$1" <<'PY'
import re,sys
f=sys.argv[1] if len(sys.argv)>1 else ''
x=open('post/raw/ui.xml',encoding='utf-8').read()
for m in re.finditer(r'<node [^>]*>',x):
    n=m.group(0); t=re.search(r'text="([^"]*)"',n).group(1); chk='checkable="true"' in n
    a=list(map(int,re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',n).groups()))
    if (t or chk) and f.lower() in (t.lower()+(' switch' if chk else '')):
        print(f"{t[:40]!r} {(a[0]+a[2])//2} {(a[1]+a[3])//2}{' SWITCH checked='+re.search(r'checked=\"(\w+)\"',n).group(1) if chk else ''}")
PY
