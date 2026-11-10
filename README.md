# NemApi APK — NemApi pour téléphone

![version](https://img.shields.io/badge/version-1.0.0-22d3ee)
![plateforme](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-6366f1)
![licence](https://img.shields.io/badge/licence-MIT-2dd4bf)

**L'API OpenAI dans ton téléphone.** NemApi APK est une application Android autonome qui
expose une API locale compatible OpenAI (`http://<téléphone>:8787/v1`) alimentée par
**DeepSeek Web**, sans clé API, sans carte bancaire, en gardant l'abonnement web que tu
utilises déjà.

```
agent / client OpenAI  ──►  NemApi APK (port 8787)  ──►  WebView + ForegroundService  ──►  chat.deepseek.com
(Cline, Aider, curl…)       API locale, SSE, images       DOM + tee du flux SSE             (session web)
```

> **Ce dépôt publie l'application Android (APK).**
> La version navigateur historique de NemApi — extension Chromium + proxy FastAPI, sept
> fournisseurs — reste disponible dans `extension/` et `proxy/`, documentée dans
> [`docs/README-version-web.md`](docs/README-version-web.md). Les deux versions partagent
> le même contrat d'API mais sont indépendantes.

---

## Sommaire

- [Ce que fait l'application](#ce-que-fait-lapplication)
- [Installation](#installation)
- [Première requête](#première-requête)
- [Depuis un agent de codage](#depuis-un-agent-de-codage)
- [Référence de l'API](#référence-de-lapi)
- [Architecture](#architecture)
- [Performances mesurées](#performances-mesurées)
- [Tests](#tests)
- [Limites connues](#limites-connues)
- [Compilation](#compilation)
- [Licence](#licence)

---

## Ce que fait l'application

| | |
|---|---|
| **API OpenAI locale** | `POST /v1/chat/completions`, `POST /v1/responses`, `GET /v1/models`, streaming SSE |
| **Streaming réel** | les octets SSE reçus par la page sont recopiés tels quels — aucune simulation, aucun faux fragment |
| **Images** | parties `image_url` acceptées (data URI base64 ou URL http), déposées dans le composeur du site |
| **Arrière-plan** | `ForegroundService` + WebView persistant : l'app répond quand l'écran est ailleurs, voire éteint |
| **Tableau de bord** | `GET /dashboard` — état, journal, statistiques, codes d'erreur |
| **Chat intégré** | onglet dédié : bulles, historique multi-tours, pièces jointes, réponse en direct |
| **Un seul fournisseur** | DeepSeek, volontairement — pas de sélecteur qui promet l'impossible |
| **Session durable** | cookies sauvegardables et réinjectables, pas de reconnexion à chaque compilation |

Trois garanties de conception :

1. **Aucun contenu de conversation n'est journalisé** — seules les étapes techniques, les tailles et les codes d'erreur.
2. **Aucun contournement de CAPTCHA** : la connexion se fait une fois, à la main, dans l'app.
3. **`error_code` fermé** — douze codes stables, jamais de message libre inventé.

---

## Installation

1. Télécharge `NemApi-1.0.0.apk` depuis les [Releases](../../releases).
2. Installe-le (autorise les sources inconnues si Android le demande) :

   ```bash
   adb install -r NemApi-1.0.0.apk
   ```
3. Ouvre **NemApi** → **Démarrer** (le service passe en premier plan, notification visible).
4. Onglet **Pilotage** → **Ouvrir le navigateur DeepSeek** → connecte-toi **une seule fois**.
5. Note l'URL affichée dans l'onglet **API** :

   ```
   http://192.168.x.x:8787/v1
   ```

   (ou `http://127.0.0.1:8787/v1` depuis le téléphone lui-même, avec `adb forward tcp:8787 tcp:8787` depuis un ordinateur branché en USB)

L'APK publié est signé avec la clé de développement : il s'installe partout, mais ne peut
pas être mis à jour par le Play Store.

---

## Première requête

```bash
curl -N http://127.0.0.1:8787/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "deepseek-chat",
    "stream": true,
    "messages": [{"role": "user", "content": "Dis bonjour en trois mots"}]
  }'
```

Réponse (extrait réel) :

```
data: {"id":"chatcmpl-1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}
data: {"choices":[{"index":0,"delta":{"content":"Bonjour"},"finish_reason":null}]}
data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
data: [DONE]
```

Avec une image :

```json
{
  "model": "deepseek-chat",
  "messages": [{
    "role": "user",
    "content": [
      {"type": "text", "text": "Décris cette image"},
      {"type": "image_url", "image_url": {"url": "data:image/png;base64,iVBORw0…"}}
    ]
  }]
}
```

---

## Depuis un agent de codage

Cline, Roo Code, Continue, Aider, Cursor, un script maison — tout client qui accepte une
`base_url` OpenAI fonctionne.

| Réglage | Valeur |
|---|---|
| Base URL | `http://<ip-du-téléphone>:8787/v1` |
| API key | n'importe quoi (`nemapi`) — le Bearer est optionnel et désactivé par défaut |
| Modèle | `deepseek-chat` ou `deepseek-reasoner` |

Points vérifiés : `/v1/models` répond, les consignes `system` sont transmises, le champ
`tools` est **toléré** (la requête aboutit, l'appel d'outil n'est pas exécuté côté
téléphone), un contexte de plusieurs milliers de caractères passe, et `/v1/responses`
fonctionne. Test automatisé : `tools/agent_compat.py`.

---

## Référence de l'API

| Méthode | Chemin | Rôle |
|---|---|---|
| `GET` | `/v1/models` | modèles exposés |
| `POST` | `/v1/chat/completions` | conversation, `stream: true/false`, images |
| `POST` | `/v1/responses` | variante « Responses API » (mapping minimal) |
| `GET` | `/dashboard` | tableau de bord HTML |
| `GET` | `/api/status` | état complet en JSON |
| `GET` | `/api/debug/latency` | statistiques de latence (moyenne, p50, p90, min, max) |
| `GET` | `/api/debug/describe` | structure de la page, sans contenu |
| `POST` | `/api/debug/cookies` | restauration de session |
| `POST` | `/api/debug/eval` | évaluation JS de diagnostic (usage interne) |

### Codes d'erreur (contrat fermé)

`invalid_request` · `provider_busy` · `provider_not_configured` · `selector_missing` ·
`login_required` · `timeout` · `network_error` · `upstream_error` · `cancelled` ·
`not_ready` · `unauthorized` · `unsupported`

Chacun a un code HTTP stable (`409` pour `provider_busy`, `504` pour `timeout`, `400` sinon).

---

## Architecture

```
┌──────────────────────────── téléphone ────────────────────────────┐
│  ApiServer (socket maison, SSE)          :8787                     │
│        │                                                            │
│        ▼                                                            │
│  DeepSeek.kt — orchestrateur                                        │
│   1. DOM  : écrire le prompt, déposer les images, cliquer Envoyer   │
│   2. hook : lire le flux SSE que la page reçoit (tee, pas de MITM)  │
│   3. DOM  : lecture de secours si le flux n'a rien donné            │
│        │                                                            │
│        ▼                                                            │
│  WebView unique (session conservée) + BridgeService (1er plan)      │
└────────────────────────────────────────────────────────────────────┘
```

| Dossier | Contenu |
|---|---|
| `app/src/main/java/com/nemap/android/core` | orchestrateur, état, journal, erreurs |
| `.../web` | hôte du WebView, moteur d'évaluation JS |
| `.../api` | serveur HTTP, routes OpenAI, dashboard |
| `.../service` | service de premier plan |
| `.../ui` | thème néon (labels, cartes, pastilles, boutons) |
| `assets/js/hook.js` | accroche `fetch`/XHR au démarrage du document, parseur SSE |
| `assets/js/adapter-deepseek.js` | sélecteurs DOM, saisie, envoi, pièces jointes |
| `tools/` | tests et sondes (voir plus bas) |

---

## Performances mesurées

Six requêtes réelles, application au premier plan, sur un DeepSeek connecté :

| Type | Premier fragment | Total | Caractères | Fragments |
|---|---|---|---|---|
| court (« bonjour ») | 1,56 s | 2,10 s | 7 | 1 |
| court (capitale) | 2,55 s | 2,56 s | 29 | 1 |
| moyen (5 phrases) | 2,33 s | 3,95 s | 579 | 3 |
| moyen (3 avantages) | 2,63 s | 3,76 s | 629 | 3 |
| long (150 mots) | 2,93 s | 5,15 s | 1 159 | 5 |
| code (Kotlin) | 2,28 s | 3,37 s | 470 | 3 |

- **Premier fragment** : 1,56 s mini, **2,38 s en moyenne**, 2,93 s maxi
- **Durée totale** : 2,10 s mini, **3,48 s en moyenne**, 5,15 s maxi
- **Débit** : 115 caractères/s en moyenne, jusqu'à 225 sur une réponse longue
- **Images** : ≈ 48 s (le site téléverse et analyse l'image ; le transport, lui, prend moins d'une seconde)
- **Arrière-plan** : 3 requêtes sur 3 abouties (2,10 / 1,86 / 1,60 s) avec l'écran d'accueil affiché
- **Fiabilité** : 9 appels consécutifs, 0 erreur (`errors_by_code = {}`)

---

## Tests

```bash
cd tools
node test-hook.js            # 10 assertions sur le parseur et le filtre d'URL
python3 agent_compat.py      # compatibilité agents de codage (5 tests)
python3 latency_stats.py     # campagne de latence
python3 image_test.py        # envoi d'images (stream et non-stream)
python3 sse_probe.py "…"     # un appel stream et sa chronologie
python3 api_tests.py         # batterie API (7 tests : 409, annulation, gros contexte…)
python3 restore-session.py   # réinjection des cookies sauvegardés
```

`test-hook.js` est **obligatoire avant chaque compilation** : il existe parce qu'un
`catch` silencieux a déjà masqué une régression (`isChatCall is not defined`) pendant
plusieurs essais.

---

## Limites connues

- **La session DeepSeek expire** : l'app le signale (`login_required`) et il suffit de se reconnecter dans l'onglet du navigateur. Les cookies peuvent être sauvegardés et réinjectés, mais un jeton révoqué ne se ressuscite pas.
- **Images ≈ 48 s** : latence imposée par le site, pas par l'app.
- **Un seul fournisseur** : DeepSeek. C'est un choix pour cette version, pas un oubli.
- **Classes DeepSeek hachées** : les sélecteurs sont heuristiques et retombent sur une lecture DOM si le site change.
- **`tools` non exécutés** : un agent qui attend un appel d'outil obtiendra une réponse texte.
- **APK signé debug** : installation directe, pas de mise à jour par magasin.
- L'API écoute sur toutes les interfaces : à n'utiliser que sur un réseau de confiance.

---

## Compilation

```bash
cd android
./gradlew assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
```

AGP 8.5.2 · Kotlin 2.0.20 · Gradle 8.7 · JDK 17 · minSdk 26 · targetSdk 34.

---

## Licence

MIT — voir [`LICENSE`](LICENSE). Projet personnel : aucune affiliation avec DeepSeek.
