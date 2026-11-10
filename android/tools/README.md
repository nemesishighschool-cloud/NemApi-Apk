# Outils de test NemApi APK

Tous les scripts API supposent l'app lancée, le service actif et le port forwardé dans l'émulateur :

```bash
adb forward tcp:8090 tcp:8090
```

| Script | Rôle |
|---|---|
| `test-hook.js` | **obligatoire avant chaque build** — 10 assertions sur le parseur SSE et le filtre d'URL de `hook.js` (`node test-hook.js`) |
| `test-continue.js` | test DOM synthétique du bouton « Continue » : détecté désactivé, puis cliqué une seule fois lorsqu'il devient actif |
| `dashboard_test.py` | pages NemApi web originales, assets, endpoints JSON, écritures, absence de contenu conversationnel |
| `sse_probe.py` | un appel en stream avec la chronologie détaillée des fragments |
| `api_tests.py` | batterie API : 7 tests (409 `provider_busy`, annulation, gros contexte, `/v1/responses`, 400) |
| `latency_stats.py` | campagne de latence : 6 prompts, TTFT, total, débit, synthèse |
| `image_test.py` | envoi d'images : PNG généré, 4 quadrants, stream et non-stream, usage |
| `pdf_test.py` | PDF `file_data` natif, Chat Completions et Responses; PDF 9 pages lu en un seul fichier, erreurs explicites |
| `qwen_incremental_test.py` | Qwen Code : contexte initial >64k puis deux tours incrémentaux |
| `agent_compat.py` | compatibilité agents de codage : `/v1/models`, `system` + code, `tools`, contexte long, `/v1/responses` |
| `restore-session.py` | réinjection des cookies DeepSeek sauvegardés |

Diagnostics à ouvrir directement : `http://127.0.0.1:8090/api/net` donne les interfaces/IP de l'appareil et l'URL réseau à essayer ; `http://127.0.0.1:8090/api/debug/latency` donne les mesures de génération.

Les cookies sont stockés **hors dépôt**, dans `/workspaces/.nemapi-android/session/` (chmod 600). Ne jamais démarrer l'AVD avec `-wipe-data`.
