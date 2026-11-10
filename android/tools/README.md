# Outils de développement

| Fichier | Rôle |
|---|---|
| `test-hook.js` | Tests Node du hook réseau : parseur DeepSeek / OpenAI / blocs THINK, et câblage XHR. `node tools/test-hook.js` |
| `sse_probe.py` | Horodate chaque delta SSE reçu de l'API locale (mesure du streaming). |
| `api_tests.py` | Batterie de tests : concurrence (`provider_busy`), annulation, prompt long, `/v1/responses`, erreurs. |
| `restore-session.py` | Restaure la session provider sauvegardée (cookies) dans le WebView. |

Les scripts Python s'exécutent **dans le codespace** (l'API est joignable via
`adb forward tcp:8787 tcp:8787`).
