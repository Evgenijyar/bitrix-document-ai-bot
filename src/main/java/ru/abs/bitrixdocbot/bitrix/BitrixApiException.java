package ru.abs.bitrixdocbot.bitrix;

public class BitrixApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BitrixApiException(String message) {
        super(message);
    }

    public BitrixApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
