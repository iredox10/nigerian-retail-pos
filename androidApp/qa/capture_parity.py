#!/usr/bin/env python3
"""Drive only an isolated QA app on an explicitly selected emulator. No real user phone/data."""
import json
import pathlib
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

serial, destination = sys.argv[1:]
assert serial.startswith('emulator-'), 'This visual fixture must never target a physical merchant phone'
out = pathlib.Path(destination)
out.mkdir(parents=True, exist_ok=True)
adb = ['adb', '-s', serial]
package = 'ng.retailpos.android.acceptance'
def call(*args):
    return subprocess.run(adb+list(args),capture_output=True,check=True,timeout=35).stdout.decode().strip()
def own_foreground():
    state=call('shell','dumpsys','activity','activities')
    resumed=[line for line in state.splitlines() if 'ResumedActivity' in line]
    assert any(package+'/pos.android.MainActivity' in line for line in resumed), 'QA app is not foreground; no screenshot or input allowed'
def nodes():
    own_foreground()
    call('shell','uiautomator','dump','/sdcard/retailpos-parity-ui.xml')
    return list(ET.fromstring(call('shell','cat','/sdcard/retailpos-parity-ui.xml')).iter('node'))
def tap(node):
    values=list(map(int,re.findall(r'\d+',node.attrib['bounds'])))
    x1,y1,x2,y2=values
    assert x2>x1 and y2>y1
    own_foreground();call('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
def tap_text(text, scroll=False):
    for attempt in range(8 if scroll else 4):
        for node in nodes():
            if node.attrib.get('text')==text or node.attrib.get('content-desc')==text:
                values=list(map(int,re.findall(r'\d+',node.attrib['bounds'])))
                if len(values)==4 and values[3]>values[1] and values[1]<1250:
                    tap(node);time.sleep(.5);return
        if scroll:call('shell','input','swipe','360','1000','360','350','400')
        time.sleep(.5)
    raise AssertionError('Could not find visible QA action: '+text)
def screenshot(name, expected):
    assert any(n.attrib.get('text')==expected for n in nodes()), 'Expected screen not shown: '+expected
    own_foreground()
    image=subprocess.run(adb+['exec-out','screencap','-p'],capture_output=True,check=True,timeout=20).stdout
    own_foreground()
    (out/(name+'.png')).write_bytes(image)

def retain_failure(kind, error, traceback):
    # Only retain our disposable fixture; never capture another foreground app.
    try:
        own_foreground()
        call('shell', 'uiautomator', 'dump', '/sdcard/retailpos-parity-ui.xml')
        (out/'failure-ui.xml').write_text(call('shell', 'cat', '/sdcard/retailpos-parity-ui.xml'))
        own_foreground()
        result=subprocess.run(adb+['exec-out','screencap','-p'],capture_output=True,check=True,timeout=20)
        own_foreground()
        (out/'failure-screen.png').write_bytes(result.stdout)
    except Exception:
        pass
    sys.__excepthook__(kind, error, traceback)
sys.excepthook=retain_failure

call('shell','am','start','--user','0','-W','-n',package+'/pos.android.MainActivity')
time.sleep(2)
fields=[n for n in nodes() if n.attrib.get('class')=='android.widget.EditText']
assert len(fields)==2, 'Expected a new isolated installation with two onboarding fields'
tap(fields[0]);call('shell','input','text','CIParityShop')
fields=[n for n in nodes() if n.attrib.get('class')=='android.widget.EditText']
tap(fields[1]);call('shell','input','text','CI-only-owner-2026')
tap_text('Create shop',scroll=True)
time.sleep(2)
# The emulator belongs to this test job; disabling its connectivity proves local screens remain usable.
call('shell','svc','wifi','disable')
call('shell','svc','data','disable')
tap_text('More')
for action, title, filename in [
    ('CSV import','Import CSV items','csv-import'),
    ('Suppliers','Suppliers','suppliers'),
    ('Purchase orders','Purchase orders','purchase-orders'),
    ('Stock counts','Stock counts','stock-counts'),
    ('Supplier credit notes','Supplier credit notes','supplier-credits'),
    ('Reports','Reports','reports'),
]:
    tap_text(action,scroll=True)
    screenshot(filename,title)
    tap_text('Back to shop tools')
(out/'review.json').write_text(json.dumps({'device':serial,'qaPackage':package,'sampleShopOnly':True,'offlineScreensCaptured':6,'fullMerchantUsabilityResearch':False,'csvPickerImportJourneyVerified':False},indent=2)+'\n')
print('Captured six real QA screens offline; merchant research and full picker journeys remain separate.')
