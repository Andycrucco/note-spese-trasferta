#!/usr/bin/env python3
"""Screenshot per Google Play: installa l'app sull'emulatore, carica le spese di prova (playstore/demo),
attraversa le schermate principali e salva le immagini in out/. Gira nel workflow "Screenshot Play Store"."""
import os, re, subprocess, sys, time, xml.etree.ElementTree as ET

PKG = 'it.scontrini.trasferta'
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, 'out'); os.makedirs(OUT, exist_ok=True)
APK = sys.argv[1]

def sh(*a, check=True):
    r = subprocess.run(['adb', *a], capture_output=True, text=True)
    if check and r.returncode != 0: print('ERR', a, r.stdout, r.stderr)
    return r.stdout

def shell(cmd): return sh('shell', cmd)

def shot(name):
    time.sleep(1.5); dismiss_anr()
    with open(os.path.join(OUT, name + '.png'), 'wb') as f:
        f.write(subprocess.run(['adb', 'exec-out', 'screencap', '-p'], capture_output=True).stdout)
    print('screenshot', name)

def nodes():
    for _ in range(4):
        shell('rm -f /sdcard/ui.xml')
        out = sh('shell', 'uiautomator dump --compressed /sdcard/ui.xml', check=False)
        x = sh('shell', 'cat /sdcard/ui.xml', check=False)
        try: return list(ET.fromstring(x[x.index('<'):]).iter('node'))
        except Exception: print('dump non riuscito:', out.strip()[:200]); time.sleep(2)
    return []

def dismiss_anr():
    """chiude gli avvisi "non risponde" del launcher dell'emulatore"""
    for _ in range(3):
        ns = nodes()
        if not any('responding' in (n.get('text') or '') for n in ns): return
        for n in ns:
            if (n.get('text') or '') in ('Wait', 'Attendi'):
                x, y = center(n); shell(f'input tap {x} {y}'); time.sleep(2); break

def center(n):
    a = list(map(int, re.findall(r'\d+', n.get('bounds'))))
    return (a[0] + a[2]) // 2, (a[1] + a[3]) // 2

def find(text, desc=False):
    for n in nodes():
        v = n.get('content-desc' if desc else 'text') or ''
        if text.lower() in v.lower(): return n
    return None

def tap_text(text, desc=False, tries=3):
    for _ in range(tries):
        n = find(text, desc)
        if n is not None:
            x, y = center(n); shell(f'input tap {x} {y}'); time.sleep(2); return True
        time.sleep(1.5)
    print('non trovato:', text); return False

def scroll_to(text, max_swipes=10, top=420):
    """scorre finché il testo compare, poi lo porta vicino all'alto dello schermo"""
    for _ in range(max_swipes):
        n = find(text)
        if n is not None:
            x, y = center(n)
            if y > top + 200: shell(f'input swipe 540 {y} 540 {top} 900'); time.sleep(1.2)
            return True
        shell('input swipe 540 1900 540 700 700'); time.sleep(1)
    print('non trovato scorrendo:', text); return False

def to_top():
    for _ in range(6): shell('input swipe 540 700 540 2000 300')
    time.sleep(1)

# --- emulatore pronto ---
time.sleep(40)
shell('input keyevent KEYCODE_WAKEUP'); shell('wm dismiss-keyguard'); shell('input keyevent KEYCODE_HOME'); time.sleep(10)
dismiss_anr()

# --- installazione e dati di prova ---
sh('install', '-r', '-g', APK)
for p in ['POST_NOTIFICATIONS', 'ACCESS_FINE_LOCATION', 'ACCESS_COARSE_LOCATION', 'ACCESS_MEDIA_LOCATION']:
    sh('shell', f'pm grant {PKG} android.permission.{p}', check=False)
shell(f'am force-stop {PKG}')
sh('push', os.path.join(HERE, 'demo'), '/data/local/tmp/demo')
now = int(time.time() * 1000)
prefs = {
    'app.xml': '<map><boolean name="consent" value="true" /></map>',
    'ui.xml': '<map><boolean name="cardAsked" value="true" /><long name="lastBackup" value="%d" /></map>' % now,
    'updates.xml': '<map><long name="checked" value="%d" /><int name="latest" value="0" /></map>' % now,
}
for k, v in prefs.items():
    open('/tmp/' + k, 'w').write("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n" + v + '\n')
    sh('push', '/tmp/' + k, '/data/local/tmp/demo/' + k)
