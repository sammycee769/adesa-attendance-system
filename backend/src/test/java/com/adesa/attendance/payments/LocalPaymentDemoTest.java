package com.adesa.attendance.payments;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
class LocalPaymentDemoTest {
 @Test void dedicatedDatabaseAndMockAreMandatoryBeforeMigrations() {
  var config=new LocalPaymentDemo();var env=new MockEnvironment();
  assertThatThrownBy(()->config.paymentLocalMigrations(env)).isInstanceOf(IllegalStateException.class);
  env.withProperty("spring.datasource.url",LocalPaymentDemo.URL).withProperty("adesa.payments.transport","mock");
  assertThat(config.paymentLocalMigrations(env)).isNotNull();
  env.withProperty("adesa.payments.transport","zoho");assertThatThrownBy(()->config.paymentLocalMigrations(env)).isInstanceOf(IllegalStateException.class);
  env.withProperty("adesa.payments.transport","mock").setActiveProfiles("payments-local","prod");
  assertThatThrownBy(()->config.paymentLocalMigrations(env)).isInstanceOf(IllegalStateException.class);
 }
}
