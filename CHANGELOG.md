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

## 0.1.11

- Replaced guessed webhook download endpoints and public viewer links with the official Disk version flow.
- The backend now obtains metadata with `disk.file.get`, resolves the current version with `disk.file.getVersions`, optionally refreshes it with `disk.version.get`, and downloads the exact signed `/rest/download.json` URL.
- Signed download URLs are passed to Spring as `URI` objects so `%`-encoded authorization tokens are not re-encoded.
- Downloaded byte length and PDF/DOC/DOCX signatures are checked before Apache Tika receives the file.

## 0.1.12

- Основным способом скачивания вложений стал официальный `im.v2.File.download`.
- Для личного чата передаются фактические `dialogId` и `fileId` из события.
- `imbot.v2.File.download` и Disk API оставлены только резервными маршрутами.
- Вебхуку теперь требуются права `imbot`, `im` и `disk`.
- Метаданные файла (`NAME`, `SIZE`) читаются отдельно через `disk.file.get` и используются для проверки бинарного ответа.

## 0.1.13

- Исправлена перспектива `dialogId` для `im.v2.File.download` в личном чате.
- Для вызова от имени владельца входящего webhook используется ID собеседника — ID бота, а не ID самого владельца webhook.
- Для групповых чатов `chat{chatId}` передаётся без изменений.
- Добавлен безопасный fallback на исходный `dialogId` и диагностическое логирование преобразования.
