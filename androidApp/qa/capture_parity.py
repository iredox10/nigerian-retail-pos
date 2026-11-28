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
def hide_keyboard():
    own_foreground()
    state=call('shell','dumpsys','input_method')
    current=re.search(r'\bmInputShown\s*=\s*(true|false)\b',state)
    if current and current.group(1)=='true':
        call('shell','input','keyevent','KEYCODE_BACK')

def tap_text(text, scroll=False):
    for attempt in range(8 if scroll else 4):
        for node in nodes():
            if node.attrib.get('text')==text or node.attrib.get('content-desc')==text:
                values=list(map(int,re.findall(r'\d+',node.attrib['bounds'])))
                if node.attrib.get('enabled')=='true' and len(values)==4 and values[3]>values[1] and values[1]<1250:
                    tap(node);time.sleep(.5);return
        if scroll:call('shell','input','swipe','360','1000','360','350','400')
        time.sleep(.5)
    raise AssertionError('Could not find visible QA action: '+text)
def screenshot(name, expected):
    assert any(n.attrib.get('text')==expected or n.attrib.get('content-desc')==expected for n in nodes()), 'Expected screen not shown: '+expected
    own_foreground()
    image=subprocess.run(adb+['exec-out','screencap','-p'],capture_output=True,check=True,timeout=20).stdout
    own_foreground()
    (out/(name+'.png')).write_bytes(image)

def assert_selected(label):
    tree=nodes()
    def matches(node):
        return node.attrib.get('text')==label or node.attrib.get('content-desc')==label
    assert any((node.attrib.get('selected')=='true' or node.attrib.get('checked')=='true') and any(matches(child) for child in node.iter('node')) for node in tree), 'Saved choice was not restored: '+label

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
    try:
        (out/'failure-runtime.log').write_text(call('shell','logcat','-d','-s','AndroidRuntime:E'))
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
hide_keyboard()
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
    ('Reorder suggestions','Reorder suggestions','reorder'),
    ('Shop & receipt details','Shop & receipt details','shop-receipt-settings'),
]:
    tap_text(action,scroll=True)
    screenshot(filename,title)
    if action=='Shop & receipt details':
        tap_text('Receipt design')
        tap_text('Double line',scroll=True)
        tap_text('Centre',scroll=True)
        screenshot('receipt-design-controls','Heading alignment')
        tap_text('Rendered receipt design preview, page 1',scroll=True)
        screenshot('receipt-design-pdf-preview','Rendered receipt design preview, page 1')
        tap_text('Review receipt details',scroll=True)
        tap_text('Save receipt details',scroll=True)
        time.sleep(1)
        for attempt in range(8):
            own_foreground();call('shell','input','swipe','360','350','360','1000','300')
        screenshot('receipt-design-saved','Receipt details and design saved for new sales.')
        call('shell','am','force-stop',package)
        call('shell','am','start','--user','0','-W','-n',package+'/pos.android.MainActivity')
        time.sleep(2)
        unlock=[n for n in nodes() if n.attrib.get('class')=='android.widget.EditText']
        assert len(unlock)==1, 'Expected one owner password field after restart'
        tap(unlock[0]);call('shell','input','text','CI-only-owner-2026')
        hide_keyboard()
        tap_text('Open shop',scroll=True)
        time.sleep(1)
        tap_text('More')
        tap_text('Shop & receipt details',scroll=True)
        tap_text('Receipt design')
        for attempt in range(8):
            if any(n.attrib.get('text')=='Centre' for n in nodes()): break
            call('shell','input','swipe','360','1000','360','350','400')
        assert_selected('Centre')
        for attempt in range(8):
            if any(n.attrib.get('text')=='Double line' for n in nodes()): break
            call('shell','input','swipe','360','350','360','1000','400')
        assert_selected('Double line')
        screenshot('receipt-design-reopened','Double line')

    tap_text('Back to shop tools')
(out/'review.json').write_text(json.dumps({'device':serial,'qaPackage':package,'sampleShopOnly':True,'offlineScreensCaptured':12,'receiptDesignPersistedAfterRestart':True,'receiptDesignPdfPreviewCaptured':True,'fullMerchantUsabilityResearch':False,'csvPickerImportJourneyVerified':False},indent=2)+'\n')
print('Captured twelve real QA screens offline; merchant research and full picker journeys remain separate.')
