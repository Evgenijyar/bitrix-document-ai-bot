package ru.abs.bitrixdocbot.bitrix;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class BitrixEventParserTest {

    private final BitrixEventParser parser = new BitrixEventParser();
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void extractsFileIdsFromMessageParamsFileIdArray() throws Exception {
        JsonNode data = mapper.readTree("""
            {
              "message": {
                "text": "Я - арендодатель",
                "params": {
                  "FILE_ID": [239590, "239592"]
                }
              },
              "chat": {
                "dialogId": 216
              }
            }
            """);

        assertThat(parser.extractMessageText(data)).isEqualTo("Я - арендодатель");
        assertThat(parser.extractAttachments(data))
            .extracting(attachment -> attachment.fileId())
            .containsExactly(239590L, 239592L);
    }

    @Test
    void keepsRealFileMetadataWhenItIsAlsoPresent() throws Exception {
        JsonNode data = mapper.readTree("""
            {
              "message": {
                "params": {"FILE_ID": [239590]},
                "files": [
                  {"id": 239590, "name": "Договор.pdf", "size": 2048}
                ]
              }
            }
            """);

        assertThat(parser.extractAttachments(data)).singleElement().satisfies(attachment -> {
            assertThat(attachment.fileId()).isEqualTo(239590L);
            assertThat(attachment.fileName()).isEqualTo("Договор.pdf");
            assertThat(attachment.declaredSize()).isEqualTo(2048L);
        });
    }
}
