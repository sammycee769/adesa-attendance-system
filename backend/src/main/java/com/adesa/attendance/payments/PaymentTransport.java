package com.adesa.attendance.payments;

/** Only explicit provider rejections may be retried. Timeouts are uncertain. */
public interface PaymentTransport {
 record Receipt(String messageId, boolean mock) {}
 Receipt deliver(PaymentComposer.Message message);
 boolean live();
 class Rejected extends RuntimeException {
  final boolean retryable;
  public Rejected(boolean retryable) {super("Provider rejected the request before acceptance");this.retryable=retryable;}
 }
}
