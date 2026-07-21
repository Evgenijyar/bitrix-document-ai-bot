# Changelog

## 0.1.3

- Added correlated console logging for all admin API operations with `operationId`.
- Added detailed Bitrix24 bot registration, bot check, polling, message and file operation logs.
- Added safe outbound Bitrix24/OpenAI/Google request and response summaries.
- Added full stack traces and HTTP response bodies for failed external API calls.
- Added configuration persistence logs without exposing API keys, webhook secrets or bot tokens.
- Added browser console logging for every admin button and HTTP request.
- Fixed unhandled frontend promise errors when saving before bot registration or model tests.
- Added configurable `APP_LOG_LEVEL` and an operation ID in the console log pattern.

## 0.1.2

- Fixed HTTP 404 at `/admin/`.
- `/`, `/admin`, and `/admin/` now redirect to `/admin/index.html`.
- Added a regression test for the admin entry-point redirect.

## 0.1.1

- Updated deprecated Jackson 3 calls to `isString()`, `asString()` and `isContainer()`.
- Removed all reported Jackson 3 deprecation warnings.
- Fixed compilation failure caused by the removed `isContainerNode()` method.
