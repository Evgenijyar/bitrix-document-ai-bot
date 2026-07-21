# Changelog

## 0.1.9
- Исправлена загрузка URL вида `/rest/{user}/{webhook}/download/`, которые облачный Bitrix24 возвращает вместо `/rest/download.json?token=...`.
- Для legacy download endpoint добавлены POST form и POST JSON стратегии с `id`, `fileId`, `botId`, `botToken`, `dialogId`.
- Сохранен стандартный GET для одноразовых machine URL.
- Добавлен последний fallback `disk.file.getExternalLink`.
- Убрано ошибочное сообщение, будто отсутствие `disk` является единственной причиной сбоя.

## 0.1.8
- Добавлен fallback через Disk API.
