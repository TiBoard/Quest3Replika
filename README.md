# Quest3Replika

Application Android native (Kotlin) qui diffuse l'écran d'un **Meta Quest 3** vers une **tablette Android** en temps réel, sur le réseau local, sans Internet ni serveur.

Un seul APK universel : au lancement, on choisit le mode **Émetteur** (sur le Quest 3) ou **Récepteur** (sur la tablette).

## Fonctionnement

```
Quest 3 (Émetteur)                                                     Tablette (Récepteur)
MediaProjection → VirtualDisplay → GL (recadrage œil gauche)          TCP → MediaCodec (décodeur
→ MediaCodec H.264 (CBR, sans images B) → socket TCP (TCP_NODELAY)  ──▶  faible latence) → SurfaceView
                         annonce mDNS _questmirror._tcp  ◀── découverte NSD automatique
```

- **Vidéo** : H.264 matériel, 720p max à 30 i/s, débit réglable de 2 à 10 Mbit/s (5 par défaut), image clé chaque seconde et à chaque connexion. Pas d'audio.
- **Latence** : aucun tampon de lecture. Si le Wi-Fi sature, l'émetteur jette le retard (plus de ~200 ms en file) et repart sur une image clé plutôt que d'accumuler du décalage.
- **Découverte** : NSD/mDNS (`_questmirror._tcp`, port 47800). Saisie d'IP manuelle possible en secours.
- **Tâche de fond sur le Quest 3** : service au premier plan de type `mediaProjection` avec notification. La capture continue quand vous lancez un jeu ou une application VR. WakeLock, WifiLock basse latence et MulticastLock sont tenus pendant la diffusion.
- **Recadrage œil gauche** : supprime le double affichage stéréo côte à côte. Zoom et décalages horizontal/vertical réglables **en direct** pendant la diffusion.
- **Reprise** : le récepteur se reconnecte tout seul (toutes les secondes) et suit l'émetteur par son nom même si son IP change.
- **Aucune dépendance externe** : uniquement le SDK Android et la bibliothèque standard Kotlin.

## Récupérer l'APK

### Via GitHub (recommandé)

Chaque push est compilé par GitHub Actions (workflow `.github/workflows/build-apk.yml`) :

- **Branche `main`** : l'APK est publié dans la release **« latest »** (onglet *Releases* du dépôt).
- **Tag `v*`** (ex. `v1.0.0`) : une release versionnée avec l'APK est créée.
- **Pull request** : une préversion **« pr-N »** avec l'APK est publiée dans *Releases* (supprimée à la fermeture de la PR).
- **Tous les runs** : l'APK est aussi joint en artefact `Quest3Replika-apk` (onglet *Actions* → le run → *Artifacts*), si le quota de stockage d'artefacts du compte le permet.

### Compiler soi-même

Prérequis : Android Studio (Ladybug ou plus récent) ou JDK 17 + SDK Android (plateforme 35).

```bash
git clone https://github.com/TiBoard/Quest3Replika.git
cd Quest3Replika
./gradlew assembleRelease        # Windows : gradlew.bat assembleRelease
# APK : app/build/outputs/apk/release/app-release.apk
```

Ou dans Android Studio : *File → Open*, puis *Build → Build App Bundle(s) / APK(s) → Build APK(s)*.

### Signature

Le dépôt contient un keystore de debug (`keystore/debug.keystore`, mot de passe `android`) : toutes les compilations ont la même signature, donc `adb install -r` met à jour l'application sans la désinstaller. Pour une clé privée, ajoutez dans *Settings → Secrets and variables → Actions* les secrets `QR_KEYSTORE_BASE64` (keystore encodé en base64), `QR_KEYSTORE_PASSWORD`, `QR_KEY_ALIAS` et `QR_KEY_PASSWORD` : le workflow les utilise automatiquement (il faudra alors désinstaller une fois l'ancienne version).

## Installation

### Sur le Meta Quest 3 (mode développeur)

1. **Activer le mode développeur** : créez (ou utilisez) une organisation sur <https://developers.meta.com>, puis dans l'application mobile **Meta Horizon** : *Appareils → votre Quest 3 → Paramètres du casque → Mode développeur* → activer.
2. Redémarrez le casque et branchez-le au PC en USB-C.
3. Dans le casque, acceptez **« Autoriser le débogage USB »** (cochez *Toujours autoriser depuis cet ordinateur*).
4. Installez les [platform-tools Android](https://developer.android.com/tools/releases/platform-tools) (adb), puis :

```bash
adb devices                                   # le Quest doit apparaître comme "device"
adb install -r Quest3Replika-1.0.0-xxxxxxx.apk
```

5. Dans le casque : *Bibliothèque → Applications → filtre « Sources inconnues »* → **Quest3Replika**.

Astuce : pour installer sans câble ensuite, `adb tcpip 5555` puis `adb connect <IP-du-Quest>:5555`.

### Sur la tablette Android

Soit `adb install -r Quest3Replika-….apk` (débogage USB activé dans *Options pour les développeurs*), soit copiez l'APK sur la tablette et ouvrez-le (autorisez l'installation depuis des sources inconnues).

