# NemApi Android — 1.0.2

Version Android distincte de NemApi Web. Elle embarque le service OpenAI-compatible local, le navigateur DeepSeek et **les pages web originales de NemApi** (`proxy/web`), servies par l'application au port 8090.

## Démarrage et URLs

1. Ouvrir NemApi, démarrer le service et vérifier que DeepSeek est connecté.
2. Sur le téléphone : `http://127.0.0.1:8090` (tableau de bord) ou `http://127.0.0.1:8090/v1` (base API).
3. Depuis un autre appareil connecté au **même réseau Wi-Fi**, utiliser l'IP affichée dans **Pilotage → URL réseau** : `http://<IP-DU-TÉLÉPHONE>:8090`. La base API est alors `http://<IP-DU-TÉLÉPHONE>:8090/v1`.
4. Diagnostic des interfaces et de l'IP sélectionnée : `http://<IP-DU-TÉLÉPHONE>:8090/api/net`.

Le serveur écoute sur `0.0.0.0:8090` (et essaie aussi IPv6 si l'OS le permet). Les interfaces Wi-Fi sont prioritaires par rapport à l'Ethernet et aux interfaces virtuelles. Le service conserve un verrou Wi-Fi pour rester joignable lorsque l'écran s'éteint.

**Important :** les deux appareils doivent être sur le même sous-réseau. Un Wi-Fi invité ou un routeur configuré avec l'isolation des clients bloque volontairement les connexions entre appareils ; aucun réglage de l'app ne peut lever cette isolation. Le pare-feu du téléphone, un VPN et les données mobiles peuvent également changer l'IP ou bloquer l'accès. Vérifier d'abord `/api/net` sur le téléphone, puis ouvrir la même URL depuis le second appareil.

## Dashboard web d'origine

Les fichiers `app/src/main/assets/web/{admin.html,admin.css,admin.js,...}` sont copiés sans modification depuis `proxy/web` de NemApi Web. L'app sert ces pages et fournit leurs routes JSON usuelles (`/stats`, `/providers`, `/settings`, `/agent-integrations`, `/api-keys`, etc.) avec l'état du service Android. L'API conversationnelle, le statut, le journal et les compteurs de l'app restent natifs ; aucun contenu de conversation n'est copié dans le dashboard ou ses logs.

Le catalogue ne propose qu'un fournisseur : DeepSeek. Les cartes des intégrations d'agents affichent la version des fiches NemApi ; certaines fonctions propres au proxy Web (ex. gestion d'onglets d'extension externes ou contexte incrémental) ne sont pas transposables telles quelles à l'architecture WebView Android. Ne pas activer une option en pensant qu'elle reconfigure le proxy Web distant : elle s'applique uniquement à l'instance Android.

## Envoi d'images

Le délai fixe venait d'un défaut de mesure : l'app attendait une vignette DOM que le site DeepSeek n'exposait pas toujours aux sélecteurs utilisés, puis allait systématiquement au plafond de 45 secondes. L'image avait déjà été envoyée par la page, mais l'app ne savait pas que le transfert était terminé.

Depuis 1.0.2, `hook.js` mesure les requêtes réseau d'upload (départ, activité, HTTP, fin) pour `fetch` et XHR. Kotlin sort de l'attente à la fin réelle de la requête, et considère le cas sans trafic après 3 secondes comme une pièce jointe traitée localement. Le plafond de 45 s ne reste qu'un filet de sécurité. Journal : « téléversement terminé en … ms (HTTP …) ».

## Build / tests

Gradle Kotlin, compileSdk/targetSdk 34, minSdk 26. Le build APK doit se faire dans le codespace existant selon le workflow du dépôt (`/workspaces/.nemapi-android/build-apk.sh`). Aucun `-wipe-data` : les cookies/session DeepSeek résident sur l'AVD.

Tests de la 1.0.2 :
- `node tools/test-hook.js` — parseur SSE et détection d'endpoint.
- `python3 tools/dashboard_test.py` — pages, assets, endpoints du dashboard, écritures settings/intégrations/clés.
- `python3 tools/agent_compat.py` — 5 cas d'intégration API.
- `python3 tools/image_test.py` — pièces jointes stream/non-stream et lecture des 4 quadrants.
- `/api/net` et requête HTTP depuis une adresse non-loopback de l'émulateur.

Une IP Wi-Fi d'émulateur est virtuelle : le test non-loopback valide le bind réseau de l'app, mais ne remplace pas l'essai depuis un vrai client connecté au Wi-Fi du téléphone.
