# Bitrix Document AI Bot

MVP чат-бота Bitrix24 для однократного анализа документов PDF, DOC и DOCX.

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

## Что уже реализовано

- Две независимые настройки моделей: простая и основная.
- Для каждой модели: provider, endpoint, modelId, apiKey.
- Выбор `OPENAI` или `GOOGLE` без захардкоженных ID моделей.
- Редактируемые промпты классификатора и основного анализа.
- Сохранение настроек в `/data/config.json`.
- API-ключи не возвращаются обратно в браузер; пустое поле при сохранении не стирает ранее сохранённый ключ.
- Регистрация и проверка чат-бота Bitrix24.
- Получение событий через `imbot.v2.Event.get`.
- Скачивание вложений через `imbot.v2.File.download`.
- Анализ PDF, DOC и DOCX через извлечение текста Apache Tika.
- Фиксированный отказ для нерелевантных запросов.
- Разбиение длинного ответа на несколько сообщений Bitrix24.
- Админка по `/admin/`.
- Dockerfile, Docker Compose и серверные скрипты.
- Подробное безопасное логирование админки, Bitrix24 REST, poller, файлов и LLM без вывода API-ключей и botToken.

## Входящий webhook Bitrix24

Нужен **входящий webhook** с правом `imbot`:

1. Bitrix24 → Разработчикам / Ресурсы разработчика.
2. Другое → Входящий webhook.
3. В правах выбрать `imbot`.
4. Сохранить.
5. Скопировать полный URL вида:
   `https://portal.bitrix24.ru/rest/1/WEBHOOK_TOKEN/`
6. Вставить URL в админке.
7. Нажать «Сохранить настройки».
8. Нажать «Создать/получить бота».
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

Для локального запуска рекомендуется заменить их переменными окружения:

```bash
export APP_ADMIN_USERNAME=admin
export APP_ADMIN_PASSWORD='strong-password'
mvn spring-boot:run
```

## Настройка endpoint моделей

### OpenAI-compatible Chat Completions

Можно указать как полный endpoint, так и базовый адрес версии API:

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

Для Chat Completions отправляется стандартный массив `messages` и параметр:

```json
{"reasoning":{"enabled":true}}
```

Если конкретный OpenAI-совместимый провайдер явно отклоняет поле `reasoning`, приложение один раз повторяет запрос без него.

### OpenAI Responses API

Responses API используется только при явном полном endpoint:

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

Админка будет доступна на порту `APP_PORT`.


## Диагностические логи

По умолчанию для пакета проекта включён уровень `DEBUG` через:

```dotenv
APP_LOG_LEVEL=DEBUG
```

Просмотр на сервере:

```bash
docker logs -f --tail=300 bitrix-document-ai-bot
```

При нажатии кнопок админки в логе появляются последовательные метки:

```text
ADMIN HTTP
ADMIN CONFIG
ADMIN BITRIX REGISTER
BITRIX BOT
BITRIX API
```

Каждый запрос админки получает `operationId`. Тот же идентификатор выводится в консоль браузера, поэтому запрос можно сопоставить с конкретной цепочкой строк в `docker logs`. Webhook-токен, `botToken`, API-ключи, промпты, документы и тексты сообщений в логах маскируются или заменяются длиной текста.

## Ограничения MVP

- Все документы одного анализа должны быть прикреплены к одному сообщению.
- Сканированные PDF без текстового слоя не распознаются: OCR пока не включён.
- DOC, DOCX и PDF преобразуются в текст перед отправкой модели.
- Нет истории анализов и долговременной памяти.
- Нет OAuth и публикации в Маркете Bitrix24.
- Один контейнер должен быть единственным poller для данного бота.
- Для очень больших документов текст обрезается согласно лимитам в настройках.
