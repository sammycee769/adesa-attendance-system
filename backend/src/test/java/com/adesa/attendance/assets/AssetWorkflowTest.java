package com.adesa.attendance.assets;

import com.adesa.attendance.employee.*;
import com.adesa.attendance.security.AuthenticatedEmployee;
import com.adesa.attendance.common.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static com.adesa.attendance.assets.AssetRequests.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AssetWorkflowTest {
 @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine");
 @DynamicPropertySource static void db(DynamicPropertyRegistry r) {r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
 @Autowired AssetService service;
 @Autowired AssetQueries queries;
 @Autowired EmployeeRepository employees;
 @Autowired JdbcTemplate jdbc;
 @Autowired MockMvc mvc;
 AuthenticatedEmployee admin,it,employee,other,hr;
 UUID subsidiary;
 @BeforeEach void setup() {
  admin=person(EmployeeRole.ADMIN);it=person(EmployeeRole.IT_ADMIN);employee=person(EmployeeRole.EMPLOYEE);other=person(EmployeeRole.EMPLOYEE);hr=person(EmployeeRole.HR);
  subsidiary=service.subsidiary(new SubsidiaryRequest("Local test subsidiary "+UUID.randomUUID()),admin);
 }
 AuthenticatedEmployee person(EmployeeRole role) {
  String key=UUID.randomUUID().toString(); var e=employees.saveAndFlush(new Employee(key.substring(0,20),"Synthetic "+role,"asset-"+key+"@example.test","unused-test-hash",role,true));
  return new AuthenticatedEmployee(e.getId(),e.getEmail(),e.getFullName(),role);
 }
 Registration registration(String serial) {return new Registration("LAPTOP","Test Brand","Test Model",serial,LocalDate.now(),"GOOD");}
 UUID asset() {return service.register(registration(UUID.randomUUID().toString()),it);}
 UUID assign(UUID asset,AuthenticatedEmployee target) {UUID x=service.assign(new Assign(asset,target.employeeId(),subsidiary,LocalDate.now(),"Synthetic assignment"),it);service.assignmentDecision(x,true,new Decision("Verified"),admin);return x;}
 String assetStatus(UUID id) {return jdbc.queryForObject("select status from it_assets where id=?",String.class,id);}
 @Test void registrationUniqueSerialAndAudit() {
  String serial=UUID.randomUUID().toString();UUID a=service.register(registration(serial),it);
  assertThat(queries.detail(a,it).get("asset_code").toString()).startsWith("ADESA-LAP-");
  assertThatThrownBy(()->service.register(registration(serial.toUpperCase(Locale.ROOT)),it)).isInstanceOf(ConflictException.class);
  assertThat(queries.history(a,it)).extracting(x->x.get("action")).contains("ASSET_CREATED");
  assertThatThrownBy(()->jdbc.update("delete from asset_audit_logs where asset_id=?",a)).isInstanceOf(org.springframework.dao.DataAccessException.class);
 }
 @Test void assignmentApprovalReservesAndRejectReleasesAsset() {
  UUID a=asset(); UUID x=service.assign(new Assign(a,employee.employeeId(),subsidiary,LocalDate.now(),"Pending"),it);
  assertThatThrownBy(()->service.assign(new Assign(a,other.employeeId(),subsidiary,LocalDate.now(),"Duplicate"),it)).isInstanceOf(BusinessRuleException.class);
  service.assignmentDecision(x,false,new Decision("Rejected"),admin);UUID approved=assign(a,employee);
  assertThat(assetStatus(a)).isEqualTo("ASSIGNED");
  assertThatThrownBy(()->service.assignmentDecision(approved,true,new Decision("Repeat"),it)).isInstanceOf(BusinessRuleException.class);
  assertThat(jdbc.queryForObject("select count(*) from asset_assignments where asset_id=?",Integer.class,a)).isEqualTo(2);
 }
 @Test void issueOwnershipAndRepairRestoresAssignment() {
  UUID a=asset();assign(a,employee);
  Fault fault=new Fault(a,"Battery","Synthetic fault","BATTERY","HIGH","Diagnostic");
  assertThatThrownBy(()->service.report(fault,other)).isInstanceOf(ForbiddenException.class);
  UUID issue=service.report(fault,employee);
  assertThat(queries.records("issues",other,"","",a,null,0,20).totalElements()).isZero();
  UUID repair=service.repair(new RepairRequest(a,issue,"Battery replacement","INTERNAL","Local test technician",BigDecimal.TEN,"Test"),it);
  assertThatThrownBy(()->service.repairUpdate(repair,new RepairUpdate("COMPLETED","REPAIR",null,BigDecimal.TEN,"GOOD","Invalid skip"),it)).isInstanceOf(BusinessRuleException.class);
  for(String step:List.of("UNDER_REVIEW","APPROVED","IN_PROGRESS","COMPLETED")) service.repairUpdate(repair,new RepairUpdate(step,"REPAIR","Local technician",BigDecimal.TEN,"GOOD","Verified "+step),it);
  assertThat(assetStatus(a)).isEqualTo("ASSIGNED");
  assertThat(jdbc.queryForObject("select status from asset_issues where id=?",String.class,issue)).isEqualTo("RESOLVED");
  assertThat(queries.history(a,it)).extracting(x->x.get("action")).contains("ISSUE_REPORTED","REPAIR_APPROVED","REPAIR_COMPLETED");
 }
 @Test void replacementRequiresIndependentApprovalExplicitDeviceAndVerifiedReturn() {
  UUID old=asset(),next=asset();UUID assignment=assign(old,employee);
  UUID request=service.replacement(new ReplacementRequest(old,"FAULTY","Synthetic replacement"),employee);
  assertThatThrownBy(()->service.replacement(new ReplacementRequest(old,"FAULTY","Duplicate"),employee)).isInstanceOf(ConflictException.class);
  assertThatThrownBy(()->service.replacementDecision(request,"APPROVED",new Decision("Own request"),employee)).isInstanceOf(ForbiddenException.class);
  service.replacementDecision(request,"APPROVED",new Decision("Approved"),it);
  assertThat(assetStatus(next)).isEqualTo("AVAILABLE");
  assertThatThrownBy(()->service.fulfil(request,new Fulfil(next,subsidiary,"Before return"),it)).isInstanceOf(BusinessRuleException.class);
  UUID ret=service.initiateReturn(new ReturnRequest(assignment,LocalDate.now(),"Return old asset"),it);
  assertThat(assetStatus(old)).isEqualTo("PENDING_REPLACEMENT");
  service.receive(ret,new ReturnReceipt(LocalDate.now(),"GOOD","Physically inspected"),it);
  service.fulfil(request,new Fulfil(next,subsidiary,"Explicitly selected replacement"),it);
  assertThat(assetStatus(next)).isEqualTo("ASSIGNED");assertThat(assetStatus(old)).isEqualTo("AVAILABLE");
  assertThat(jdbc.queryForObject("select status from asset_replacement_requests where id=?",String.class,request)).isEqualTo("FULFILLED");
 }
 @Test void clearanceBlocksUntilReturnAndObligationsResolved() {
  UUID a=asset();UUID x=assign(a,employee);UUID c=service.clearance(new ClearanceRequest(employee.employeeId(),"Exit test"),hr);
  assertThatThrownBy(()->service.clearanceDecision(c,true,new Decision("Premature"),it)).isInstanceOf(BusinessRuleException.class);
  assertThatThrownBy(()->assign(asset(),employee)).isInstanceOf(BusinessRuleException.class);
  UUID ret=service.initiateReturn(new ReturnRequest(x,LocalDate.now(),"Exit"),it);
  assertThat(assetStatus(a)).isEqualTo("ASSIGNED");service.receive(ret,new ReturnReceipt(LocalDate.now(),"DAMAGED","Inspected damage"),it);
  assertThat(assetStatus(a)).isEqualTo("UNDER_REPAIR");
  service.clearanceDecision(c,true,new Decision("All obligations reviewed"),it);
  assertThat(queries.clearance(c,hr).get("status")).isEqualTo("CLEARED");
  assertThat(new String(AssetReports.clearance(queries.clearance(c,hr)),java.nio.charset.StandardCharsets.ISO_8859_1)).startsWith("%PDF");
 }
 @Test void roleBoundariesAndPrivateHistory() throws Exception {
  UUID a=asset();assign(a,employee);
  assertThatThrownBy(()->service.register(registration("denied"),hr)).isInstanceOf(ForbiddenException.class);
  assertThatThrownBy(()->queries.detail(a,other)).isInstanceOf(ForbiddenException.class);
  assertThatThrownBy(()->queries.records("audit-logs",hr,"","",null,null,0,20)).isInstanceOf(ForbiddenException.class);
  assertThatThrownBy(()->queries.report("REPAIR_COSTS",hr)).isInstanceOf(ForbiddenException.class);
  mvc.perform(get("/api/v1/assets").with(authentication(auth(employee)))).andExpect(status().isOk());
  mvc.perform(get("/api/v1/admin/employees").with(authentication(auth(it)))).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/admin/attendance").with(authentication(auth(it)))).andExpect(status().isForbidden());
  mvc.perform(post("/api/v1/assets").with(authentication(auth(employee))).with(csrf()).contentType("application/json").content("{\"assetType\":\"LAPTOP\",\"brand\":\"X\",\"model\":\"Y\",\"serialNumber\":\"Z\",\"purchaseDate\":\"2020-01-01\",\"condition\":\"GOOD\"}")).andExpect(status().isForbidden());
  mvc.perform(post("/api/v1/assets").with(authentication(auth(it))).with(csrf()).contentType("application/json").content("{\"assetType\":\"LAPTOP\",\"brand\":\"\",\"model\":\"Y\",\"serialNumber\":\"Z\",\"purchaseDate\":\"2099-01-01\",\"condition\":\"GOOD\"}")).andExpect(status().isBadRequest());
 }
 UsernamePasswordAuthenticationToken auth(AuthenticatedEmployee p) {return new UsernamePasswordAuthenticationToken(p,null,List.of(new SimpleGrantedAuthority("ROLE_"+p.role().name())));}
 @Test void concurrentAssignmentHasExactlyOneWinner() throws Exception {
  UUID a=asset(); CountDownLatch gate=new CountDownLatch(1);
  try(var executor=Executors.newFixedThreadPool(2)) {
   var jobs=new ArrayList<Future<Boolean>>();for(var person:List.of(employee,other)) jobs.add(executor.submit(()->{gate.await();try{service.assign(new Assign(a,person.employeeId(),subsidiary,LocalDate.now(),"Concurrent test"),it);return true;}catch(BusinessRuleException|DataIntegrityViolationException|ConflictException e){return false;}}));
   gate.countDown();int winners=0;for(var job:jobs)if(job.get(20,TimeUnit.SECONDS))winners++;assertThat(winners).isEqualTo(1);
  }
  assertThat(jdbc.queryForObject("select count(*) from asset_assignments where asset_id=? and status='ACTIVE'",Integer.class,a)).isEqualTo(1);
 }
 @Test void formerOwnerCannotSeeCurrentEmployeeAndAdminCanAccountForLoss() {
  UUID a=asset();UUID first=assign(a,employee);
  UUID ret=service.initiateReturn(new ReturnRequest(first,LocalDate.now(),"Return before reassignment"),it);
  service.receive(ret,new ReturnReceipt(LocalDate.now(),"GOOD","Inspected"),it);
  assign(a,other);
  var personal=queries.inventory(employee,"","","","",null,0,100,"assetCode","asc").content();
  assertThat(personal).filteredOn(row->row.get("id").equals(a)).allSatisfy(row->{assertThat(row.get("employee_name")).isNull();assertThat(row.get("employee_email")).isNull();});
  assertThatThrownBy(()->service.accountLoss(a,new Decision("Unapproved loss"),it)).isInstanceOf(ForbiddenException.class);
  UUID replacement=service.replacement(new ReplacementRequest(a,"OTHER","Lost device replacement"),other);
  service.accountLoss(a,new Decision("Administrator verified and formally accounted for loss"),admin);
  assertThat(assetStatus(a)).isEqualTo("LOST");
  service.replacementDecision(replacement,"APPROVED",new Decision("Replacement after documented loss"),it);
  UUID next=asset();service.fulfil(replacement,new Fulfil(next,subsidiary,"Explicit replacement selection"),it);
  assertThat(assetStatus(next)).isEqualTo("ASSIGNED");assertThat(assetStatus(a)).isEqualTo("LOST");
 }
 @Test void outstandingIssuesBlockClearanceEvenAfterReturn() {
  UUID a=asset();UUID x=assign(a,employee);UUID issue=service.report(new Fault(a,"Network fault","Synthetic issue","NETWORK","LOW",null),employee);
  UUID ret=service.initiateReturn(new ReturnRequest(x,LocalDate.now(),"Return"),it);service.receive(ret,new ReturnReceipt(LocalDate.now(),"GOOD","Inspected"),it);
  UUID c=service.clearance(new ClearanceRequest(employee.employeeId(),"Exit"),hr);
  assertThatThrownBy(()->service.clearanceDecision(c,true,new Decision("Blocked by issue"),it)).isInstanceOf(BusinessRuleException.class);
  service.issue(issue,new IssueUpdate("RESOLVED","LOW","IT accepted and resolved the obligation"),it);
  service.clearanceDecision(c,true,new Decision("All obligations resolved"),it);
 }
 @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
 @Test void employeesRequestOnlyAvailableAssetsAndApprovalAssignsWithQueuedEmails() throws Exception {
  UUID a=asset();String code=jdbc.queryForObject("select asset_code from it_assets where id=?",String.class,a);
  assertThat(queries.availableAssets(code,0,20).content()).hasSize(1).allSatisfy(row->assertThat(row).doesNotContainKeys("employee_email","serial_number","created_by"));
  UUID request=service.requestAsset(new DeviceRequestInput(a,subsidiary,"Synthetic employee needs a laptop"),employee);
  assertThat(queries.availableAssets(code,0,20).content()).isEmpty();
  assertThat(queries.records("requests",other,"","",null,null,0,20).content()).isEmpty();
  assertThatThrownBy(()->service.requestAsset(new DeviceRequestInput(a,subsidiary,"Competing request"),other)).isInstanceOf(BusinessRuleException.class);
  assertThatThrownBy(()->service.assign(new Assign(a,other.employeeId(),subsidiary,LocalDate.now(),"IT cannot bypass reservation"),it)).isInstanceOf(BusinessRuleException.class);
  assertThatThrownBy(()->service.requestDecision(request,true,new Decision("Employee cannot approve"),employee)).isInstanceOf(ForbiddenException.class);
  service.requestDecision(request,true,new Decision("IT approved laptop request"),it);
  assertThat(assetStatus(a)).isEqualTo("ASSIGNED");
  assertThat(jdbc.queryForObject("select count(*) from asset_assignments where asset_id=? and employee_id=? and status='ACTIVE' and approval_status='APPROVED'",Integer.class,a,employee.employeeId())).isEqualTo(1);
  for(String event:List.of("ASSET_REQUEST_SUBMITTED","ASSET_REQUEST_APPROVED")) {
   var recipients=jdbc.queryForList("select recipient_email from asset_mail_outbox where request_id=? and event_type=?",String.class,request,event);
   assertThat(recipients).contains(employee.email(),it.email(),admin.email()).doesNotContain(other.email(),hr.email());
  }
  int count=jdbc.queryForObject("select count(*) from asset_mail_outbox where request_id=?",Integer.class,request);
  assertThatThrownBy(()->service.requestDecision(request,true,new Decision("Duplicate approval"),it)).isInstanceOf(BusinessRuleException.class);
  assertThat(jdbc.queryForObject("select count(*) from asset_mail_outbox where request_id=?",Integer.class,request)).isEqualTo(count);
  mvc.perform(get("/api/v1/assets/available").with(authentication(auth(employee)))).andExpect(status().isOk());
 }
 @Test void rejectingARequestReleasesReservationAndUnavailableAssetsCannotBeRequested() {
  UUID a=asset();UUID request=service.requestAsset(new DeviceRequestInput(a,subsidiary,"Synthetic request"),employee);
  service.requestDecision(request,false,new Decision("Not required"),it);
  assertThat(queries.availableAssets("",0,100).content()).anySatisfy(row->assertThat(row.get("id")).isEqualTo(a));
  assign(a,other);
  assertThatThrownBy(()->service.requestAsset(new DeviceRequestInput(a,subsidiary,"Already assigned"),employee)).isInstanceOf(BusinessRuleException.class);
  UUID repairAsset=asset();service.repair(new RepairRequest(repairAsset,null,"Assessment","INTERNAL",null,null,null),it);
  assertThatThrownBy(()->service.requestAsset(new DeviceRequestInput(repairAsset,subsidiary,"Open repair"),employee)).isInstanceOf(BusinessRuleException.class);
 }
 @Test void repairAndReplacementRequestsNotifyEmployeeAndItAtSubmissionAndApproval() {
  UUID a=asset();assign(a,employee);
  assertThatThrownBy(()->service.requestRepair(new EmployeeRepairRequest(a,"Not my device",null),other)).isInstanceOf(ForbiddenException.class);
  UUID repair=service.requestRepair(new EmployeeRepairRequest(a,"Synthetic fault","Employee repair notes"),employee);
  assertThat(queries.records("repairs",employee,"","",a,null,0,20).content()).hasSize(1).allSatisfy(row->assertThat(row).doesNotContainKeys("actual_cost","estimated_cost","technician"));
  assertThat(queries.records("repairs",other,"","",a,null,0,20).content()).isEmpty();
  service.repairUpdate(repair,new RepairUpdate("UNDER_REVIEW",null,null,null,null,"Review"),it);
  service.repairUpdate(repair,new RepairUpdate("APPROVED","REPAIR",null,null,null,"Approved"),it);
  UUID replacement=service.replacement(new ReplacementRequest(a,"FAULTY","Synthetic replacement"),employee);
  service.replacementDecision(replacement,"APPROVED",new Decision("Approved replacement"),it);
  for(var entry:Map.of(repair,List.of("REPAIR_REQUEST_SUBMITTED","REPAIR_REQUEST_APPROVED"),replacement,List.of("REPLACEMENT_REQUEST_SUBMITTED","REPLACEMENT_REQUEST_APPROVED")).entrySet())
   for(String event:entry.getValue()) assertThat(jdbc.queryForList("select recipient_email from asset_mail_outbox where request_id=? and event_type=?",String.class,entry.getKey(),event)).contains(employee.email(),it.email(),admin.email());
 }
 @Test void rolledBackRequestsProduceNeitherReservationNorEmail() {
  UUID a=asset();var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
  assertThatThrownBy(()->tx.execute(status->{service.requestAsset(new DeviceRequestInput(a,subsidiary,"Rollback"),employee);throw new IllegalStateException("Synthetic rollback");})).isInstanceOf(IllegalStateException.class);
  assertThat(jdbc.queryForObject("select count(*) from asset_requests where asset_id=?",Integer.class,a)).isZero();
  assertThat(jdbc.queryForObject("select count(*) from asset_mail_outbox where asset_id=?",Integer.class,a)).isZero();
 }
 @Test void concurrentEmployeeRequestsHaveOneWinner() throws Exception {
  UUID a=asset();CountDownLatch gate=new CountDownLatch(1);
  try(var executor=Executors.newFixedThreadPool(2)) {
   var jobs=new ArrayList<Future<Boolean>>();for(var person:List.of(employee,other)) jobs.add(executor.submit(()->{gate.await();try{service.requestAsset(new DeviceRequestInput(a,subsidiary,"Concurrent request"),person);return true;}catch(BusinessRuleException|ConflictException ex){return false;}}));
   gate.countDown();int winners=0;for(var job:jobs)if(job.get(20,TimeUnit.SECONDS))winners++;assertThat(winners).isEqualTo(1);
  }
 }
 @Test void queryEndpointsPaginationAndExportsUseDatabaseRecords() throws Exception {
  UUID a=asset();assign(a,employee);
  assertThat(queries.inventory(it,"Test Brand","","","",null,0,1,"assetCode","asc").content()).hasSize(1);
  assertThat(queries.dashboard(it)).containsKeys("byType","bySubsidiary","monthlyRepairs","recentActivity");
  for(String kind:List.of("assignments","issues","repairs","replacements","returns","clearance","audit-logs")) assertThat(queries.records(kind,it,"","",null,null,0,20)).isNotNull();
  for(String name:List.of("INVENTORY","BY_EMPLOYEE","BY_SUBSIDIARY","AVAILABLE","DAMAGED","UNDER_REPAIR","REPAIR_COSTS","PENDING_REPLACEMENTS","OUTSTANDING_RETURNS","CLEARANCE")) assertThat(queries.report(name,it)).isNotNull();
  mvc.perform(get("/api/v1/assets/reports/export?format=csv").with(authentication(auth(it)))).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/csv"));
  assertThat(new String(AssetReports.csv(List.of(Map.of("cell","=formula"))))).contains("'=formula");
 }
}
