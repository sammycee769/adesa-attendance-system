package com.adesa.attendance.payments;
import java.math.*;
import java.text.DecimalFormat;
import java.time.YearMonth;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
@Component
public class PaymentComposer {
 private final com.adesa.attendance.config.AccountProperties account;
 private static final String ORIGINAL_SIGNATURE=loadOriginalSignature();
 public PaymentComposer(com.adesa.attendance.config.AccountProperties account) {this.account=account;}
 public String effectiveSignature(String configured) {return configured.isBlank()?ORIGINAL_SIGNATURE:configured;}
 private static String loadOriginalSignature() {
  try(var input=new org.springframework.core.io.ClassPathResource("mail/christian-signature.html").getInputStream()) {
   return new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
  } catch(java.io.IOException ex) {throw new IllegalStateException("Christian's imported signature is missing",ex);}
 }

 public record Message(String from,String to,String cc,String subject,String text,String html,boolean signatureConfigured) {}
 public Message compose(Map<String,Object> config, YearMonth month,String signature) {
  signature=effectiveSignature(signature).replace("{{PUBLIC_URL}}",HtmlUtils.htmlEscape(account.getPublicUrl().replaceAll("/+$","")));
  String amount=new DecimalFormat("#,##0.##",java.text.DecimalFormatSymbols.getInstance(Locale.US)).format(new BigDecimal(config.get("amount").toString()));
  String symbol=config.get("currency").equals("NGN")?"₦":"$";
  Map<String,String> variables=Map.of("amount",symbol+amount,"estimated_ngn",config.get("estimated_ngn").toString(),
    "renewal_day",Objects.toString(config.get("renewal_day"),""),"billing_month",month.toString(),"name",config.get("name").toString());
  String body=config.get("body").toString(), subject=config.get("subject").toString();
  for(var entry:variables.entrySet()) {body=body.replace("{{"+entry.getKey()+"}}",entry.getValue());subject=subject.replace("{{"+entry.getKey()+"}}",entry.getValue());}
  String html="<!doctype html><html><body style=\"font-family:Arial,sans-serif\"><div>"+HtmlUtils.htmlEscape(body).replace("\n","<br>")+"</div>"+
   (signature.isBlank()?"<p>[Christian’s existing Zoho signature has not been configured]</p>":signature)+"</body></html>";
  return new Message("christian@adesahq.com",config.get("to_address").toString(),config.get("cc_addresses").toString(),subject,body,html,!signature.isBlank());
 }
}
