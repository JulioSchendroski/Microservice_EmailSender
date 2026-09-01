package com.ms.email.Service;

/**
 * Lançada quando o envio de e-mail falha.
 * No fluxo síncrono vira HTTP 502; no assíncrono aciona a DLQ.
 */
public class EmailSendException extends RuntimeException {

    public EmailSendException(String message, Throwable cause) {
        super(message, cause);
    }
}