shell('chmod -R 755 /data/local/tmp/demo')
shell(f"run-as {PKG} sh -c 'mkdir -p files/photos shared_prefs && cp /data/local/tmp/demo/data.json files/ && cp /data/local/tmp/demo/foto/*.jpg files/photos/ && cp /data/local/tmp/demo/*.xml shared_prefs/'")
print(shell(f"run-as {PKG} ls -R files shared_prefs"))

# barra di stato pulita
shell('settings put global sysui_demo_allowed 1')
for c in ['enter', 'clock -e hhmm 0930', 'battery -e level 100 -e plugged false', 'network -e wifi show -e level 4', 'network -e mobile show -e datatype none -e level 4', 'notifications -e visible false']:
    parts = c.split(' ', 1)
    shell(f'am broadcast -a com.android.systemui.demo -e command {parts[0]} ' + (parts[1] if len(parts) > 1 else ''))

# --- schermate ---
shell(f'am start -n {PKG}/.MainActivity'); time.sleep(8)
dismiss_anr()
shot('01_home')
scroll_to('Da completare'); shot('02_da_completare')
scroll_to('EUR'); shot('03_totali')
scroll_to('Asador Cerro Alto'); shot('04_elenco')
scroll_to('Controllo prima'); shot('05_controllo')
to_top()
if scroll_to('Asador Cerro Alto') and tap_text('Asador Cerro Alto'):
    time.sleep(3); shot('06_spesa'); shell('input keyevent KEYCODE_BACK'); time.sleep(2)
to_top()
if tap_text('Apri il menu', desc=True): shot('07_menu'); shell('input keyevent KEYCODE_BACK'); time.sleep(1)
to_top()
if scroll_to('Sono a tavola') and tap_text('Sono a tavola'):
    time.sleep(2); shot('08_tavolo'); shell('input keyevent KEYCODE_BACK'); time.sleep(1)
to_top()
if scroll_to('Crea cartella') and tap_text('Crea cartella'):
    tap_text('Esporta lo stesso', tries=2)
    time.sleep(45); shot('09_condividi')
    shell('input keyevent KEYCODE_BACK'); time.sleep(2)
sh('pull', '/sdcard/Download/NoteSpese', os.path.join(OUT, 'export'), check=False)

# --- prove delle funzioni nuove (immagini t_*, non per lo Store) ---
def alive(): return shell(f'pidof {PKG}').strip() != ''
shell(f'am start -n {PKG}/.MainActivity -a it.scontrini.trasferta.STORICO'); time.sleep(4); shot('t_scorciatoia_storico'); shell('input keyevent KEYCODE_BACK'); time.sleep(1)
shell(f'am start -n {PKG}/.MainActivity -a it.scontrini.trasferta.DA_COMPLETARE'); time.sleep(4); shot('t_scorciatoia_todo')
if tap_text('Apri il menu', desc=True) and tap_text('Backup su file'):
    shot('t_backup_dialogo')
    if tap_text('Crea backup'):
        time.sleep(3); shot('t_backup_salva')
        for t in ['SAVE', 'Save', 'Salva', 'SALVA']:
            if find(t) is not None: tap_text(t); break
        time.sleep(1.2); shot('t_backup_fatto')
time.sleep(3)
print('backup su disco:', shell('ls -la /sdcard/Download /sdcard/Documents 2>/dev/null | grep -i backup'))
if tap_text('Apri il menu', desc=True) and tap_text('Backup su file') and tap_text('Ripristina'):
    time.sleep(3); shot('t_ripristino_scegli')
    if tap_text('NoteSpese_backup'): time.sleep(1.5); shot('t_ripristino_fatto')
shell('cmd statusbar click-tile it.scontrini.trasferta/.ReceiptTileService'); time.sleep(4); shot('t_riquadro')
print('app viva dopo le prove:', alive())
print('crash:', shell('logcat -d -b crash | tail -40'))
print('fatto')
