package com.adesa.attendance.payments;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PaymentDispatcher {
 private static final Logger log=LoggerFactory.getLogger(PaymentDispatcher.class);
 private final PaymentService service;
 public PaymentDispatcher(PaymentService service) {this.service=service;}
 /** Testable time boundary; invoked by the scheduler with the current instant. */
 public void tick(Instant now) {
  prepare(now);
  for(int i=0;i<10;i++) {
   Map<String,Object> claimed=claim(now);
   if(claimed==null)return;
   UUID id=(UUID)claimed.get("id");
   int attempt=(Integer)claimed.get("attempts");
   String status="UNKNOWN", receipt=null, detail="Provider acceptance uncertain; reconcile before retry";
   try {
    var result=service.transport.deliver(service.decode(claimed.get("snapshot").toString()));
    receipt=result.messageId();status=result.mock()?"MOCK_CAPTURED":"SENT";detail=result.mock()?"Local mock capture; no network delivery":"Provider accepted";
   } catch(PaymentTransport.Rejected ex) {
    status=ex.retryable && attempt<3?"RETRY":"FAILED";detail=ex.retryable?"Confirmed temporary rejection":"Confirmed rejection";
   } catch(RuntimeException ex) {
    // Do not include provider exception text, bodies, tokens or message contents in logs.
    log.warn("payment_delivery request={} attempt={} result=UNKNOWN",id,attempt);
   }
   finish(id,attempt,status,receipt,detail,now);
  }
 }
 private void prepare(Instant now) {
  service.tx.executeWithoutResult(s->{
   service.db.update("update payment_delivery_attempts a set result='UNKNOWN',finished_at=? where result='SENDING' and started_at<?",
    Timestamp.from(now),Timestamp.from(now.minusSeconds(600)));
   service.db.update("update payment_requests set status='UNKNOWN',updated_at=? where status='SENDING' and updated_at<?",Timestamp.from(now),Timestamp.from(now.minusSeconds(600)));
   service.db.update("update payment_requests set status='MISSED',updated_at=? where status in ('READY','RETRY') and delivery_deadline<=?",Timestamp.from(now),Timestamp.from(now));
   var settings=service.db.queryForMap("select * from payment_automation where id=1 for update");
   if(!(boolean)settings.get("enabled"))return;
   var date=now.atZone(PaymentCalendar.ZONE).toLocalDate();YearMonth month=YearMonth.from(date);
   for(var config:service.db.queryForList("select * from payment_configs where enabled=true and owner_employee_id in (select id from employees where is_active=true) order by id for update")) {
    for (YearMonth targetMonth:List.of(month,month.plusMonths(1))) {
    int day=(Integer)config.get("scheduled_day");LocalDate send=PaymentCalendar.adjusted(targetMonth,day);
    if(now.isBefore(PaymentCalendar.due(targetMonth,day).toInstant()))continue;
    UUID id=UUID.randomUUID();String status=send.equals(date)?"READY":"MISSED";
    service.db.update("insert into payment_requests(id,config_id,billing_month,scheduled_date,send_date,status,snapshot,next_attempt_at,delivery_deadline,owner_employee_id) values(?,?,?,?,?,?,?,?,?,?) on conflict(config_id,billing_month) do nothing",
     id,config.get("id"),Date.valueOf(targetMonth.atDay(1)),Date.valueOf(targetMonth.atDay(day)),Date.valueOf(send),status,
     service.encode(service.composer.compose(config,targetMonth,settings.get("signature_html").toString())),Timestamp.from(now),Timestamp.from(send.plusDays(1).atStartOfDay(PaymentCalendar.ZONE).toInstant()),config.get("owner_employee_id"));
    // Previews must not suppress an enabled schedule on its due date.
    service.db.update("update payment_requests set status=?,snapshot=?,next_attempt_at=?,delivery_deadline=?,updated_at=? where config_id=? and billing_month=? and status='PREVIEW'",
     status,service.encode(service.composer.compose(config,targetMonth,settings.get("signature_html").toString())),Timestamp.from(now),Timestamp.from(send.plusDays(1).atStartOfDay(PaymentCalendar.ZONE).toInstant()),Timestamp.from(now),config.get("id"),Date.valueOf(targetMonth.atDay(1)));
    }
   }
  });
 }
 private Map<String,Object> claim(Instant now) {
  return service.tx.execute(s->{
   var settings=service.db.queryForMap("select * from payment_automation where id=1 for update");
   if(!(boolean)settings.get("enabled"))return null;
   var rows=service.db.queryForList("select r.* from payment_requests r join payment_configs c on c.id=r.config_id where r.status in ('READY','RETRY') and r.next_attempt_at<=? and r.delivery_deadline>? and r.owner_employee_id=c.owner_employee_id and c.enabled=true and c.owner_employee_id in (select id from employees where is_active=true) order by r.created_at limit 1 for update of r skip locked",Timestamp.from(now),Timestamp.from(now));
   if(rows.isEmpty())return null;
   var row=rows.getFirst();int attempt=(Integer)row.get("attempts")+1;row.put("attempts",attempt);
   service.db.update("update payment_requests set status='SENDING',attempts=?,updated_at=? where id=?",attempt,Timestamp.from(now),row.get("id"));
   service.db.update("insert into payment_delivery_attempts(id,request_id,attempt,started_at,result) values(?,?,?,?,'SENDING')",UUID.randomUUID(),row.get("id"),attempt,Timestamp.from(now));
   service.audit("scheduler","DELIVERY_CLAIMED",row.get("id"),"attempt="+attempt);
   return row;
  });
 }
 private void finish(UUID id,int attempt,String status,String receipt,String detail,Instant now) {
  service.tx.executeWithoutResult(s->{
   // A stale worker must not overwrite a reconciled/unknown request after restart recovery.
   int count=service.db.update("update payment_requests set status=?,provider_message_id=?,sent_at=?,next_attempt_at=?,updated_at=? where id=? and status='SENDING' and attempts=?",
    status,receipt,status.equals("SENT")?Timestamp.from(now):null,Timestamp.from(now.plusSeconds(60L*(1L<<Math.min(attempt,5)))),Timestamp.from(now),id,attempt);
   if(count==0)return;
   service.db.update("update payment_delivery_attempts set result=?,provider_message_id=?,detail=?,finished_at=? where request_id=? and attempt=?",status,receipt,detail,Timestamp.from(now),id,attempt);
   service.audit("scheduler","DELIVERY_"+status,id,"attempt="+attempt);
   log.info("payment_delivery request={} attempt={} result={}",id,attempt,status);
  });
 }
}
