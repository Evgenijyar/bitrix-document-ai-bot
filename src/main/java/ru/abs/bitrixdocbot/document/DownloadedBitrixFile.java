package ru.abs.bitrixdocbot.document;

public record DownloadedBitrixFile(
    long fileId,
    String fileName,
    Long declaredSize,
    byte[] content,
    String source
) {
    public DownloadedBitrixFile {
        fileName = fileName == null || fileName.isBlank() ? "bitrix-file-" + fileId : fileName;
        content = content == null ? new byte[0] : content;
        source = source == null || source.isBlank() ? "unknown" : source;
    }
}
