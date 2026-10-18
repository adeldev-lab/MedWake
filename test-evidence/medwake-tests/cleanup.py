import subprocess, re, time, os

SERIAL="R5CW21ZV0MB"
UI="/sdcard/ui.xml"
LOCAL="/tmp/opencode/medwake-tests/ui_del.xml"

def adb(cmd):
    return subprocess.run(["adb","-s",SERIAL]+cmd, capture_output=True, text=True).stdout

def dump():
    adb(["shell","uiautomator","dump",UI])
    time.sleep(0.6)
    return adb(["shell","cat",UI])

def parse(data):
    nodes=[]
    for m in re.finditer(r'<node ([^>]*)/?>', data):
        a=m.group(1)
        rid=re.search(r'resource-id="([^"]*)"',a); txt=re.search(r'text="([^"]*)"',a); bnd=re.search(r'bounds="([^"]*)"',a)
        rid=rid.group(1) if rid else ''; txt=txt.group(1) if txt else ''; bnd=bnd.group(1) if bnd else ''
        nodes.append((rid,txt,bnd))
    return nodes

def center(bnd):
    nums=[int(x) for x in re.findall(r'\d+', bnd)]
    return ((nums[0]+nums[2])//2,(nums[1]+nums[3])//2)

for i in range(12):
    data=dump()
    nodes=parse(data)
    # find med delete buttons
    del_btns=[(rid,txt,bnd) for (rid,txt,bnd) in nodes if 'btnDeleteMed' in rid]
    if not del_btns:
        print(f"iter {i}: no btnDeleteMed found -> done")
        break
    # tap the first (topmost) med delete
    rid,txt,bnd=del_btns[0]
    cx,cy=center(bnd)
    print(f"iter {i}: tapping med delete at ({cx},{cy})")
    adb(["shell","input","tap",str(cx),str(cy)])
    time.sleep(1.2)
    # find confirm dialog positive button (text Delete, NOT btnDeleteMed)
    data2=dump()
    nodes2=parse(data2)
    confirm=None
    for (rid,txt,bnd) in nodes2:
        if txt.strip()=="Delete" and 'btnDeleteMed' not in rid:
            confirm=(rid,txt,bnd); break
    if not confirm:
        # fallback: android:id/button1
        for (rid,txt,bnd) in nodes2:
            if rid.endswith("button1"):
                confirm=(rid,txt,bnd); break
    if not confirm:
        print(f"iter {i}: NO confirm dialog 'Delete' button found! Dumping buttons:")
        for (rid,txt,bnd) in nodes2:
            if txt.strip() or 'button' in rid: print("   ",rid,txt,bnd)
        break
    cx2,cy2=center(confirm[2])
    print(f"iter {i}: tapping confirm Delete at ({cx2},{cy2})")
    adb(["shell","input","tap",str(cx2),str(cy2)])
    time.sleep(1.8)

print("=== cleanup loop finished ===")
