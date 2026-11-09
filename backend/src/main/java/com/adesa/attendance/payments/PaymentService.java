package com.adesa.attendance.payments;

import com.adesa.attendance.common.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.internet.InternetAddress;
import java.sql.Date;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Service
public class PaymentService {
 final JdbcTemplate db;
 final TransactionTemplate tx;
 final PaymentComposer composer;
 final ObjectMapper json;
 final PaymentTransport transport;
 public PaymentService(JdbcTemplate db,PlatformTransactionManager manager,PaymentComposer composer,ObjectMapper json,PaymentTransport transport) {
  this.db=db;this.tx=new TransactionTemplate(manager);this.composer=composer;this.json=json;this.transport=transport;
 }
 public List<Map<String,Object>> configurations(Instant now) {
  var result=db.queryForList("select c.*, e.full_name owner_name,e.email owner_email, (select max(sent_at) from payment_requests r where r.config_id=c.id and status='SENT') last_successful_send from payment_configs c left join employees e on e.id=c.owner_employee_id order by scheduled_day");
  YearMonth month=YearMonth.from(now.atZone(PaymentCalendar.ZONE));
  for(var row:result) {
   int day=(Integer)row.get("scheduled_day");
   row.put("scheduled_date",month.atDay(day));row.put("adjusted_date",PaymentCalendar.adjusted(month,day));
   row.put("next_request_date",PaymentCalendar.next(now,day));
   row.put("renewal_date",row.get("renewal_day")==null?null:month.atDay((Integer)row.get("renewal_day")));
   row.put("upcoming",java.util.stream.IntStream.range(0,3).mapToObj(i->PaymentCalendar.due(month.plusMonths(i),day)).toList());
  }
  return result;
 }
 Map<String,Object> config(UUID id) {
  var rows=db.queryForList("select * from payment_configs where id=?",id);
  if(rows.isEmpty()) throw new NotFoundException("Payment configuration not found");return rows.getFirst();
 }
 public Map<String,Object> settings() {
  var settings=db.queryForMap("select * from payment_automation where id=1");
  settings.put("signature_html",composer.effectiveSignature(settings.get("signature_html").toString()));
  settings.put("transport",transport.live()?"zoho":"mock");settings.put("sender","christian@adesahq.com");
  settings.put("timezone","Africa/Lagos");settings.put("sending_time","09:00");return settings;
 }
 void audit(String actor,String action,Object target,String detail) {
  db.update("insert into payment_audit(actor,action,target,detail) values(?,?,?,?)",actor,action,target.toString(),detail);
 }
 public void save(UUID id,PaymentInput input,String actor) {
  if(input.ownerEmployeeId()!=null && !Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from employees where id=? and is_active=true)",Boolean.class,input.ownerEmployeeId())))
   throw new BadRequestException("Choose an active employee");
  addresses(input.toAddress(),false);addresses(input.ccAddresses(),true);
  if(input.subject().contains("\n")||input.subject().contains("\r")) throw new BadRequestException("Subject must be a single line");
  if(!input.body().stripTrailing().endsWith("Best regards,")) throw new BadRequestException("Email body must end with Best regards,");
  validateVariables(input.body());validateVariables(input.subject());
  if(input.name().contains("\n")||input.name().contains("\r"))throw new BadRequestException("Name must be a single line");
  if(input.body().contains("{{renewal_day}}")&&input.renewalDay()==null)throw new BadRequestException("This template requires a renewal day");
  tx.executeWithoutResult(s->{
   int updated=db.update("update payment_configs set name=?,amount=?,currency=?,estimated_ngn=?,scheduled_day=?,renewal_day=?,description=?,to_address=?,cc_addresses=?,subject=?,body=?,owner_employee_id=?,enabled=?,version=version+1 where id=? and version=?",
    input.name(),input.amount(),input.currency(),input.estimatedNgn(),input.scheduledDay(),input.renewalDay(),input.description(),input.toAddress(),input.ccAddresses(),input.subject(),input.body(),input.ownerEmployeeId(),input.enabled(),id,input.version());
   if(updated!=1) throw new ConflictException("Configuration changed. Reload before saving.");
   db.update("update payment_requests set status='MISSED',updated_at=now() where config_id=? and status in ('PREVIEW','READY','RETRY') and owner_employee_id is distinct from ?",id,input.ownerEmployeeId());
   audit(actor,"CONFIG_UPDATED",id,encode(input));
  });
 }
 public UUID create(PaymentInput input,String actor) {
  return tx.execute(s->{
   UUID id=UUID.randomUUID();String code="CUSTOM_"+id.toString().replace("-","").substring(0,20);
   db.update("insert into payment_configs(id,code,name,amount,currency,estimated_ngn,scheduled_day,renewal_day,description,to_address,cc_addresses,subject,body,enabled) values(?,?,?,?,?,?,?,?,?,?,?,?,?,false)",
    id,code,input.name(),input.amount(),input.currency(),input.estimatedNgn(),input.scheduledDay(),input.renewalDay(),input.description(),input.toAddress(),input.ccAddresses(),input.subject(),input.body());
   PaymentInput initial=new PaymentInput(input.name(),input.amount(),input.currency(),input.estimatedNgn(),input.scheduledDay(),input.renewalDay(),input.description(),input.toAddress(),input.ccAddresses(),input.subject(),input.body(),input.ownerEmployeeId(),input.enabled(),0);
   save(id,initial,actor);audit(actor,"CONFIG_CREATED",id,"Assigned recurring payment created");return id;
  });
 }
 public List<Map<String,Object>> employees() {
  return db.queryForList("select id,full_name,email from employees where is_active=true order by full_name");
 }
 public PaymentComposer.Message previewFor(UUID id,YearMonth month,com.adesa.attendance.security.AuthenticatedEmployee actor) {
  // Authorize and render the same snapshot, even if an administrator reassigns the configuration concurrently.
  var selected=config(id);
  if(actor.role()!=com.adesa.attendance.employee.EmployeeRole.ADMIN && !actor.employeeId().equals(selected.get("owner_employee_id")))
   throw new ForbiddenException("This payment is not assigned to you");
  return composer.compose(selected,month,settings().get("signature_html").toString());
 }
 public Map<String,Object> employeeDashboard(com.adesa.attendance.security.AuthenticatedEmployee actor,Instant now) {
  if(actor.role()==com.adesa.attendance.employee.EmployeeRole.ADMIN)return dashboard(now);
  UUID owner=actor.employeeId();
  var payments=configurations(now).stream().filter(c->owner.equals(c.get("owner_employee_id"))).toList();
  var history=db.queryForList("select r.*,c.name from payment_requests r join payment_configs c on c.id=r.config_id where r.owner_employee_id=? order by r.created_at desc limit 200",owner);
  var attempts=db.queryForList("select a.* from payment_delivery_attempts a join payment_requests r on r.id=a.request_id join payment_configs c on c.id=r.config_id where r.owner_employee_id=? order by a.started_at desc limit 300",owner);
  var settings=settings();settings.put("signature_html","");
  return Map.of("payments",payments,"settings",settings,"history",history,"attempts",attempts,"audit",List.of(),"employees",List.of(),"canManage",false);
 }
 public Map<String,Object> dashboard(Instant now) {
  return Map.of("payments",configurations(now),"settings",settings(),"history",history(),"attempts",attempts(),"audit",audits(),"employees",employees(),"canManage",true);
 }
 private void validateVariables(String value) {
  String remaining=value.replaceAll("\\{\\{(amount|estimated_ngn|renewal_day|billing_month|name)}}","");
  if(remaining.contains("{{")||remaining.contains("}}")) throw new BadRequestException("Unsupported template variable");
 }
 private void addresses(String value,boolean emptyAllowed) {
  if(value.isBlank()&&emptyAllowed)return;
  try {
   if(value.contains("\r")||value.contains("\n"))throw new IllegalArgumentException();
   var addresses=InternetAddress.parse(value,true);
   if(addresses.length==0 || addresses.length>20) throw new IllegalArgumentException();
   for(var address:addresses){address.validate();if(!address.getAddress().contains("@"))throw new IllegalArgumentException();}
  }catch(Exception ex){throw new BadRequestException("Enter valid comma-separated email addresses");}
 }
 public void settings(boolean enabled,String signature,String actor) {
  if(signature.length()>100000) throw new BadRequestException("Signature is too large");
  // Signature is trusted admin HTML, isolated in a sandboxed preview. Only publicly fetchable HTTPS images.
  String inspected=signature.replace("{{PUBLIC_URL}}/assets/email/adesa-hq.png","https://signature.invalid/adesa-hq.png")
    .replace("{{PUBLIC_URL}}/assets/email/group-headquarters.png","https://signature.invalid/group-headquarters.png");
  if(!signature.isBlank() && (inspected.matches("(?is).*<(script|iframe|object|embed|form)\\b.*") || inspected.matches("(?is).*\\bon[a-z]+\\s*=.*")
    || inspected.matches("(?is).*(javascript:|/zm/ImageSignature|src\\s*=\\s*['\"](?!https://)).*")))
   throw new BadRequestException("Use the original signature with public HTTPS image URLs and no active content");
  if(enabled && transport.live() && composer.effectiveSignature(signature).isBlank())throw new BadRequestException("Configure Christian's existing signature before enabling live automation");
  tx.executeWithoutResult(s->{db.update("update payment_automation set enabled=?,signature_html=?,updated_at=now() where id=1",enabled,signature);
   audit(actor,"AUTOMATION_UPDATED",1,"enabled="+enabled+"; signatureConfigured="+!signature.isBlank());});
 }
 public PaymentComposer.Message preview(UUID id,YearMonth month) {
  return composer.compose(config(id),month,settings().get("signature_html").toString());
 }
 public UUID generate(UUID id,YearMonth month,String actor) {
  return tx.execute(s->{var config=config(id);String snapshot=encode(preview(id,month));UUID request=UUID.randomUUID();
   db.update("insert into payment_requests(id,config_id,billing_month,scheduled_date,send_date,status,snapshot,owner_employee_id) values(?,?,?,?,?,'PREVIEW',?,?) on conflict(config_id,billing_month) do nothing",
    request,id,Date.valueOf(month.atDay(1)),Date.valueOf(month.atDay((Integer)config.get("scheduled_day"))),Date.valueOf(PaymentCalendar.adjusted(month,(Integer)config.get("scheduled_day"))),snapshot,config.get("owner_employee_id"));
   UUID actual=db.queryForObject("select id from payment_requests where config_id=? and billing_month=?",UUID.class,id,Date.valueOf(month.atDay(1)));
   audit(actor,"PREVIEW_GENERATED",actual,month.toString());return actual;
  });
 }
 public List<Map<String,Object>> history() {return db.queryForList("select r.*,c.name from payment_requests r join payment_configs c on c.id=r.config_id order by r.created_at desc limit 200");}
 public List<Map<String,Object>> attempts() {return db.queryForList("select * from payment_delivery_attempts order by started_at desc limit 300");}
 public List<Map<String,Object>> audits() {return db.queryForList("select * from payment_audit order by id desc limit 300");}
 public void recover(UUID id,String actor,String note,Instant now) {
  if(note.isBlank()||note.length()>1000)throw new BadRequestException("A recovery reason is required (up to 1000 characters)");
  tx.executeWithoutResult(s->{
   var settings=db.queryForMap("select * from payment_automation where id=1 for update");
   var r=db.queryForMap("select * from payment_requests where id=? for update",id);
   String status=r.get("status").toString();
   if(!List.of("MISSED","FAILED","RETRY","PREVIEW").contains(status))throw new ConflictException("Only confirmed unsent requests may be recovered; uncertain requests require reconciliation");
   YearMonth month=YearMonth.from(((Date)r.get("billing_month")).toLocalDate());
   var config=config((UUID)r.get("config_id"));
   LocalDate today=now.atZone(PaymentCalendar.ZONE).toLocalDate();YearMonth current=YearMonth.from(today);
   LocalDate adjusted=PaymentCalendar.adjusted(month,(Integer)config.get("scheduled_day"));
   boolean nextMonthFriday=month.equals(current.plusMonths(1))&&YearMonth.from(adjusted).equals(current)&&!adjusted.isAfter(today);
   if(!month.equals(current)&&!nextMonthFriday)throw new ConflictException("Outdated or future billing months cannot be sent");
   if(config.get("owner_employee_id")==null || !Objects.equals(config.get("owner_employee_id"),r.get("owner_employee_id")))throw new ConflictException("This request's employee assignment changed; review the next billing month");
   if(!(boolean)config.get("enabled"))throw new ConflictException("Enable the payment schedule first");
   if(!(boolean)settings.get("enabled"))throw new ConflictException("Enable automatic sending before queueing a request");
   String snapshot=encode(composer.compose(config,month,settings.get("signature_html").toString()));
   db.update("update payment_requests set status='READY',snapshot=?,next_attempt_at=?,delivery_deadline=?,updated_at=now() where id=?",snapshot,java.sql.Timestamp.from(now),java.sql.Timestamp.from(now.atZone(PaymentCalendar.ZONE).toLocalDate().plusDays(1).atStartOfDay(PaymentCalendar.ZONE).toInstant()),id);
   audit(actor,"RECOVERY_QUEUED",id,note);
  });
 }
 public void reconcile(UUID id,boolean accepted,String providerId,String note,String actor) {
  if(note.isBlank()||note.length()>1000||providerId.length()>300 || (accepted&&providerId.isBlank()))throw new BadRequestException("Provide a reconciliation reason and provider receipt for accepted messages");
  tx.executeWithoutResult(s->{
   int updated=db.update("update payment_requests set status=?,provider_message_id=?,sent_at=?,updated_at=now() where id=? and status='UNKNOWN'",
    accepted?"SENT":"FAILED",accepted?providerId:null,accepted?java.sql.Timestamp.from(Instant.now()):null,id);
   if(updated!=1)throw new ConflictException("Only uncertain requests may be reconciled");
   audit(actor,accepted?"PROVIDER_ACCEPTANCE_CONFIRMED":"PROVIDER_NON_ACCEPTANCE_CONFIRMED",id,note);
  });
 }
 String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException("Cannot serialize payment record");}}
 PaymentComposer.Message decode(String value) {try{return json.readValue(value,PaymentComposer.Message.class);}catch(Exception ex){throw new IllegalStateException("Invalid payment snapshot");}}
}
