package ru.abs.bitrixdocbot.admin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public class BotConfigurationDto {

    @Valid
    private ModelSettingsDto simpleModel = new ModelSettingsDto();
    @Valid
    private ModelSettingsDto complexModel = new ModelSettingsDto();
    @Valid
    private BitrixSettingsDto bitrix = new BitrixSettingsDto();

    private String relevancePrompt = "";
    private String analysisPrompt = "";
    private String irrelevantReply = "";
    private String processingReply = "";
    private String errorReply = "";

    @Min(1)
    @Max(50)
    private int maxFileCount = 10;
    @Min(1)
    private long maxFileSizeBytes = 20L * 1024 * 1024;
    @Min(1000)
    private int maxExtractedCharsPerFile = 250_000;
    @Min(1000)
    private int maxTotalExtractedChars = 700_000;
    @Min(500)
    @Max(10000)
    private int outgoingMessageChunkSize = 3_500;

    public ModelSettingsDto getSimpleModel() {
        return simpleModel;
    }

    public void setSimpleModel(ModelSettingsDto simpleModel) {
        this.simpleModel = simpleModel;
    }

    public ModelSettingsDto getComplexModel() {
        return complexModel;
    }

    public void setComplexModel(ModelSettingsDto complexModel) {
        this.complexModel = complexModel;
    }

    public BitrixSettingsDto getBitrix() {
        return bitrix;
    }

    public void setBitrix(BitrixSettingsDto bitrix) {
        this.bitrix = bitrix;
    }

    public String getRelevancePrompt() {
        return relevancePrompt;
    }

    public void setRelevancePrompt(String relevancePrompt) {
        this.relevancePrompt = relevancePrompt;
    }

    public String getAnalysisPrompt() {
        return analysisPrompt;
    }

    public void setAnalysisPrompt(String analysisPrompt) {
        this.analysisPrompt = analysisPrompt;
    }

    public String getIrrelevantReply() {
        return irrelevantReply;
    }

    public void setIrrelevantReply(String irrelevantReply) {
        this.irrelevantReply = irrelevantReply;
    }

    public String getProcessingReply() {
        return processingReply;
    }

    public void setProcessingReply(String processingReply) {
        this.processingReply = processingReply;
    }

    public String getErrorReply() {
        return errorReply;
    }

    public void setErrorReply(String errorReply) {
        this.errorReply = errorReply;
    }

    public int getMaxFileCount() {
        return maxFileCount;
    }

    public void setMaxFileCount(int maxFileCount) {
        this.maxFileCount = maxFileCount;
    }

    public long getMaxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    public void setMaxFileSizeBytes(long maxFileSizeBytes) {
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public int getMaxExtractedCharsPerFile() {
        return maxExtractedCharsPerFile;
    }

    public void setMaxExtractedCharsPerFile(int maxExtractedCharsPerFile) {
        this.maxExtractedCharsPerFile = maxExtractedCharsPerFile;
    }

    public int getMaxTotalExtractedChars() {
        return maxTotalExtractedChars;
    }

    public void setMaxTotalExtractedChars(int maxTotalExtractedChars) {
        this.maxTotalExtractedChars = maxTotalExtractedChars;
    }

    public int getOutgoingMessageChunkSize() {
        return outgoingMessageChunkSize;
    }

    public void setOutgoingMessageChunkSize(int outgoingMessageChunkSize) {
        this.outgoingMessageChunkSize = outgoingMessageChunkSize;
    }
}
