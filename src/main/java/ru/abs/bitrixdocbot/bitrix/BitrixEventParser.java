package ru.abs.bitrixdocbot.bitrix;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import ru.abs.bitrixdocbot.document.BitrixAttachment;

@Service
public class BitrixEventParser {

    public String extractDialogId(JsonNode data) {
        for (String pointer : new String[]{"/chat/dialogId", "/message/dialogId", "/dialogId"}) {
            String value = data.at(pointer).asString("");
            if (!value.isBlank()) {
                return value;
            }
        }
        long chatId = data.at("/chat/id").asLong(0);
        return chatId > 0 ? "chat" + chatId : "";
    }

    public String extractMessageText(JsonNode data) {
        for (String pointer : new String[]{"/message/text", "/message/message", "/message/body", "/text"}) {
            String value = data.at(pointer).asString("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    public List<BitrixAttachment> extractAttachments(JsonNode data) {
        JsonNode message = data.path("message");
        Map<Long, BitrixAttachment> unique = new LinkedHashMap<>();
        collectFileContainers(message, unique);
        return new ArrayList<>(unique.values());
    }

    private void collectFileContainers(JsonNode node, Map<Long, BitrixAttachment> unique) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                if ("files".equalsIgnoreCase(entry.getKey()) || "file".equalsIgnoreCase(entry.getKey())) {
                    collectCandidates(entry.getValue(), unique);
                } else if (entry.getValue().isContainer()) {
                    collectFileContainers(entry.getValue(), unique);
                }
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectFileContainers(child, unique));
        }
    }

    private void collectCandidates(JsonNode node, Map<Long, BitrixAttachment> unique) {
        if (node.isArray()) {
            node.forEach(child -> collectCandidates(child, unique));
            return;
        }
        if (node.isObject()) {
            long id = firstLong(node, "id", "fileId", "diskFileId", "ID", "FILE_ID");
            if (id > 0) {
                String name = firstText(node, "name", "fileName", "NAME", "FILE_NAME");
                Long size = firstLong(node, "size", "fileSize", "SIZE") > 0
                    ? firstLong(node, "size", "fileSize", "SIZE")
                    : null;
                unique.putIfAbsent(id, new BitrixAttachment(id, name.isBlank() ? "file-" + id : name, size));
            }
            node.values().forEach(child -> collectCandidates(child, unique));
        }
    }

    private long firstLong(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isIntegralNumber() || value.isString()) {
                long parsed = value.asLong(0);
                if (parsed > 0) return parsed;
            }
        }
        return 0;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asString("");
            if (!value.isBlank()) return value;
        }
        return "";
    }
}
