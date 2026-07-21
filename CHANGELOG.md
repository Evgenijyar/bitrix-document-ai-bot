# Changelog

## 0.1.9
- Исправлена загрузка URL вида `/rest/{user}/{webhook}/download/`, которые облачный Bitrix24 возвращает вместо `/rest/download.json?token=...`.
- Для legacy download endpoint добавлены POST form и POST JSON стратегии с `id`, `fileId`, `botId`, `botToken`, `dialogId`.
- Сохранен стандартный GET для одноразовых machine URL.
- Добавлен последний fallback `disk.file.getExternalLink`.
- Убрано ошибочное сообщение, будто отсутствие `disk` является единственной причиной сбоя.

## 0.1.8
- Добавлен fallback через Disk API.

## 0.1.10
- Logs full extracted document text and exact system/user prompts sent to the LLM.
- Logs exact JSON request body for OpenAI-compatible endpoints.
- Detects and rejects Bitrix24 HTML viewer pages masquerading as downloaded documents.
- Adds file SHA-256, detected MIME type, and magic bytes to diagnostics.
- Tries concrete webhook download candidates using both disk object ID and internal FILE_ID.
- Tries public-link download variants before rejecting the response.
