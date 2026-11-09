package com.adesa.attendance.payments;
import com.adesa.attendance.config.MailProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.http.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
class PaymentTransportTest {
 MailProperties properties() {
  var p=new MailProperties();var z=p.getZoho();z.setDataCenter(MailProperties.ZohoDataCenter.US);z.setAccountId("123");z.setClientId("synthetic-client");z.setClientSecret("synthetic-secret");z.setRefreshToken("synthetic-refresh");return p;
 }
 PaymentComposer.Message message(){return new PaymentComposer.Message("christian@adesahq.com","finance@adesahq.com","femi@adesahq.com,payment@adesahq.com,blessing@adesahq.com","Request for Payment","Best regards,","<p>Original signature</p>",true);}
 @Test void defaultsCannotContactZohoAndApprovalIsRequired() {
  var config=new PaymentTransportConfig();var transport=config.paymentTransport(properties(),false,"mock");
  assertThat(transport.live()).isFalse();assertThat(transport.deliver(message()).mock()).isTrue();
  assertThatThrownBy(()->config.paymentTransport(properties(),false,"zoho")).isInstanceOf(IllegalStateException.class);
 }
 @Test void zohoPayloadAndReceiptAreCapturedWithoutNetwork() {
  var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
  server.expect(requestTo("https://accounts.zoho.com/oauth/v2/token")).andRespond(withSuccess("{\"access_token\":\"synthetic-token\"}",MediaType.APPLICATION_JSON));
  server.expect(requestTo("https://mail.zoho.com/api/accounts/123/messages")).andExpect(method(HttpMethod.POST))
   .andExpect(header("Authorization","Zoho-oauthtoken synthetic-token"))
   .andExpect(content().json("{\"fromAddress\":\"christian@adesahq.com\",\"toAddress\":\"finance@adesahq.com\",\"ccAddress\":\"femi@adesahq.com,payment@adesahq.com,blessing@adesahq.com\",\"mailFormat\":\"html\"}"))
   .andRespond(withSuccess("{\"status\":{\"code\":200},\"data\":{\"messageId\":\"provider-id-123\"}}",MediaType.APPLICATION_JSON));
  var transport=new PaymentTransportConfig.ZohoPayments(properties().getZoho(),builder.build());
  assertThat(transport.deliver(message()).messageId()).isEqualTo("provider-id-123");server.verify();
 }
 @Test void confirmedRateLimitIsRetryableButServerFailureIsUncertain() {
  for(HttpStatus status:new HttpStatus[]{HttpStatus.TOO_MANY_REQUESTS,HttpStatus.INTERNAL_SERVER_ERROR}) {
   var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
   server.expect(requestTo("https://accounts.zoho.com/oauth/v2/token")).andRespond(withSuccess("{\"access_token\":\"synthetic-token\"}",MediaType.APPLICATION_JSON));
   server.expect(requestTo("https://mail.zoho.com/api/accounts/123/messages")).andRespond(withStatus(status));
   var transport=new PaymentTransportConfig.ZohoPayments(properties().getZoho(),builder.build());
   if(status==HttpStatus.TOO_MANY_REQUESTS)assertThatThrownBy(()->transport.deliver(message())).isInstanceOfSatisfying(PaymentTransport.Rejected.class,ex->assertThat(ex.retryable).isTrue());
   else assertThatThrownBy(()->transport.deliver(message())).isInstanceOf(IllegalStateException.class).isNotInstanceOf(PaymentTransport.Rejected.class);
   server.verify();
  }
 }
}
