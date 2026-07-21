# Bitrix Document AI Bot

MVP чат-бота Bitrix24 для однократного анализа прикреплённых документов и других файлов, из которых Apache Tika может извлечь текст.

## Стек

- Java 21
- Spring Boot 4.1.0
- Spring MVC + статический HTML/CSS/JS
- Spring Security HTTP Basic
- Apache Tika 3.3.1 для извлечения текста
- Bitrix24 Chatbots 2.0 (`imbot.v2`, режим `fetch`)
- OpenAI Responses API или OpenAI Chat Completions API
- Google Gemini `generateContent`
- Docker / Docker Compose

Lombok и Thymeleaf не используются.

## Логика обработки сообщений

Проверочной LLM и классификатора релевантности в проекте нет.

Правила реализованы обычным Java-кодом:

1. Сообщение без вложений не передаётся модели. Бот отвечает: `Прикрепите файлы документов`.
2. Если вложен один или несколько файлов, backend скачивает их из Bitrix24.
3. Apache Tika пытается извлечь текст из каждого файла.
4. Файлы с извлекаемым текстом передаются основной модели одним комплектом.
5. Файлы без извлекаемого текста пропускаются.
6. Если текст не удалось извлечь ни из одного вложения, бот отвечает: `Прикрепите файлы документов`.

Это позволяет работать не только с PDF, DOC и DOCX, но и с TXT, RTF, ODT, HTML, XML, JSON, CSV, таблицами и другими форматами, которые поддерживает Apache Tika. Сканированные PDF без текстового слоя пока требуют отдельного OCR.

## Что реализовано

- Одна настраиваемая основная модель.
- Настройки модели: provider, endpoint, modelId, apiKey.
- Выбор `OPENAI` или `GOOGLE` без захардкоженного ID модели.
- Редактируемый основной системный промпт.
- Программная маршрутизация сообщений по наличию вложений и результату извлечения текста.
- Сохранение настроек в `/data/config.json`.
- API-ключ не возвращается обратно в браузер; пустое поле при сохранении не стирает ранее сохранённый ключ.
- Автоматическая миграция старого `config.json`: настройки простой модели и промпт релевантности удаляются.
- Регистрация и проверка чат-бота Bitrix24.
- Получение событий через `imbot.v2.Event.get`.
- Скачивание вложений через `imbot.v2.File.download` с `dialogId`.
- Автоматический fallback через `disk.file.get`, если портал возвращает некорректную ссылку скачивания.
- Разбиение длинного ответа на несколько сообщений Bitrix24.
- Админка по `/admin/`.
- Dockerfile, Docker Compose и серверные скрипты.
- Подробное безопасное логирование админки, Bitrix24 REST, файлов и LLM без вывода API-ключей и botToken.

## Входящий webhook Bitrix24

Нужен **входящий webhook** с правами `imbot` и `disk`.

`imbot` используется для регистрации бота, polling событий и сообщений. `disk` нужен как резервный
официальный маршрут `disk.file.get`, если конкретная ревизия Bitrix24 возвращает из
`imbot.v2.File.download` некорректную webhook-подобную ссылку.

1. Bitrix24 → Разработчикам / Ресурсы разработчика.
2. Другое → Входящий webhook.
3. В правах выбрать `imbot` и `disk`.
4. Сохранить.
5. Скопировать полный URL вида:
   `https://portal.bitrix24.ru/rest/1/WEBHOOK_TOKEN/`
6. Вставить URL в админке.
7. Нажать «Сохранить настройки».
8. Нажать «Создать / получить бота».
9. Нажать «Проверить бота».

Проект использует режим `fetch`, поэтому Bitrix24 не должен отправлять события на публичный callback URL.

## Локальный запуск

Требования: JDK 21 и Maven 3.9+.

```bash
mvn clean test
mvn spring-boot:run
```

Открыть:

```text
http://localhost:8080/admin/
```

Данные по умолчанию:

```text
login: admin
password: change-this-password
```

## Настройка основной модели

### OpenAI-compatible Chat Completions

```text
Provider: OPENAI
Endpoint: https://api.tokenator.top/v1
Model ID: gpt-5.5
API key: ваш ключ агрегатора
```

Адрес, заканчивающийся на `/v1`, автоматически преобразуется в:

```text
https://api.tokenator.top/v1/chat/completions
```

Для Chat Completions отправляется массив `messages` и параметр:

```json
{"reasoning":{"enabled":true}}
```

Если провайдер отклоняет поле `reasoning`, приложение один раз повторяет запрос без него.

### OpenAI Responses API

Responses API используется при явном полном endpoint:

```text
Provider: OPENAI
Endpoint: https://api.openai.com/v1/responses
Model ID: укажите нужный ID модели
API key: ваш ключ OpenAI
```

### Google Gemini generateContent

```text
Provider: GOOGLE
Endpoint: https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent
Model ID: укажите нужный ID модели
API key: ваш ключ Google AI Studio
```

Маркер `{model}` автоматически заменяется значением Model ID.

## Docker

```bash
cp .env.example .env
nano .env
docker compose up -d --build
docker logs -f --tail=300 bitrix-document-ai-bot
```

## Диагностические логи

```bash
docker logs -f --tail=300 bitrix-document-ai-bot
```

При обработке сообщения появляются метки:

```text
MESSAGE PROCESSOR started
MESSAGE PROCESSOR file started
MESSAGE PROCESSOR file accepted
MESSAGE PROCESSOR file skipped
MESSAGE PROCESSOR analysis started
LLM GATEWAY
```

## Ограничения MVP

- Все документы одного анализа должны быть прикреплены к одному сообщению.
- Сканированные PDF без текстового слоя не распознаются: OCR пока не включён.
- Нет истории анализов и долговременной памяти.
- Нет OAuth и публикации в Маркете Bitrix24.
- Один контейнер должен быть единственным poller для данного бота.
- Для очень больших документов текст обрезается согласно лимитам в настройках.

### Bitrix24 file download implementation

For incoming chat files the application uses the Drive API sequence `disk.file.get` → `disk.file.getVersions` → `disk.version.get` when a fresh link is needed. Only the signed `https://<portal>/rest/download.json?...token=...` URL is fetched. The URL is passed as an exact `URI`, preserving the signed query string byte-for-byte. The resulting binary is validated and then parsed by Apache Tika.
