# Outils de test NemApi APK

Tous les scripts supposent l'app lancée, le service actif et le port forwardé :

```bash
adb forward tcp:8787 tcp:8787
```

| Script | Rôle |
|---|---|
| `test-hook.js` | **obligatoire avant chaque build** — 10 assertions sur le parseur SSE et le filtre d'URL de `hook.js` (`node test-hook.js`) |
| `sse_probe.py` | un appel en stream avec la chronologie détaillée des fragments |
| `api_tests.py` | batterie API : 7 tests (409 `provider_busy`, annulation, gros contexte, `/v1/responses`, 400) |
| `latency_stats.py` | campagne de latence : 6 prompts, TTFT, total, débit, synthèse |
| `image_test.py` | envoi d'images : PNG généré, 4 quadrants, stream et non-stream, usage |
| `agent_compat.py` | compatibilité agents de codage : `/v1/models`, `system` + code, `tools`, contexte long, `/v1/responses` |
| `restore-session.py` | réinjection des cookies DeepSeek sauvegardés |

Les cookies sont stockés **hors dépôt**, dans `/workspaces/.nemapi-android/session/`
(chmod 600).
