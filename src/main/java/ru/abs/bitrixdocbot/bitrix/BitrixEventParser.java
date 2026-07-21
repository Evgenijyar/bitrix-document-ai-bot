package ru.abs.bitrixdocbot.bitrix;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.abs.bitrixdocbot.document.BitrixAttachment;
import tools.jackson.databind.JsonNode;

@Service
public class BitrixEventParser {

    private static final Logger log = LoggerFactory.getLogger(BitrixEventParser.class);

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

        // Some Bitrix24 revisions return complete file objects in message.files/message.file.
        collectFileContainers(message, unique);

        // Chatbots 2.0 message events normally return attached Disk file IDs here:
        // message.params.FILE_ID = [239590, ...]
        collectFileIdParameters(message.path("params"), unique);

        List<BitrixAttachment> attachments = new ArrayList<>(unique.values());
        log.debug("BITRIX EVENT attachments parsed count={} ids={}",
            attachments.size(),
            attachments.stream().map(BitrixAttachment::fileId).toList());
        return attachments;
    }

    private void collectFileContainers(JsonNode node, Map<Long, BitrixAttachment> unique) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                String key = normalizeKey(entry.getKey());
                if ("FILES".equals(key) || "FILE".equals(key)) {
                    collectCandidates(entry.getValue(), unique);
                } else if (entry.getValue().isContainer()) {
                    collectFileContainers(entry.getValue(), unique);
                }
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectFileContainers(child, unique));
        }
    }

    private void collectFileIdParameters(JsonNode node, Map<Long, BitrixAttachment> unique) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                if (isFileIdKey(entry.getKey())) {
                    collectFileIdValues(entry.getValue(), unique);
                } else if (entry.getValue().isContainer()) {
                    collectFileIdParameters(entry.getValue(), unique);
                }
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectFileIdParameters(child, unique));
        }
    }

    private void collectFileIdValues(JsonNode node, Map<Long, BitrixAttachment> unique) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectFileIdValues(child, unique));
            return;
        }
        if (node.isObject()) {
            collectCandidates(node, unique);
            return;
        }
        if (node.isIntegralNumber()) {
            addAttachment(unique, node.asLong(0), "", null);
            return;
        }
        if (node.isString()) {
            // Usually it is a single numeric string. Splitting also tolerates comma-separated IDs.
            for (String token : node.asString("").split("[,;\\s]+")) {
                if (token.isBlank()) {
                    continue;
                }
                try {
                    addAttachment(unique, Long.parseLong(token), "", null);
                } catch (NumberFormatException ignored) {
                    log.debug("BITRIX EVENT ignored non-numeric FILE_ID value={}", token);
                }
            }
        }
    }

    private void collectCandidates(JsonNode node, Map<Long, BitrixAttachment> unique) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectCandidates(child, unique));
            return;
        }
        if (node.isObject()) {
            long id = firstLong(node, "id", "fileId", "diskFileId", "ID", "FILE_ID");
            if (id > 0) {
                String name = firstText(node, "name", "fileName", "NAME", "FILE_NAME");
                long rawSize = firstLong(node, "size", "fileSize", "SIZE");
                addAttachment(unique, id, name, rawSize > 0 ? rawSize : null);
            }
            node.values()
                .filter(JsonNode::isContainer)
                .forEach(child -> collectCandidates(child, unique));
        }
    }

    private void addAttachment(
        Map<Long, BitrixAttachment> unique,
        long id,
        String name,
        Long size
    ) {
        if (id <= 0) {
            return;
        }
        String resolvedName = name == null || name.isBlank() ? "bitrix-file-" + id : name;
        BitrixAttachment candidate = new BitrixAttachment(id, resolvedName, size);
        BitrixAttachment existing = unique.get(id);
        if (existing == null) {
            unique.put(id, candidate);
            return;
        }

        boolean existingHasPlaceholderName = existing.fileName().startsWith("bitrix-file-");
        boolean candidateHasRealName = !candidate.fileName().startsWith("bitrix-file-");
        String mergedName = existingHasPlaceholderName && candidateHasRealName
            ? candidate.fileName()
            : existing.fileName();
        Long mergedSize = existing.declaredSize() != null ? existing.declaredSize() : candidate.declaredSize();
        unique.put(id, new BitrixAttachment(id, mergedName, mergedSize));
    }

    private boolean isFileIdKey(String key) {
        String normalized = normalizeKey(key);
        return "FILEID".equals(normalized)
            || "FILEIDS".equals(normalized)
            || "DISKFILEID".equals(normalized)
            || "DISKFILEIDS".equals(normalized);
    }

    private String normalizeKey(String key) {
        return key == null
            ? ""
            : key.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    private long firstLong(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isIntegralNumber() || value.isString()) {
                long parsed = value.asLong(0);
                if (parsed > 0) {
                    return parsed;
                }
            }
        }
        return 0;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asString("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
