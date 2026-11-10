# NemApi APK — rapport v1.0.2

Date : 10 novembre 2026. Build effectuée dans le codespace existant `verbose-succotash-x56v9649pvwr3p65g`, dépôt `nemesishighschool-cloud/NemApi-Apk`.

## Correctifs livrés

### 1. Accès depuis le réseau local

- Écoute IPv4 confirmée sur `0.0.0.0:8090` ; tentative IPv6, si la pile Android accepte le bind.
- Les interfaces Wi-Fi (`wlan*`) sont privilégiées pour l'URL à partager : avant, l'ordre non déterministe pouvait choisir une IP mobile/virtuelle, affichée à tort comme IP réseau locale.
- Ajout du diagnostic `GET /api/net` : interfaces, rôles, adresses, URL à essayer, aide sur l'isolation des clients Wi-Fi.
- Verrou Wi-Fi conservé par le service de premier plan pour rester disponible écran éteint.
- Permissions réseau Wi-Fi déclarées.

### 2. Dashboard NemApi Web

- Remplacement du tableau noir fabriqué par les pages, CSS, JS et icônes originaux `proxy/web` de NemApi Web.
- Vérification SHA/source : `admin.html`, `admin.js`, `admin.css`, `config.html`, `integrations.html`, `chat.html`, `analytics.html`, `api-keys.html` sont identiques octet par octet aux originaux copiés.
- La racine `/` sert le véritable dashboard ; pages `/config`, `/integrations`, `/chat`, `/analytics`, `/api-keys-page`, CSS/JS, logos et endpoints JSON répondent.
- Endpoints dashboard mappés à l'état Android : statistiques persistantes et paramètres, catalogue DeepSeek, intégrations agents, clés Bearer (off par défaut), journaux d'état, status.
- Aucun contenu de conversation servi par le dashboard ou copié dans les journaux.

### 3. Temps d'envoi d'image

- Cause trouvée : l'app attendait une vignette/état DOM qui n'était pas exposé par l'interface DeepSeek actuelle. L'attente expirait donc systématiquement à 45 s même lorsque le fichier était déjà téléversé — ce qui explique les ~46 s fixes observées.
- Correction : le hook observe les appels réseau de téléversement `fetch` et XHR, leur réponse HTTP et leur fin. L'attente s'arrête au signal effectif ; si aucune requête n'est faite (pièce jointe locale), elle s'arrête après 3 s. 45 s ne reste qu'un délai de sécurité.
- Vérification : upload fini en **1,4 s**, HTTP 200 ; test image complet (4 quadrants) : réponse exacte en **5,15 s** au total, au lieu de 46,33 s de TTFT précédemment.

## Validation effectuée

- Build APK : **BUILD SUCCESSFUL**, 2 450 273 o.
- `dashboard_test.py` : **tous les contrôles passés** — 7 routes de pages, 4 assets, health/status/providers/stats/settings, état extension, 3 intégrations, clés API, écritures de settings/intégrations/clé, absence de texte de conversation.
- `agent_compat.py` : **5/5** — modèles, SSE+system, tools, contexte long 4589 c., `/v1/responses`.
- `image_test.py` après build final : stream et non-stream OK ; les quatre couleurs ont été reconnues exactement ; upload HTTP 200 en 1,4 s.
- Test de bind non-loopback dans l'émulateur : requête `GET /health` sur `10.0.2.16:8090` → HTTP 200, réponse `version 1.0.2`.
- Dashboard visualisé sur Chrome Android : thème sombre et cartes/compteurs d'origine NemApi.
- Release APK installée dans l'émulateur avec `adb install -r` ; aucun wipe des données AVD.

## Important pour l'accès réseau réel

Le test non-loopback ci-dessus vérifie que le serveur accepte une IP d'interface, mais l'émulateur utilise un réseau NAT virtuel `10.0.2.x` : cette adresse n'est **pas** routable depuis un appareil physique. Pour l'essai sur téléphone réel, ouvrir `http://127.0.0.1:8090/api/net` sur le téléphone, prendre l'URL Wi-Fi affichée dans Pilotage, puis essayer `http://<IP-DU-TÉLÉPHONE>:8090` depuis un ordinateur sur le même Wi-Fi. L'isolation Wi-Fi invité/client, un pare-feu ou un autre sous-réseau peuvent bloquer les clients même si l'app écoute correctement.

## Limite de périmètre à distinguer

L'habillage, les pages et les assets du dashboard sont les fichiers originaux. L'API fournit les données et routes adaptées au runtime Android. NemApi Android reste un pont WebView DeepSeek et n'héberge pas le proxy FastAPI ni l'extension Web du dépôt : les options qui nécessitent des onglets d'extension externes ou le moteur de contexte incrémental du proxy ne peuvent donc pas être supposées équivalentes à leur effet dans NemApi Web. Le README Android le précise plutôt que de prétendre qu'elles reconfigurent le proxy distant.

## Release

- Version : `1.0.2`, versionCode 3
- Commit : `1c7538f` (dashboard, réseau local, délai images)
- APK : `NemApi-1.0.2.apk`, 2 450 273 o
- MD5 : `fd4c298a6b52adf7d1a816a96f7163dd`
