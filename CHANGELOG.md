# Changelog

## 0.1.1-SNAPSHOT

- Migrated deprecated Jackson 3 tree API calls:
  - `JsonNode.isTextual()` → `JsonNode.isString()`
  - `JsonNode.asText()` / `asText(default)` → `JsonNode.asString()` / `asString(default)`
  - `JsonNode.isContainerNode()` → `JsonNode.isContainer()`
- Fixed compilation failure in `BitrixEventParser`.
