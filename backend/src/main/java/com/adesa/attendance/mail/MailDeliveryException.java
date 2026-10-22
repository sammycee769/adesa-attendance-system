package com.adesa.attendance.mail;

/** Thrown when a message could not be handed to the mail server. */
public class MailDeliveryException extends RuntimeException {

    public MailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
