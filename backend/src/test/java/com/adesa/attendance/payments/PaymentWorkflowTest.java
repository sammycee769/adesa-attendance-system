package com.adesa.attendance.payments;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"adesa.payments.initial-delay-ms=3600000","adesa.payments.transport=mock","adesa.payments.live-approved=false","adesa.mail.host=","adesa.assets.mail.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class PaymentWorkflowTest {
 @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine");
 @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
 @Autowired PaymentService service;
 @Autowired PaymentDispatcher worker;
 @Autowired MockMvc mvc;
 @MockitoBean PaymentTransport transport;
 final UUID zoho=UUID.fromString("90000000-0000-0000-0000-000000000001");
 final UUID chatgpt=UUID.fromString("90000000-0000-0000-0000-000000000003");
 final YearMonth month=YearMonth.of(2026,11);
 final Instant due=Instant.parse("2026-11-16T08:00:00Z");
 @BeforeEach void resetData(){
  service.db.update("delete from payment_delivery_attempts");service.db.update("delete from payment_requests");service.db.update("delete from payment_audit");service.db.update("delete from payment_configs where code like 'CUSTOM_%'");
  service.db.update("update payment_automation set enabled=false,signature_html=''");
  service.db.update("update payment_configs set enabled=true,scheduled_day=case code when 'ZOHO' then 16 when 'DATA' then 20 else 28 end");
  service.db.update("insert into employees(id,employee_number,full_name,email,password_hash,role,is_active) values('91000000-0000-0000-0000-000000000001','PAYMENT-OWNER','Synthetic Christian','christian@adesahq.com','unused','EMPLOYEE',true) on conflict do nothing");
  service.db.update("update payment_configs set owner_employee_id='91000000-0000-0000-0000-000000000001'");
  reset(transport);when(transport.live()).thenReturn(false);
  when(transport.deliver(any())).thenReturn(new PaymentTransport.Receipt("mock-receipt",true));
 }
 @Test void seedsAndDefaults() {
  var rows=service.configurations(due);
  assertThat(rows).hasSize(3);
  assertThat(rows).extracting(r->r.get("amount").toString()).containsExactly("72842.00","25000.00","20.00");
  assertThat(rows).extracting(r->r.get("currency")).containsExactly("NGN","NGN","USD");
  assertThat(rows).extracting(r->r.get("scheduled_day")).containsExactly(16,20,28);
  assertThat(rows.getLast().get("renewal_day")).isEqualTo(28);
  assertThat(service.settings().get("enabled")).isEqualTo(false);
  worker.tick(due);assertThat(service.history()).isEmpty();verify(transport,never()).deliver(any());
 }
 @Test void correctMessagesRecipientsAndSignatureEscaping() {
  String signature="<div id=\"original-signature\"><img src=\"https://www.adesahq.com/logo.png\">Original signature. Confidential.</div>";
  service.settings(false,signature,"admin");
  for(var config:service.configurations(due)) {
   var message=service.preview((UUID)config.get("id"),month);
   assertThat(message.from()).isEqualTo("christian@adesahq.com");
   assertThat(message.to()).isEqualTo("Finance Manager <finance@adesahq.com>");
   assertThat(message.cc()).isEqualTo("Oluwafemi Adeleye <femi@adesahq.com>, Mercy Agbo <payment@adesahq.com>, Blessing Gbadeyan <blessing@adesahq.com>");
   assertThat(message.subject()).startsWith("Request for Payment – ");
   assertThat(message.text()).startsWith("Dear Finance Team,").endsWith("Best regards,").doesNotContain("{{");
   assertThat(message.html()).containsOnlyOnce(signature).containsOnlyOnce("Best regards,");
  }
  assertThat(service.preview(zoho,month).text()).contains("₦72,842","company's Zoho subscription","Kindly process the payment promptly");
  assertThat(service.preview(UUID.fromString("90000000-0000-0000-0000-000000000002"),month).text()).contains("₦25,000","company's monthly internet data subscription");
  assertThat(service.preview(chatgpt,month).text()).contains("$20","28th","₦30,000–₦33,000").doesNotContain("30th");
  var config=service.config(zoho);config.put("body","<script>alert('x')</script> {{name}}\nBest regards,");config.put("name","<b>ADESA</b>");
  assertThat(service.composer.compose(config,month,signature).html()).contains("&lt;script&gt;","&lt;b&gt;ADESA&lt;/b&gt;");
 }
 @Test void duplicateGenerationAndConcurrentScheduler() throws Exception {
  UUID id=service.generate(zoho,month,"admin");assertThat(service.generate(zoho,month,"admin")).isEqualTo(id);
  assertThat(service.history()).hasSize(1);verify(transport,never()).deliver(any());
  service.settings(true,"","admin");service.recover(id,"admin","Local test",due);
  try(var pool=Executors.newFixedThreadPool(4)){
   var futures=new ArrayList<Future<?>>();for(int i=0;i<4;i++)futures.add(pool.submit(()->worker.tick(due)));
   for(var future:futures)future.get(20,TimeUnit.SECONDS);
  }
  assertThat(service.history()).hasSize(1);assertThat(service.attempts()).hasSize(1);
  verify(transport,times(1)).deliver(any());assertThat(service.history().getFirst().get("status")).isEqualTo("MOCK_CAPTURED");
  new PaymentDispatcher(service).tick(due.plusSeconds(60));verify(transport,times(1)).deliver(any());
 }
 @Test void beforeNineWeekendAndMissedRequests() {
  service.settings(true,"","admin");worker.tick(due.minusSeconds(1));assertThat(service.history()).isEmpty();
  worker.tick(Instant.parse("2026-11-27T08:00:00Z"));
  var rows=service.history();assertThat(rows).hasSize(3);
  assertThat(rows).filteredOn(r->r.get("config_id").equals(chatgpt)).allSatisfy(r->{assertThat(r.get("status")).isEqualTo("MOCK_CAPTURED");assertThat(r.get("send_date").toString()).isEqualTo("2026-11-27");});
  assertThat(rows).filteredOn(r->!r.get("config_id").equals(chatgpt)).allSatisfy(r->assertThat(r.get("status")).isEqualTo("MISSED"));
  verify(transport,times(1)).deliver(any());
 }
 @Test void controlledRetriesAndRestartSafety() {
  service.settings(true,"","admin");
  when(transport.deliver(any())).thenThrow(new PaymentTransport.Rejected(true));
  worker.tick(due);assertThat(service.history().getFirst().get("status")).isEqualTo("RETRY");
  new PaymentDispatcher(service).tick(due.plusSeconds(1));verify(transport,times(1)).deliver(any());
  worker.tick(due.plusSeconds(120));worker.tick(due.plusSeconds(360));
  assertThat(service.history().getFirst().get("status")).isEqualTo("FAILED");assertThat(service.attempts()).hasSize(3);
  worker.tick(due.plusSeconds(1000));verify(transport,times(3)).deliver(any());
 }
 @Test void uncertainDeliveryNeverAutomaticallyRetriedAndCanBeReconciled() {
  service.settings(true,"","admin");when(transport.deliver(any())).thenThrow(new IllegalStateException("timeout"));
  worker.tick(due);worker.tick(due.plusSeconds(1000));verify(transport,times(1)).deliver(any());
  var row=service.history().getFirst();UUID id=(UUID)row.get("id");assertThat(row.get("status")).isEqualTo("UNKNOWN");
  assertThatThrownBy(()->service.recover(id,"admin","unsafe",due)).isInstanceOf(com.adesa.attendance.common.ConflictException.class);
  service.reconcile(id,true,"zoho-verified-id","Provider confirmed acceptance","admin");
  assertThat(service.history().getFirst().get("status")).isEqualTo("SENT");
 }
 @Test void staleInFlightAfterCrashBecomesUnknown() {
  service.settings(true,"","admin");var id=service.generate(zoho,month,"admin");
  service.db.update("update payment_requests set status='SENDING',attempts=1,updated_at=? where id=?",java.sql.Timestamp.from(due.minusSeconds(601)),id);
  service.db.update("insert into payment_delivery_attempts(id,request_id,attempt,started_at,result) values(?,?,1,?,'SENDING')",UUID.randomUUID(),id,java.sql.Timestamp.from(due.minusSeconds(601)));
  worker.tick(due);assertThat(service.history().getFirst().get("status")).isEqualTo("UNKNOWN");
  assertThat(service.attempts().getFirst().get("result")).isEqualTo("UNKNOWN");verify(transport,never()).deliver(any());
 }
 @Test void retryExpiresAtMidnightAndRecoveryRejectsOldMonths() {
  service.settings(true,"","admin");when(transport.deliver(any())).thenThrow(new PaymentTransport.Rejected(true));worker.tick(due);
  worker.tick(Instant.parse("2026-11-17T00:00:00Z"));assertThat(service.history().getFirst().get("status")).isEqualTo("MISSED");
  UUID id=(UUID)service.history().getFirst().get("id");
  assertThatThrownBy(()->service.recover(id,"admin","Outdated",Instant.parse("2026-12-01T00:00:00Z"))).isInstanceOf(com.adesa.attendance.common.ConflictException.class);
  service.recover(id,"admin","Finance recovery",due.plusSeconds(86400));
  doReturn(new PaymentTransport.Receipt("mock-recovered",true)).when(transport).deliver(any());worker.tick(due.plusSeconds(86400));
  assertThat(service.history().getFirst().get("status")).isEqualTo("MOCK_CAPTURED");
 }
 @Test void disabledScheduleNeverSends() {
  service.settings(true,"","admin");service.db.update("update payment_configs set enabled=false where id=?",zoho);
  worker.tick(due);assertThat(service.history()).isEmpty();verify(transport,never()).deliver(any());
 }
 @Test void adminAuthorizationAndCsrf() throws Exception {
  String path="/api/v1/admin/recurring-payments";
  mvc.perform(get(path)).andExpect(status().isUnauthorized());
  for(String role:List.of("EMPLOYEE","HR","IT_ADMIN"))mvc.perform(get(path).with(user("synthetic").roles(role))).andExpect(status().isForbidden());
  mvc.perform(get(path).with(user("synthetic").roles("ADMIN"))).andExpect(status().isOk());
  mvc.perform(put(path+"/settings").with(user("synthetic").roles("ADMIN")).contentType("application/json").content("{\"enabled\":true,\"signatureHtml\":\"\"}")).andExpect(status().isForbidden());
  mvc.perform(put(path+"/settings").with(user("synthetic").roles("ADMIN")).with(csrf()).contentType("application/json").content("{\"enabled\":false,\"signatureHtml\":\"\"}")).andExpect(status().isOk());
 }
 @Test void rejectsPrivateSignatureAndInvalidTemplates() {
  assertThatThrownBy(()->service.settings(false,"<img src=\"/zm/ImageSignature?x\">","admin")).isInstanceOf(com.adesa.attendance.common.BadRequestException.class);
  assertThatThrownBy(()->service.settings(false,"<script>alert(1)</script>","admin")).isInstanceOf(com.adesa.attendance.common.BadRequestException.class);
 }
 @Test void previewDoesNotBlockScheduledDelivery() {
  var id=service.generate(zoho,month,"admin");service.settings(true,"","admin");worker.tick(due);
  assertThat(service.history()).hasSize(1);assertThat(service.history().getFirst().get("id")).isEqualTo(id);
  assertThat(service.history().getFirst().get("status")).isEqualTo("MOCK_CAPTURED");verify(transport,times(1)).deliver(any());
 }
 @Test void directDatabaseDuplicateAndSeedIdempotenceAreEnforced() throws Exception {
  var id=service.generate(zoho,month,"admin");
  assertThatThrownBy(()->service.db.update("insert into payment_requests(id,config_id,billing_month,scheduled_date,send_date,status,snapshot) select ?,config_id,billing_month,scheduled_date,send_date,status,snapshot from payment_requests where id=?",UUID.randomUUID(),id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  String migration=new String(new org.springframework.core.io.ClassPathResource("db/migration/V9__recurring_payments.sql").getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
  service.db.execute(migration.substring(migration.indexOf("INSERT INTO payment_configs")));
  assertThat(service.configurations(due)).hasSize(3);
 }
 @Test void validationAndOptimisticConcurrency() throws Exception {
  String path="/api/v1/admin/recurring-payments/"+zoho;
  mvc.perform(put(path).with(user("synthetic").roles("ADMIN")).with(csrf()).contentType("application/json").content("{\"name\":\"\",\"amount\":-1,\"scheduledDay\":30}" )).andExpect(status().isBadRequest());
  var config=service.config(zoho);
  var input=new PaymentInput(config.get("name").toString(),new java.math.BigDecimal("72842"),"NGN","",16,null,"",config.get("to_address").toString(),config.get("cc_addresses").toString(),config.get("subject").toString(),config.get("body").toString(),(UUID)config.get("owner_employee_id"),true,(Integer)config.get("version"));
  service.save(zoho,input,"admin");
  assertThatThrownBy(()->service.save(zoho,input,"admin")).isInstanceOf(com.adesa.attendance.common.ConflictException.class);
  assertThat(service.audits()).extracting(r->r.get("action")).contains("CONFIG_UPDATED");
 }
 @Test void recoveryAndSchedulerSupportNextMonthsFridayAcrossMonthBoundary() {
  service.db.update("update payment_configs set scheduled_day=1 where id=?",zoho);
  YearMonth next=YearMonth.of(2027,5);Instant friday=Instant.parse("2027-04-30T08:00:00Z");
  UUID id=service.generate(zoho,next,"admin");service.settings(true,"","admin");
  service.recover(id,"admin","Weekend-adjusted next month request",friday);worker.tick(friday);
  assertThat(service.history()).filteredOn(r->r.get("id").equals(id)).allSatisfy(r->{assertThat(r.get("status")).isEqualTo("MOCK_CAPTURED");assertThat(r.get("send_date").toString()).isEqualTo("2027-04-30");});
  assertThat(PaymentCalendar.next(friday.plusSeconds(1),1).toLocalDate()).isEqualTo(LocalDate.of(2027,6,1));
 }
 @Test void employeeViewsOnlyAssignedSchedulesHistoryAndPreviews() throws Exception {
  var owner=new com.adesa.attendance.security.AuthenticatedEmployee(UUID.fromString("91000000-0000-0000-0000-000000000001"),"christian@adesahq.com","Synthetic Christian",com.adesa.attendance.employee.EmployeeRole.EMPLOYEE);
  var other=new com.adesa.attendance.security.AuthenticatedEmployee(UUID.randomUUID(),"other@example.test","Other Employee",com.adesa.attendance.employee.EmployeeRole.EMPLOYEE);
  service.generate(zoho,month,"admin");
  assertThat((List<?>)service.employeeDashboard(owner,due).get("payments")).hasSize(3);
  assertThat((List<?>)service.employeeDashboard(owner,due).get("history")).hasSize(1);
  assertThat((List<?>)service.employeeDashboard(other,due).get("payments")).isEmpty();
  assertThat((List<?>)service.employeeDashboard(other,due).get("history")).isEmpty();
  assertThat((List<?>)service.employeeDashboard(other,due).get("employees")).isEmpty();
  var ownerAuth=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(owner,null,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_EMPLOYEE")));
  var otherAuth=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(other,null,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_EMPLOYEE")));
  mvc.perform(get("/api/v1/recurring-payments").with(authentication(ownerAuth))).andExpect(status().isOk());
  mvc.perform(post("/api/v1/recurring-payments/"+zoho+"/preview").with(authentication(ownerAuth)).with(csrf()).contentType("application/json").content("{\"month\":\"2026-11\"}")).andExpect(status().isOk());
  mvc.perform(post("/api/v1/recurring-payments/"+zoho+"/preview").with(authentication(otherAuth)).with(csrf()).contentType("application/json").content("{\"month\":\"2026-11\"}")).andExpect(status().isForbidden());
  mvc.perform(post("/api/v1/admin/recurring-payments").with(authentication(ownerAuth)).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
 }
 @Test void adminCanCreateAnAssignedScheduleAndUnassignedSchedulesCannotSend() {
  UUID owner=UUID.fromString("91000000-0000-0000-0000-000000000001");
  var input=new PaymentInput("Custom payment",java.math.BigDecimal.TEN,"NGN","",16,null,"Monthly test","finance@adesahq.com","","Payment request","Dear Finance Team,\nBest regards,",owner,false,0);
  UUID id=service.create(input,"admin");assertThat(service.config(id).get("owner_employee_id")).isEqualTo(owner);
  service.settings(true,"","admin");service.db.update("update payment_configs set owner_employee_id=null");worker.tick(due);
  assertThat(service.history()).isEmpty();verify(transport,never()).deliver(any());
 }
}
