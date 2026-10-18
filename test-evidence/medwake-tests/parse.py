#!/usr/bin/env python3
import sys, re, xml.etree.ElementTree as ET
# parse a uiautomator dump and print nodes with text/resource-id/class and bounds
path = sys.argv[1] if len(sys.argv)>1 else "/tmp/opencode/medwake-tests/_a5dump.xml"
tree = ET.parse(path)
root = tree.getroot()
def center(bounds):
    m = re.search(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', bounds)
    if not m: return None
    x1,y1,x2,y2 = map(int, m.groups())
    return ((x1+x2)//2, (y1+y2)//2, x1,y1,x2,y2)
# optional filter by text substring
filt = sys.argv[2] if len(sys.argv)>2 else None
for node in root.iter('node'):
    t = node.get('text','')
    rid = node.get('resource-id','')
    cls = node.get('class','')
    b = node.get('bounds','')
    c = center(b)
    if filt and filt not in t and filt not in rid:
        continue
    if c:
        print(f"{t!r:40} rid={rid!r:45} cls={cls.split('.')[-1]:20} center=({c[0]},{c[1]}) box=({c[3]},{c[4]},{c[1]},{c[2]})")
