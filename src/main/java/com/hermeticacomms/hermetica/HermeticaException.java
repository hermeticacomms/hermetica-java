package com.hermeticacomms.hermetica;

/** Thrown for any non-2xx answer. {@link #getMessage()} is "status code: errorMessage". */
public class HermeticaException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    /** HTTP status. */
    public final int status;
    /** Error code from the API, or "http_error". */
    public final String code;
    /** Error message from the API (or the start of the raw body). */
    public final String errorMessage;
    /** Parsed error details, or null. */
    public final transient Object details;

    /**
     * @param status HTTP status
     * @param code error code
     * @param errorMessage error message
     * @param details parsed details or null
     */
    public HermeticaException(int status, String code, String errorMessage, Object details) {
        super(status + " " + code + ": " + errorMessage);
        this.status = status;
        this.code = code;
        this.errorMessage = errorMessage;
        this.details = details;
    }
}
