package com.adesa.attendance.payments;

import com.adesa.attendance.config.MailProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Configuration
class PaymentTransportConfig {
 @Bean
 PaymentTransport paymentTransport(MailProperties properties,
   @Value("${adesa.payments.live-approved:false}") boolean approved,
   @Value("${adesa.payments.transport:mock}") String transport) {
  if ("mock".equals(transport)) return new PaymentTransport() {
   public Receipt deliver(PaymentComposer.Message message) {return new Receipt("mock-"+UUID.randomUUID(),true);}
   public boolean live() {return false;}
  };
  if (!approved || !"zoho".equals(transport) || !properties.getZoho().isConfigured())
   throw new IllegalStateException("Payment live delivery requires explicit approval and authorized Zoho configuration");
  var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
  factory.setReadTimeout(Duration.ofSeconds(30));
  return new ZohoPayments(properties.getZoho(),RestClient.builder().requestFactory(factory).build());
 }
 /** Uses the existing secure OAuth configuration; never exposes tokens to the API. */
 static final class ZohoPayments implements PaymentTransport {
  private final MailProperties.Zoho settings;
  private final RestClient client;
  ZohoPayments(MailProperties.Zoho settings,RestClient client) {this.settings=settings;this.client=client;}
  public boolean live() {return true;}
  public Receipt deliver(PaymentComposer.Message message) {
   if (!message.signatureConfigured()) throw new Rejected(false);
   String token;
   try {
    var form=new LinkedMultiValueMap<String,String>();
    form.add("grant_type","refresh_token");form.add("refresh_token",settings.getRefreshToken());
    form.add("client_id",settings.getClientId());form.add("client_secret",settings.getClientSecret());
    JsonNode response=client.post().uri(settings.getDataCenter().accountsOrigin()+"/oauth/v2/token")
     .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(JsonNode.class);
    token=response==null?"":response.path("access_token").asText("");
    if(token.isBlank()) throw new Rejected(false);
   } catch(Rejected ex) {throw ex;} catch(RuntimeException ex) {throw new Rejected(true);} // No mail submission occurred.
   try {
    JsonNode result=client.post().uri(settings.getDataCenter().mailOrigin()+"/api/accounts/"+settings.getAccountId()+"/messages")
     .header("Authorization","Zoho-oauthtoken "+token).contentType(MediaType.APPLICATION_JSON)
     .body(Map.of("fromAddress",message.from(),"toAddress",message.to(),"ccAddress",message.cc(),
      "subject",message.subject(),"content",message.html(),"mailFormat","html","encoding","UTF-8"))
     .retrieve().body(JsonNode.class);
    if(result==null || result.path("status").path("code").asInt()!=200)
     throw new IllegalStateException("Uncertain provider acceptance");
    String id=result.path("data").path("messageId").asText("");
    if(id.isBlank()) throw new IllegalStateException("Missing provider receipt");
    return new Receipt(id,false);
   } catch(RestClientResponseException ex) {
    int status=ex.getStatusCode().value();
    if(status==429) throw new Rejected(true);
    if(status>=400 && status<500) throw new Rejected(false);
    throw new IllegalStateException("Uncertain provider acceptance");
   }
  }
 }
}
