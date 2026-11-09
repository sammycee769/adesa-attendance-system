package com.adesa.attendance.assets;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class LocalAssetDemoTest {
 @Test void refusesAnyUnverifiedDatabaseBeforeMigrations() {
  var config=new LocalAssetDemo();
  assertThatThrownBy(()->config.localOnlyMigrations(new MockEnvironment())).isInstanceOf(IllegalStateException.class);
  assertThatThrownBy(()->config.localOnlyMigrations(new MockEnvironment().withProperty("spring.datasource.url","jdbc:postgresql://example.invalid/forbidden"))).isInstanceOf(IllegalStateException.class);
 }
 @Test void refusesProductionProfileEvenWithLoopbackDatabase() {
  var env=new MockEnvironment().withProperty("spring.datasource.url",LocalAssetDemo.URL);env.setActiveProfiles("assets-local","prod");
  assertThatThrownBy(()->new LocalAssetDemo().localOnlyMigrations(env)).isInstanceOf(IllegalStateException.class);
 }
 @Test void acceptsOnlyTheDedicatedLocalDatabase() {
  assertThat(new LocalAssetDemo().localOnlyMigrations(new MockEnvironment().withProperty("spring.datasource.url",LocalAssetDemo.URL))).isNotNull();
 }
}