## Utilisation

1. Mettez le Quest 3 et la tablette sur le **même réseau Wi-Fi** (de préférence en 5 GHz ou 6 GHz) ou sur le point d'accès mobile de l'un des deux.
2. **Tablette** : lancez Quest3Replika, choisissez **Récepteur**. Avec « Se connecter automatiquement » coché, elle ouvre le flux dès qu'un émetteur est trouvé ; sinon touchez l'émetteur dans la liste. Touchez la vidéo pour afficher/masquer les statistiques (résolution, i/s, débit).
3. **Quest 3** : lancez Quest3Replika, choisissez **Émetteur**, réglez le recadrage, puis **Démarrer la diffusion** et acceptez la fenêtre de capture d'écran. Vous pouvez ensuite quitter l'application et lancer un jeu : la diffusion continue (notification « Quest3Replika diffuse l'écran », bouton *Arrêter*).
4. Pour ajuster le cadrage pendant la diffusion, rouvrez l'application sur le Quest : les curseurs Zoom/Décalage s'appliquent immédiatement. Activer ou désactiver le recadrage redémarre seulement l'encodeur (coupure d'une fraction de seconde).

Les choix (mode, recadrage, débit, dernier émetteur) sont mémorisés.

## Permissions

| Permission | Pourquoi |
|---|---|
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Service de capture qui tourne en arrière-plan (obligatoire depuis Android 14) |
| `POST_NOTIFICATIONS` (demandée au lancement, Android 13+) | Notification du service de capture |
| `NEARBY_WIFI_DEVICES` (`neverForLocation`, Android 13+) | Wi-Fi local, sans accès à la localisation |
| `CHANGE_WIFI_MULTICAST_STATE` | Réception des annonces mDNS |
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | Sockets TCP en réseau local (aucun accès Internet utilisé) |
| `WAKE_LOCK` | Empêcher la mise en veille du CPU / Wi-Fi pendant la diffusion |

La capture d'écran elle-même passe par la fenêtre de consentement système MediaProjection, à accepter à chaque démarrage de diffusion (exigence d'Android 14).

## Structure du projet

```
app/src/main/java/com/tiboard/quest3replika/
├── MainActivity.kt              Choix du mode, réglages, lancement émetteur / liste des émetteurs
├── common/
│   ├── Protocol.kt              Protocole binaire (poignée de main, CONFIG, FRAME, demande d'image clé)
│   ├── Permissions.kt           Permissions Android 13+ et adresses IP locales
│   └── Prefs.kt                 Réglages persistants et calcul de la zone de recadrage
├── discovery/
│   ├── NsdAdvertiser.kt         Annonce mDNS de l'émetteur
│   └── NsdScanner.kt            Découverte et résolution des émetteurs
├── sender/
│   ├── CaptureService.kt        Service au premier plan mediaProjection, verrous, notification
│   ├── ScreenCapturer.kt        VirtualDisplay + reconstruction du pipeline à chaud
│   ├── GlCropRenderer.kt        Recadrage/redimensionnement GPU (OpenGL ES 2, texture externe)
│   ├── VideoEncoder.kt          Encodeur H.264 matériel faible latence
│   └── StreamSender.kt          Serveur TCP, file d'envoi avec abandon du retard
└── receiver/
    ├── ReceiverActivity.kt      Affichage plein écran, ratio conservé, statistiques
    ├── StreamReceiver.kt        Client TCP avec reconnexion automatique
    └── VideoDecoder.kt          Décodeur H.264 matériel en mode faible latence
```

## Conseils de latence et dépannage

- Le Wi-Fi est le principal facteur : routeur proche, bande 5/6 GHz, peu d'autres appareils actifs. Le point d'accès d'un téléphone fonctionne aussi.
- Image saccadée ou en retard : baissez le débit à 3-4 Mbit/s.
- L'émetteur n'apparaît pas : certains routeurs bloquent le multicast (isolation client / « AP isolation »). Utilisez la saisie d'IP manuelle (l'IP et le port s'affichent sur le Quest pendant la diffusion).
- Image noire au début : le récepteur attend la première image clé (moins d'une seconde).
- Le système peut arrêter la capture (par exemple à la mise en veille du casque) : relancez la diffusion depuis l'application.
- Journaux : `adb logcat -s CaptureService StreamSender VideoEncoder ScreenCapturer GlCropRenderer StreamReceiver VideoDecoder`.

## Limites connues

- Pas d'audio (choix de conception pour cette version).
- Un seul récepteur à la fois : une nouvelle connexion remplace la précédente.
- Le contenu capturé dépend de ce que Horizon OS expose à MediaProjection ; si le rendu n'est pas en côte à côte, désactivez simplement le recadrage.
