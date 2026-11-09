package com.adesa.attendance.assets;

import com.adesa.attendance.common.*;
import com.adesa.attendance.employee.Employee;
import com.adesa.attendance.employee.EmployeeRole;
import com.adesa.attendance.security.AuthenticatedEmployee;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static com.adesa.attendance.assets.AssetRequests.*;

/** Every mutation locks the asset first. Assignment, return and repair changes commit atomically. */
@Service
@Transactional
public class AssetService {
 private final EntityManager em;
 private final AssetRepository inventory;
 private final AssetNotifications notifications;
 public AssetService(EntityManager em,AssetRepository inventory,AssetNotifications notifications) {
  this.em=em; this.inventory=inventory; this.notifications=notifications;
 }
 static boolean manager(AuthenticatedEmployee actor) { return actor.role()==EmployeeRole.ADMIN || actor.role()==EmployeeRole.IT_ADMIN; }
 static boolean reader(AuthenticatedEmployee actor) { return manager(actor) || actor.role()==EmployeeRole.HR; }
 static void manage(AuthenticatedEmployee actor) { if(!manager(actor)) throw new ForbiddenException("Asset management permission required"); }
 static void hr(AuthenticatedEmployee actor) { if(!reader(actor)) throw new ForbiddenException("HR or asset management permission required"); }
 private static void require(boolean condition, String message) { if(!condition) throw new BusinessRuleException(message); }
 <T> T find(Class<T> type, UUID id) { T row=em.find(type,id); if(row==null) throw new NotFoundException("Asset record not found"); return row; }
 private void flush() {
  try { em.flush(); }
  catch(org.hibernate.exception.ConstraintViolationException ex) {
   throw new ConflictException("This operation conflicts with an existing asset record");
  }
 }
 private Asset lock(UUID id) { return inventory.findLockedById(id).orElseThrow(()->new NotFoundException("Asset not found")); }
 private void employeeLock(UUID id) { Employee e=em.find(Employee.class,id,LockModeType.PESSIMISTIC_WRITE); if(e==null) throw new NotFoundException("Employee not found"); }
 private <T extends AssetRecord> T add(T row, AuthenticatedEmployee actor) { row.createdBy=actor.employeeId(); row.updatedBy=actor.employeeId(); em.persist(row); return row; }
 private void touch(AssetRecord row, AuthenticatedEmployee actor) { row.updatedBy=actor.employeeId(); row.updatedAt=Instant.now(); }
 private List<Assignment> active(UUID asset) { return em.createQuery("from Assignment where assetId=:id and status='ACTIVE'",Assignment.class).setParameter("id",asset).getResultList(); }
 private boolean owns(UUID asset,UUID employee) { return active(asset).stream().anyMatch(x->x.employeeId.equals(employee)&&x.approvalStatus.equals("APPROVED")); }
 private void own(UUID asset, AuthenticatedEmployee actor) { if(!owns(asset,actor.employeeId())) throw new ForbiddenException("Only the employee currently assigned this asset may submit this request"); }
 void view(UUID asset,AuthenticatedEmployee actor) {
  if(reader(actor)) return;
  Long count=em.createQuery("select count(x) from Assignment x where x.assetId=:id and x.employeeId=:employee and x.approvalStatus='APPROVED'",Long.class).setParameter("id",asset).setParameter("employee",actor.employeeId()).getSingleResult();
  if(count==0) throw new ForbiddenException("This asset is private");
 }
 private void audit(Asset a,UUID employee,String action,String previous,String next,String notes,AuthenticatedEmployee actor) {
  AssetAudit log=new AssetAudit(); log.assetId=a==null?null:a.id; log.employeeId=employee; log.action=action;
  log.previousState=previous; log.newState=next; log.notes=notes; add(log,actor);
 }
 private void change(Asset a,String status,AuthenticatedEmployee actor) { a.status=status; touch(a,actor); }
 private void subsidiary(UUID id) { require(find(Subsidiary.class,id).active,"Subsidiary is inactive"); }
 private void noClearance(UUID employee) {
  long n=em.createQuery("select count(c) from Clearance c where c.employeeId=:id and c.status in ('PENDING','NOT_CLEARED')",Long.class).setParameter("id",employee).getSingleResult();
  require(n==0,"Employee has an open clearance; close that process before assigning another asset");
 }
 public UUID register(Registration r,AuthenticatedEmployee actor) {
  manage(actor); Asset a=new Asset(); a.assetType=r.assetType(); a.brand=r.brand().trim(); a.model=r.model().trim(); a.serialNumber=r.serialNumber().trim();
  a.purchaseDate=r.purchaseDate(); a.condition=r.condition(); a.status=Set.of("DAMAGED","UNUSABLE").contains(a.condition)?"UNDER_REPAIR":"AVAILABLE";
  String sequence=a.assetType.equals("LAPTOP")?"asset_laptop_number":"asset_phone_number";
  Number n=(Number)em.createNativeQuery("select nextval('"+sequence+"')").getSingleResult();
  a.assetCode="ADESA-"+(a.assetType.equals("LAPTOP")?"LAP":"PHN")+"-"+String.format("%04d",n.longValue()); add(a,actor);
  audit(a,null,"ASSET_CREATED",null,snapshot(a),"Registered "+a.assetCode,actor); flush(); return a.id;
 }
 public void edit(UUID id,Edit r,AuthenticatedEmployee actor) {
  manage(actor); Asset a=lock(id); require(a.version==r.version(),"Asset changed; refresh before editing"); String before=snapshot(a);
  require(!Set.of("RETIRED","DISPOSED","LOST").contains(a.status),"Terminal assets cannot be edited");
  a.brand=r.brand().trim(); a.model=r.model().trim(); a.serialNumber=r.serialNumber().trim(); a.purchaseDate=r.purchaseDate(); a.condition=r.condition();
  if(Set.of("DAMAGED","UNUSABLE").contains(a.condition)&&a.status.equals("AVAILABLE")) a.status="UNDER_REPAIR";
  touch(a,actor); audit(a,null,"ASSET_UPDATED",before,snapshot(a),"Inventory details updated",actor); flush();
 }
 private String snapshot(Asset a) { return a.assetCode+" | "+a.brand+" | "+a.model+" | "+a.serialNumber+" | "+a.purchaseDate+" | "+a.condition+" | "+a.status; }
 public void terminal(UUID id,String target,Decision r,AuthenticatedEmployee actor) {
  manage(actor); require(Set.of("RETIRED","LOST","DISPOSED").contains(target),"Invalid terminal status"); Asset a=lock(id);
  require(!a.status.equals("DISPOSED"),"Disposed assets are final");
  require(active(id).isEmpty(),"Verify all asset returns before retiring, disposing or recording loss");
  require(!pendingRequest(id),"Resolve the pending asset request first");
  require(em.createQuery("select count(r) from Repair r where r.assetId=:id and r.status in ('REPORTED','UNDER_REVIEW','APPROVED','IN_PROGRESS')",Long.class).setParameter("id",id).getSingleResult()==0,"Complete or cancel open repairs first");
  String before=a.status; change(a,target,actor); audit(a,null,"ASSET_"+target,before,target,r.notes(),actor);
 }
 /** Formal loss accounting closes custody without pretending a device was returned. */
 public void accountLoss(UUID id,Decision r,AuthenticatedEmployee actor) {
  if(actor.role()!=EmployeeRole.ADMIN) throw new ForbiddenException("An administrator must authorize loss accounting");
  List<Assignment> initial=active(id);
  for(UUID employee:initial.stream().map(x->x.employeeId).distinct().sorted().toList()) employeeLock(employee);
  Asset a=lock(id); require(!pendingRequest(id),"Resolve the pending asset request first");require(!Set.of("DISPOSED","RETIRED","LOST").contains(a.status),"Asset is already in a terminal state");
  String before=a.status;
  for(Assignment x:active(id)) {
   x.status="CANCELLED"; touch(x,actor);
   for(AssetReturn v:em.createQuery("from AssetReturn where assignmentId=:id and status in ('PENDING','OVERDUE')",AssetReturn.class).setParameter("id",x.id).getResultList()) {
    v.status="CANCELLED"; v.notes=r.notes(); touch(v,actor); audit(a,x.employeeId,"RETURN_CANCELLED_FOR_LOSS","PENDING","CANCELLED",r.notes(),actor);
   }
   audit(a,x.employeeId,"ASSET_LOSS_ACCOUNTED",before,"LOST",r.notes(),actor);
  }
  for(Repair v:em.createQuery("from Repair where assetId=:id and status in ('REPORTED','UNDER_REVIEW','APPROVED','IN_PROGRESS')",Repair.class).setParameter("id",id).getResultList()) {
   String old=v.status;v.status="CANCELLED";v.notes=r.notes();touch(v,actor);audit(a,null,"REPAIR_CANCELLED",old,"CANCELLED",r.notes(),actor);
  }
  change(a,"LOST",actor); audit(a,null,"ASSET_LOST",before,"LOST",r.notes(),actor);
 }
 private boolean pendingRequest(UUID assetId) {
  return em.createQuery("select count(r) from DeviceRequest r where r.assetId=:id and r.status='PENDING'",Long.class)
   .setParameter("id",assetId).getSingleResult()>0;
 }
 private void available(Asset a) {
  require(a.status.equals("AVAILABLE") && active(a.id).isEmpty(),"Only an available, unreserved asset may be requested or assigned");
  require(Set.of("NEW","GOOD","FAIR").contains(a.condition),"The asset is not in a working condition");
  require(em.createQuery("select count(r) from Repair r where r.assetId=:id and r.status in ('REPORTED','UNDER_REVIEW','APPROVED','IN_PROGRESS')",Long.class).setParameter("id",a.id).getSingleResult()==0,"The asset has an open repair");
 }
 public UUID requestAsset(DeviceRequestInput r,AuthenticatedEmployee actor) {
  employeeLock(actor.employeeId()); noClearance(actor.employeeId()); Asset a=lock(r.assetId());
  require(find(Employee.class,actor.employeeId()).isActive(),"Employee is inactive"); subsidiary(r.subsidiaryId()); available(a);
  require(!pendingRequest(a.id),"This asset is already reserved by a pending request");
  DeviceRequest request=new DeviceRequest();request.assetId=a.id;request.employeeId=actor.employeeId();
  request.subsidiaryId=r.subsidiaryId();request.reason=r.reason().trim();request.status="PENDING";add(request,actor);
  audit(a,request.employeeId,"ASSET_REQUESTED",null,"PENDING",request.reason,actor);
  notifications.queue("Asset","SUBMITTED",request.id,a,request.employeeId,request.reason,actor);
  flush();return request.id;
 }
 public void requestDecision(UUID id,boolean approve,Decision r,AuthenticatedEmployee actor) {
  manage(actor);DeviceRequest request=find(DeviceRequest.class,id);employeeLock(request.employeeId);Asset a=lock(request.assetId);em.refresh(request);
  require(request.status.equals("PENDING"),"Request is no longer pending");
  if(approve) {
   require(!request.employeeId.equals(actor.employeeId()),"You cannot approve your own asset request");
   noClearance(request.employeeId);require(find(Employee.class,request.employeeId).isActive(),"Employee is inactive");subsidiary(request.subsidiaryId);available(a);
   Assignment x=new Assignment();x.assetId=a.id;x.employeeId=request.employeeId;x.subsidiaryId=request.subsidiaryId;
   x.assignmentDate=LocalDate.now();x.status="ACTIVE";x.approvalStatus="APPROVED";x.approvedBy=actor.employeeId();x.approvalDate=Instant.now();x.notes=r.notes();add(x,actor);
   request.assignmentId=x.id;change(a,"ASSIGNED",actor);audit(a,x.employeeId,"ASSET_ASSIGNED","AVAILABLE","ASSIGNED",r.notes(),actor);
  }
  request.status=approve?"APPROVED":"REJECTED";request.reviewedBy=actor.employeeId();request.reviewedAt=Instant.now();request.notes=r.notes();touch(request,actor);
  audit(a,request.employeeId,"ASSET_REQUEST_"+request.status,"PENDING",request.status,r.notes(),actor);
  notifications.queue("Asset",request.status,request.id,a,request.employeeId,r.notes(),actor);
 }
 public UUID requestRepair(EmployeeRepairRequest r,AuthenticatedEmployee actor) {
  Asset a=lock(r.assetId());own(a.id,actor);
  require(!Set.of("RETIRED","LOST","DISPOSED").contains(a.status),"Terminal assets cannot be repaired");
  Repair v=new Repair();v.assetId=a.id;v.employeeId=actor.employeeId();v.description=r.description().trim();v.repairType="INTERNAL";
  v.status="REPORTED";v.submittedDate=LocalDate.now();v.notes=r.notes();add(v,actor);
  audit(a,v.employeeId,"REPAIR_REQUESTED",null,"REPORTED",r.description(),actor);
  notifications.queue("Repair","SUBMITTED",v.id,a,v.employeeId,r.description(),actor);flush();return v.id;
 }
 public UUID assign(Assign r,AuthenticatedEmployee actor) {
  manage(actor); employeeLock(r.employeeId()); noClearance(r.employeeId()); Asset a=lock(r.assetId());
  require(find(Employee.class,r.employeeId()).isActive(),"Employee is inactive"); subsidiary(r.subsidiaryId());
  available(a);require(!pendingRequest(a.id),"An employee request reserves this asset");
  Assignment x=new Assignment(); x.assetId=a.id; x.employeeId=r.employeeId(); x.subsidiaryId=r.subsidiaryId(); x.assignmentDate=r.assignmentDate();
  x.status="ACTIVE"; x.approvalStatus="PENDING"; x.notes=r.notes(); add(x,actor);
  audit(a,x.employeeId,"ASSIGNMENT_SUBMITTED",null,"PENDING",r.notes(),actor); flush(); return x.id;
 }
 public void assignmentDecision(UUID id,boolean approve,Decision r,AuthenticatedEmployee actor) {
  manage(actor); Assignment initial=find(Assignment.class,id); employeeLock(initial.employeeId); Asset a=lock(initial.assetId); em.refresh(initial); Assignment x=initial;
  require(x.status.equals("ACTIVE")&&x.approvalStatus.equals("PENDING"),"Assignment is no longer pending");
  if(approve) { noClearance(x.employeeId); require(find(Employee.class,x.employeeId).isActive(),"Employee is inactive"); require(a.status.equals("AVAILABLE"),"Asset is no longer available"); }
  x.approvalStatus=approve?"APPROVED":"REJECTED"; x.approvedBy=actor.employeeId(); x.approvalDate=Instant.now(); touch(x,actor);
  if(approve) { change(a,"ASSIGNED",actor); audit(a,x.employeeId,"ASSIGNMENT_APPROVED","PENDING","APPROVED",r.notes(),actor); } else x.status="CANCELLED";
  audit(a,x.employeeId,approve?"ASSET_ASSIGNED":"ASSIGNMENT_REJECTED","PENDING",x.approvalStatus,r.notes(),actor);
 }
 public UUID report(Fault r,AuthenticatedEmployee actor) {
  Asset a=lock(r.assetId()); own(a.id,actor); Issue i=new Issue(); i.assetId=a.id; i.employeeId=actor.employeeId(); i.title=r.title().trim();
  i.description=r.description().trim(); i.category=r.category(); i.priority=r.priority(); i.status="OPEN"; i.notes=r.notes(); add(i,actor);
  audit(a,i.employeeId,"ISSUE_REPORTED",null,"OPEN",i.title+": "+i.description,actor); return i.id;
 }
 public void issue(UUID id,IssueUpdate r,AuthenticatedEmployee actor) {
  manage(actor); Issue i=find(Issue.class,id); Asset a=lock(i.assetId); em.refresh(i); String before=i.status; String oldPriority=i.priority;
  boolean valid=i.status.equals(r.status()) || switch(i.status) {
   case "OPEN" -> Set.of("UNDER_REVIEW","IN_PROGRESS","RESOLVED").contains(r.status());
   case "UNDER_REVIEW" -> Set.of("IN_PROGRESS","RESOLVED").contains(r.status());
   case "IN_PROGRESS" -> r.status().equals("RESOLVED"); case "RESOLVED" -> Set.of("CLOSED","IN_PROGRESS").contains(r.status()); default -> false; };
  require(valid,"Invalid issue status transition"); i.status=r.status(); i.priority=r.priority(); i.notes=r.notes(); touch(i,actor);
  audit(a,i.employeeId,"ISSUE_UPDATED",before+" / "+oldPriority,r.status()+" / "+r.priority(),r.notes(),actor);
 }
 public UUID repair(RepairRequest r,AuthenticatedEmployee actor) {
  manage(actor); Asset a=lock(r.assetId()); require(!pendingRequest(a.id),"Resolve the pending asset request first");require(!Set.of("RETIRED","LOST","DISPOSED").contains(a.status),"Terminal assets cannot be repaired");
  if(r.issueId()!=null) require(find(Issue.class,r.issueId()).assetId.equals(a.id),"Issue belongs to a different asset");
  require(active(a.id).stream().noneMatch(x->x.approvalStatus.equals("PENDING")),"Resolve pending assignment approval first");
  Repair v=new Repair(); v.assetId=a.id; v.employeeId=active(a.id).stream().filter(x->x.approvalStatus.equals("APPROVED")).map(x->x.employeeId).findFirst().orElse(actor.employeeId()); v.issueId=r.issueId(); v.description=r.description(); v.repairType=r.repairType(); v.status="REPORTED";
  v.technician=r.technician(); v.estimatedCost=r.estimatedCost(); v.submittedDate=LocalDate.now(); v.notes=r.notes(); add(v,actor);
  audit(a,v.employeeId,"REPAIR_REQUESTED",null,v.status,r.description(),actor); notifications.queue("Repair","SUBMITTED",v.id,a,v.employeeId,r.description(),actor); flush(); return v.id;
 }
 public void repairUpdate(UUID id,RepairUpdate r,AuthenticatedEmployee actor) {
  manage(actor); Repair v=find(Repair.class,id); Asset a=lock(v.assetId); em.refresh(v); String before=v.status;
  boolean valid=switch(v.status) {case "REPORTED"->Set.of("UNDER_REVIEW","REJECTED","CANCELLED").contains(r.status());
   case "UNDER_REVIEW"->Set.of("APPROVED","REJECTED","CANCELLED").contains(r.status());
   case "APPROVED"->Set.of("IN_PROGRESS","CANCELLED").contains(r.status());
   case "IN_PROGRESS"->Set.of("COMPLETED","CANCELLED").contains(r.status()); default->false;};
  require(valid,"Invalid repair transition");
  if(r.status().equals("APPROVED")) { require(r.decision()!=null&&!r.decision().equals("REJECT"),"Select repair or replace"); v.approvedBy=actor.employeeId(); v.approvedDate=LocalDate.now(); v.decision=r.decision(); }
  if(r.status().equals("IN_PROGRESS")) { require("REPAIR".equals(v.decision),"Replacement decisions require an explicit replacement request"); v.startDate=LocalDate.now(); change(a,"UNDER_REPAIR",actor); }
  if(r.status().equals("COMPLETED")) { require(r.condition()!=null&&Set.of("NEW","GOOD","FAIR").contains(r.condition()),"Successful repair requires a working condition");
   require(r.actualCost()!=null,"Actual cost is required on completion"); v.completionDate=LocalDate.now(); a.condition=r.condition(); restore(a,actor);
   if(v.issueId!=null) { Issue i=find(Issue.class,v.issueId); String old=i.status; i.status="RESOLVED"; touch(i,actor); audit(a,i.employeeId,"ISSUE_RESOLVED",old,"RESOLVED",r.notes(),actor); }
  }
  if(r.status().equals("REJECTED")) v.decision="REJECT";
  if(r.status().equals("CANCELLED")&&before.equals("IN_PROGRESS")) { a.condition="DAMAGED"; change(a,"UNDER_REPAIR",actor); }
  v.status=r.status(); if(r.technician()!=null) v.technician=r.technician(); if(r.actualCost()!=null) v.actualCost=r.actualCost(); v.notes=r.notes(); touch(v,actor);
  audit(a,v.employeeId,"REPAIR_"+v.status,before,v.status,r.notes(),actor);
  if(Set.of("APPROVED","REJECTED").contains(v.status)) notifications.queue("Repair",v.status,v.id,a,v.employeeId,r.notes(),actor);
 }
 private void restore(Asset a,AuthenticatedEmployee actor) {
  boolean assigned=active(a.id).stream().anyMatch(x->x.approvalStatus.equals("APPROVED"));
  long replacement=em.createQuery("select count(r) from Replacement r where r.assetId=:id and r.status='APPROVED'",Long.class).setParameter("id",a.id).getSingleResult();
  change(a,replacement>0?"PENDING_REPLACEMENT":assigned?"ASSIGNED":"AVAILABLE",actor);
 }
 public UUID replacement(ReplacementRequest r,AuthenticatedEmployee actor) {
  Asset a=lock(r.assetId()); own(a.id,actor); Replacement v=new Replacement(); v.assetId=a.id; v.employeeId=actor.employeeId(); v.reason=r.reason();
  v.description=r.description(); v.status="PENDING"; add(v,actor); audit(a,v.employeeId,"REPLACEMENT_REQUESTED",null,"PENDING",r.description(),actor); notifications.queue("Replacement","SUBMITTED",v.id,a,v.employeeId,r.description(),actor); flush(); return v.id;
 }
 public void replacementDecision(UUID id,String decision,Decision r,AuthenticatedEmployee actor) {
  manage(actor); Replacement v=find(Replacement.class,id); Asset a=lock(v.assetId); em.refresh(v);
  require(!v.employeeId.equals(actor.employeeId()),"You cannot review your own replacement request");
  require(Set.of("PENDING","UNDER_REVIEW").contains(v.status),"Request is no longer awaiting review");
  require(Set.of("UNDER_REVIEW","APPROVED","REJECTED").contains(decision),"Invalid replacement decision");
  if(decision.equals("APPROVED")) require(!Set.of("RETIRED","DISPOSED").contains(a.status),"Terminal asset cannot enter replacement workflow");
  String before=v.status; v.status=decision; v.reviewedBy=actor.employeeId(); v.notes=r.notes();
  if(decision.equals("APPROVED")) { v.approvalDate=Instant.now(); if(!Set.of("UNDER_REPAIR","LOST").contains(a.status)) change(a,"PENDING_REPLACEMENT",actor); }
  touch(v,actor); audit(a,v.employeeId,"REPLACEMENT_"+decision,before,decision,r.notes(),actor);
  if(Set.of("APPROVED","REJECTED").contains(decision)) notifications.queue("Replacement",decision,v.id,a,v.employeeId,r.notes(),actor);
 }
 public void fulfil(UUID id,Fulfil r,AuthenticatedEmployee actor) {
  manage(actor); Replacement v=find(Replacement.class,id); employeeLock(v.employeeId); noClearance(v.employeeId);
  require(!v.assetId.equals(r.replacementAssetId()),"Select a different replacement asset");
  // Stable UUID lock order avoids deadlocks when replacements cross assets.
  var ids=new ArrayList<>(List.of(v.assetId,r.replacementAssetId())); ids.sort(Comparator.naturalOrder()); for(UUID key:ids) lock(key);
  em.refresh(v); Asset previous=find(Asset.class,v.assetId); Asset next=find(Asset.class,r.replacementAssetId());
  require(v.status.equals("APPROVED"),"Replacement must be approved first"); require(active(previous.id).isEmpty(),"Previous asset must have a verified return first");
  boolean returned=em.createQuery("select count(r) from AssetReturn r where r.assetId=:asset and r.employeeId=:employee and r.status='RETURNED'",Long.class).setParameter("asset",v.assetId).setParameter("employee",v.employeeId).getSingleResult()>0;
  boolean accounted=previous.status.equals("LOST")&&em.createQuery("select count(l) from AssetAudit l where l.assetId=:asset and l.employeeId=:employee and l.action='ASSET_LOSS_ACCOUNTED'",Long.class).setParameter("asset",v.assetId).setParameter("employee",v.employeeId).getSingleResult()>0;
  require(returned||accounted,"A verified return or administrator-authorized loss accounting is required");
  available(next);require(!pendingRequest(next.id),"An employee request reserves the replacement asset"); subsidiary(r.subsidiaryId()); require(find(Employee.class,v.employeeId).isActive(),"Employee is inactive");
  Assignment x=new Assignment(); x.assetId=next.id; x.employeeId=v.employeeId; x.subsidiaryId=r.subsidiaryId(); x.assignmentDate=LocalDate.now(); x.status="ACTIVE"; x.approvalStatus="APPROVED";
  x.approvedBy=actor.employeeId(); x.approvalDate=Instant.now(); x.notes=r.notes(); add(x,actor); change(next,"ASSIGNED",actor);
  v.status="FULFILLED"; v.replacementAssetId=next.id; v.notes=r.notes(); touch(v,actor);
  if(previous.status.equals("PENDING_REPLACEMENT")) change(previous,Set.of("DAMAGED","UNUSABLE").contains(previous.condition)?"UNDER_REPAIR":"AVAILABLE",actor);
  audit(next,v.employeeId,"ASSET_ASSIGNED","AVAILABLE","ASSIGNED",r.notes(),actor); audit(previous,v.employeeId,"REPLACEMENT_FULFILLED","APPROVED","FULFILLED",next.assetCode+": "+r.notes(),actor);
 }
 public UUID initiateReturn(ReturnRequest r,AuthenticatedEmployee actor) {
  manage(actor); Assignment x=find(Assignment.class,r.assignmentId()); employeeLock(x.employeeId); Asset a=lock(x.assetId); em.refresh(x);
  require(x.status.equals("ACTIVE")&&x.approvalStatus.equals("APPROVED"),"Only an approved active assignment can be returned");
  AssetReturn v=new AssetReturn(); v.assetId=a.id; v.employeeId=x.employeeId; v.assignmentId=x.id; v.requestDate=LocalDate.now(); v.expectedDate=r.expectedDate(); v.status="PENDING"; v.notes=r.notes(); add(v,actor);
  audit(a,x.employeeId,"RETURN_INITIATED",null,"PENDING",r.notes(),actor); flush(); return v.id;
 }
 public void receive(UUID id,ReturnReceipt r,AuthenticatedEmployee actor) {
  manage(actor); AssetReturn v=find(AssetReturn.class,id); employeeLock(v.employeeId); Asset a=lock(v.assetId); em.refresh(v); Assignment x=find(Assignment.class,v.assignmentId); em.refresh(x);
  require(Set.of("PENDING","OVERDUE").contains(v.status)&&x.status.equals("ACTIVE"),"Return is already closed");
  require(!r.actualDate().isBefore(v.requestDate),"Return date cannot precede its request");
  require(em.createQuery("select count(v) from Repair v where v.assetId=:id and v.status in ('REPORTED','UNDER_REVIEW','APPROVED','IN_PROGRESS')",Long.class).setParameter("id",a.id).getSingleResult()==0,"Finish or cancel the open repair before verifying the return");
  v.status="RETURNED"; v.actualDate=r.actualDate(); v.condition=r.condition(); v.receivedBy=actor.employeeId(); v.notes=r.notes(); touch(v,actor);
  x.status="RETURNED"; touch(x,actor); a.condition=r.condition(); flush();
  if(Set.of("DAMAGED","UNUSABLE").contains(a.condition)) change(a,"UNDER_REPAIR",actor); else restore(a,actor);
  audit(a,v.employeeId,"ASSET_RETURNED","PENDING","RETURNED / "+a.condition,r.notes(),actor);
 }
 public UUID clearance(ClearanceRequest r,AuthenticatedEmployee actor) {
  hr(actor); employeeLock(r.employeeId()); Clearance c=new Clearance(); c.employeeId=r.employeeId(); c.status="PENDING"; c.notes=r.notes(); add(c,actor);
  for(Assignment x:em.createQuery("from Assignment where employeeId=:id and approvalStatus='APPROVED'",Assignment.class).setParameter("id",r.employeeId()).getResultList()) {
   ClearanceItem item=new ClearanceItem(); item.clearanceId=c.id; item.assignmentId=x.id; add(item,actor);
  }
  audit(null,c.employeeId,"CLEARANCE_INITIATED",null,"PENDING",r.notes(),actor); flush(); return c.id;
 }
 public void clearanceDecision(UUID id,boolean approve,Decision r,AuthenticatedEmployee actor) {
  manage(actor); Clearance c=find(Clearance.class,id); employeeLock(c.employeeId); em.refresh(c); require(!c.status.equals("CLEARED"),"Clearance is already final");
  if(approve) {
   require(em.createQuery("select count(x) from Assignment x where x.employeeId=:id and x.status='ACTIVE'",Long.class).setParameter("id",c.employeeId).getSingleResult()==0,"Outstanding assets or pending assignments prevent clearance");
   require(em.createQuery("select count(i) from Issue i where i.employeeId=:id and i.status not in ('RESOLVED','CLOSED')",Long.class).setParameter("id",c.employeeId).getSingleResult()==0,"Resolve outstanding issues before clearance");
   require(em.createQuery("select count(r) from Replacement r where r.employeeId=:id and r.status in ('PENDING','UNDER_REVIEW','APPROVED')",Long.class).setParameter("id",c.employeeId).getSingleResult()==0,"Resolve replacement requests before clearance");
   require(em.createQuery("select count(r) from DeviceRequest r where r.employeeId=:id and r.status='PENDING'",Long.class).setParameter("id",c.employeeId).getSingleResult()==0,"Resolve pending asset requests before clearance");
   c.clearanceDate=Instant.now(); c.clearedBy=actor.employeeId();
  }
  String before=c.status; c.status=approve?"CLEARED":"NOT_CLEARED"; c.notes=r.notes(); touch(c,actor); audit(null,c.employeeId,approve?"CLEARANCE_APPROVED":"CLEARANCE_REJECTED",before,c.status,r.notes(),actor);
 }
 public void employeeSubsidiary(UUID employee,EmployeeSubsidiary r,AuthenticatedEmployee actor) {
  manage(actor); employeeLock(employee); subsidiary(r.subsidiaryId());
  Object previous=em.createNativeQuery("select subsidiary_id from employees where id=:id").setParameter("id",employee).getSingleResult();
  em.createNativeQuery("update employees set subsidiary_id=:subsidiary where id=:id").setParameter("subsidiary",r.subsidiaryId()).setParameter("id",employee).executeUpdate();
  audit(null,employee,"EMPLOYEE_SUBSIDIARY_UPDATED",Objects.toString(previous,null),r.subsidiaryId().toString(),"Employee affiliation updated",actor);
 }
 public boolean demoExists() { return em.createQuery("select count(a) from Asset a where a.serialNumber='SYNTHETIC-LAP-001'",Long.class).getSingleResult()>0; }
 public UUID subsidiary(SubsidiaryRequest r,AuthenticatedEmployee actor) { manage(actor); Subsidiary s=new Subsidiary(); s.name=r.name().trim(); add(s,actor); audit(null,null,"SUBSIDIARY_CREATED",null,s.name,"Subsidiary registered",actor); flush(); return s.id; }
}
