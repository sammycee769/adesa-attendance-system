package com.adesa.attendance.payments;
import com.adesa.attendance.config.AccountProperties;
import org.junit.jupiter.api.Test;
import java.time.YearMonth;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class PaymentSignatureTest {
 @Test void importedZohoSignaturePreservesChristianIdentityAndOriginalLogoLayout() {
  var account=new AccountProperties();account.setPublicUrl("http://localhost:5176");
  var composer=new PaymentComposer(account);
  var config=new HashMap<String,Object>();config.put("amount",20);config.put("currency","USD");config.put("estimated_ngn","₦30,000–₦33,000");config.put("renewal_day",28);config.put("name","ChatGPT Plus Subscription Renewal");config.put("body","Dear Finance Team,\nBest regards,");config.put("subject","Request for Payment");config.put("to_address","finance@adesahq.com");config.put("cc_addresses","femi@adesahq.com");
  var message=composer.compose(config,YearMonth.of(2026,11),"");
  assertThat(message.signatureConfigured()).isTrue();
  assertThat(message.html()).containsOnlyOnce("CHRISTIAN SAMUEL").contains("IT OFFICER","christian@adesahq.com","The content of this email is confidential","http://localhost:5176/assets/email/adesa-hq.png","http://localhost:5176/assets/email/group-headquarters.png","width:650px").doesNotContain("/zm/ImageSignature","{{PUBLIC_URL}}","IT DEPARTMENT");
 }
}
