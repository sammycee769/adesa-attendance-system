package com.adesa.attendance.payments;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class PaymentProductionModeTest {
 private MockEnvironment environment(boolean production) throws IOException {
  var env=new MockEnvironment();var loader=new YamlPropertySourceLoader();
  for(var source:loader.load("defaults",new ClassPathResource("application.yml")))env.getPropertySources().addLast(source);
  if(production)for(var source:loader.load("production",new ClassPathResource("application-prod.yml")))env.getPropertySources().addFirst(source);
  return env;
 }
 @Test void localAndTestDefaultsRemainMock() throws IOException {
  var env=environment(false);
  assertThat(env.getProperty("adesa.payments.transport")).isEqualTo("mock");
  assertThat(env.getProperty("adesa.payments.live-approved",Boolean.class)).isFalse();
 }
 @Test void productionSelectsOwnerApprovedLiveDelivery() throws IOException {
  var env=environment(true);
  assertThat(env.getProperty("adesa.payments.transport")).isEqualTo("zoho");
  assertThat(env.getProperty("adesa.payments.live-approved",Boolean.class)).isTrue();
 }
 @Test void operatorCanStillExplicitlyDisableLiveDelivery() throws IOException {
  var env=environment(true).withProperty("PAYMENTS_TRANSPORT","mock").withProperty("PAYMENTS_LIVE_APPROVED","false");
  assertThat(env.getProperty("adesa.payments.transport")).isEqualTo("mock");
  assertThat(env.getProperty("adesa.payments.live-approved",Boolean.class)).isFalse();
 }
}
