package com.adesa.attendance.mail;

/**
 * Sends transactional e-mail.
 *
 * <p>Implementations must never throw for a delivery failure in a way that
 * changes the outcome of a public endpoint: callers handle
 * {@link MailDeliveryException} so that a recovery request stays generic.
 */
public interface MailService {

    /**
     * Sends one plain-text/HTML message.
     *
     * @throws MailDeliveryException when the message could not be handed to the
     *                               mail server (the caller decides how to
     *                               report this without leaking account
     *                               existence)
     */
    void send(String to, String subject, String textBody, String htmlBody);

    /**
     * True when a real transport is configured. Used to give a clear startup
     * message and to keep the "sent" contract honest in development.
     */
    boolean isDeliveryEnabled();
}
