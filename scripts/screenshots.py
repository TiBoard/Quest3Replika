#!/usr/bin/env python3
"""Capture des écrans de Quest3Replika dans un émulateur Android (utilisé par le workflow screenshots)."""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "com.tiboard.quest3replika"
OUT = sys.argv[2] if len(sys.argv) > 2 else "docs/screenshots"
APK = sys.argv[1]


def adb(*args, check=True):
    print("+ adb", " ".join(args), flush=True)
    return subprocess.run(["adb", *args], check=check, capture_output=True, text=True).stdout


def shell(cmd):
    return adb("shell", cmd, check=False)


def dismiss_anr():
    """Ferme les fenêtres « … isn't responding » de l'émulateur (lent sans GPU) en choisissant « Wait »."""
    for _ in range(3):
        found = False
        for n in nodes():
            if n.get("text", "") in ("Wait", "Attendre") and n.get("clickable") == "true" or \
                    n.get("resource-id", "") == "android:id/aerr_wait":
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
                shell(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
                print("ANR fermée", flush=True)
                found = True
                time.sleep(1.5)
                break
        if not found:
            return


def cap(name):
    dismiss_anr()
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, name), "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], check=True, capture_output=True).stdout)
    print("capture", name, flush=True)


def nodes():
    """Arbre d'interface courant (liste vide si uiautomator échoue, ex. système pas encore au repos)."""
    for _ in range(4):
        shell("rm -f /sdcard/ui.xml")
        print(shell("uiautomator dump /sdcard/ui.xml").strip(), flush=True)
        xml = adb("exec-out", "cat", "/sdcard/ui.xml", check=False)
        start = xml.find("<?xml")
        if start >= 0:
            try:
                return list(ET.fromstring(xml[start:]).iter("node"))
            except ET.ParseError as e:
                print("XML illisible :", e, flush=True)
        time.sleep(2)
    return []


def tap(pred, label, retries=5):
    for _ in range(retries):
        for n in nodes():
            if pred(n):
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
                shell(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
                print("tap", label, flush=True)
                return True
        time.sleep(1.5)
    print("introuvable :", label, flush=True)
    return False


def tap_text(t):
    dismiss_anr()
    return tap(lambda n: n.get("text", "") == t, t)


def tap_id(rid):
    return tap(lambda n: n.get("resource-id", "") == rid, rid)


for k in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
    shell(f"settings put global {k} 0")
shell("settings put system screen_off_timeout 1800000")
shell("svc power stayon true")
shell("input keyevent KEYCODE_WAKEUP")
shell("wm dismiss-keyguard")
shell("settings put global hide_error_dialogs 1")
# Résolution de tablette 10" classique : textes lisibles et rendu logiciel plus léger.
shell("wm size 1920x1200")
shell("wm density 240")
time.sleep(20)  # laisse le système finir son démarrage (sinon uiautomator échoue)
adb("install", "-r", "-g", APK)
# Connexion automatique désactivée pour pouvoir capturer la liste des émetteurs trouvés.
with open("/tmp/quest3replika.xml", "w") as f:
    f.write("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
            "<map><boolean name=\"auto_connect\" value=\"false\" /></map>\n")
adb("push", "/tmp/quest3replika.xml", "/data/local/tmp/quest3replika.xml")
shell("chmod 644 /data/local/tmp/quest3replika.xml")
shell(f"run-as {PKG} mkdir -p shared_prefs")
shell(f"run-as {PKG} cp /data/local/tmp/quest3replika.xml shared_prefs/quest3replika.xml")
print(shell(f"run-as {PKG} cat shared_prefs/quest3replika.xml"), flush=True)

# 1. Mode Émetteur
shell(f"am start -W -n {PKG}/.MainActivity")
time.sleep(4)
tap_text("Émetteur (Meta Quest 3)")
time.sleep(1.5)
cap("01-emetteur.png")

# L'émulateur affiche un écran 2D (pas de côte à côte) : on désactive le recadrage pour la démo.
tap_id(f"{PKG}:id/cropSwitch")
time.sleep(1)

# 2. Démarrage de la diffusion (fenêtre de consentement MediaProjection)
tap_id(f"{PKG}:id/senderButton")
time.sleep(3)
cap("02-consentement.png")
if not tap_id("android:id/button1"):
    tap(lambda n: n.get("text", "").lower() in ("start now", "start", "commencer"), "bouton Start")
time.sleep(5)
cap("03-diffusion.png")

# 3. Notification du service au premier plan
shell("cmd statusbar expand-notifications")
time.sleep(2.5)
cap("04-notification.png")
shell("cmd statusbar collapse")
time.sleep(1.5)

# 4. Mode Récepteur : découverte NSD (l'émetteur tourne sur le même appareil)
tap_text("Récepteur (tablette)")
time.sleep(8)
cap("05-recepteur.png")

# 5. Connexion à l'émetteur trouvé, puis flux reçu en plein écran
tap(lambda n: n.get("text", "").startswith("Quest3Replika "), "émetteur trouvé")
time.sleep(10)
shell("input tap 960 600")  # masque les statistiques
time.sleep(3)
cap("06-flux.png")
shell("input tap 960 600")  # réaffiche les statistiques
time.sleep(4)
cap("07-flux-stats.png")
print(shell(f"logcat -d -s StreamReceiver VideoDecoder StreamSender VideoEncoder ScreenCapturer CaptureService | tail -40"))
