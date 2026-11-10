# Changelog — NemApi Android

## 1.1.1 — 2026-11-10

- Qwen Code seulement : conversion des appels d’outils DSML en `tool_calls` OpenAI compatibles, avec rappel correctif joint au résultat d’outil.
- Les réponses longues restent diffusées pendant les reprises « Continue »; chevauchement dédoublonné et keep-alive SSE.
- Dashboard : intégrations limitées à Nemesis puis Qwen Code, descriptions simplifiées.
- Chat dashboard et APK : pièces jointes PDF natives, fichier original transmis en un seul `application/pdf`.

APK : `NemApi-1.1.1.apk` — `versionCode 7`, `versionName 1.1.1`.

## 1.1.0 — 2026-11-10

- Ajout du contexte incrémental Qwen Code : détection par User-Agent/en-tête, contexte initial complet puis deltas des tours suivants.
- Ajout de la reprise DeepSeek uniquement lorsqu'un bouton visible « Continue » est présent ; ajout du contrôle de chevauchement des fragments.
- Prise en charge des fichiers PDF natifs OpenAI (`file_data` dans Chat Completions et `input_file` dans Responses). Le PDF est téléversé directement comme un seul fichier `application/pdf`, sans rasterisation ni découpage des pages.
- Ajout d'erreurs explicites pour les PDF invalides et les `file_id` nécessitant l'API Files.
- Conservation du chemin classique, de Nemesis, des images et des autres types de fichiers.
- Ajout de tests live pour le contexte Qwen >64k, les formes PDF natives et un marqueur placé en page 9 d'un PDF multi-page.

APK : `NemApi-1.1.0.apk` — `versionCode 6`, `versionName 1.1.0`.
