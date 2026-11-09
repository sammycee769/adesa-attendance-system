package com.adesa.attendance.assets;

import com.adesa.attendance.common.*;
import com.adesa.attendance.security.AuthenticatedEmployee;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Read DTOs are projections, never managed entities or employee account records. */
@Service
@Transactional(readOnly=true)
public class AssetQueries {
 private final NamedParameterJdbcTemplate jdbc;
 private final AssetService service;
 public AssetQueries(NamedParameterJdbcTemplate jdbc,AssetService service) { this.jdbc=jdbc; this.service=service; }
 public record Page(List<Map<String,Object>> content,long totalElements,int page,int size) {}
 private static final Map<String,String> TABLES=Map.of("requests","asset_requests","assignments","asset_assignments","issues","asset_issues","repairs","asset_repairs","replacements","asset_replacement_requests","returns","asset_returns","clearance","employee_asset_clearances","audit-logs","asset_audit_logs");
 private Map<String,Object> params(AuthenticatedEmployee actor) { Map<String,Object> p=new HashMap<>(); p.put("actor",actor.employeeId()); return p; }
 private String scope(AuthenticatedEmployee actor,String kind) {
  if(AssetService.manager(actor)) return "true";
  if(actor.role().name().equals("HR")) { if(Set.of("inventory","assignments","clearance").contains(kind)) return "true"; }
  return switch(kind) {
   case "inventory" -> "exists(select 1 from asset_assignments owned where owned.asset_id=a.id and owned.employee_id=:actor and owned.approval_status='APPROVED')";
   case "issues" -> "(t.employee_id=:actor or exists(select 1 from asset_assignments owned where owned.asset_id=t.asset_id and owned.employee_id=:actor and owned.status='ACTIVE' and owned.approval_status='APPROVED'))";
   case "assignments" -> "t.employee_id=:actor and t.approval_status='APPROVED'";
   case "requests","repairs","replacements","returns" -> "t.employee_id=:actor";
   default -> throw new ForbiddenException("Asset management permission required");
  };
 }
 /** Minimal employee catalogue: no serial numbers, former owners, costs, or audit notes. */
 public Page availableAssets(String search,int page,int size) {
  checkPage(page,size);Map<String,Object> args=new HashMap<>();args.put("search","%"+search.toLowerCase(Locale.ROOT)+"%");args.put("limit",size);args.put("offset",page*size);
  String from=" from it_assets a where a.status='AVAILABLE' and a.condition in ('NEW','GOOD','FAIR') "
   +"and not exists(select 1 from asset_assignments x where x.asset_id=a.id and x.status='ACTIVE') "
   +"and not exists(select 1 from asset_requests r where r.asset_id=a.id and r.status='PENDING') "
   +"and not exists(select 1 from asset_repairs r where r.asset_id=a.id and r.status in ('REPORTED','UNDER_REVIEW','APPROVED','IN_PROGRESS')) "
   +"and lower(concat_ws(' ',a.asset_code,a.brand,a.model,a.asset_type)) like :search";
  return new Page(jdbc.queryForList("select a.id,a.asset_code,a.asset_type,a.brand,a.model,a.condition"+from+" order by a.asset_code limit :limit offset :offset",args),jdbc.queryForObject("select count(*)"+from,args,Long.class),page,size);
 }
 public List<Map<String,Object>> requestSubsidiaries() {
  return jdbc.queryForList("select id,name from subsidiaries where active=true order by name",Map.of());
 }
 public Page inventory(AuthenticatedEmployee actor,String search,String status,String condition,String type,UUID subsidiary,int page,int size,String sort,String direction) {
  checkPage(page,size); var p=params(actor); p.put("search","%"+search.toLowerCase(Locale.ROOT)+"%"); p.put("status",status); p.put("condition",condition); p.put("type",type); p.put("subsidiary",subsidiary);
  String personalJoin=AssetService.reader(actor)?"":" and x.employee_id=:actor";
  String from=" from it_assets a left join asset_assignments x on x.asset_id=a.id and x.status='ACTIVE' and x.approval_status='APPROVED'"+personalJoin+" left join employees e on e.id=x.employee_id left join subsidiaries s on s.id=x.subsidiary_id where "+scope(actor,"inventory")+
   " and (lower(concat_ws(' ',a.asset_code,a.serial_number,a.brand,a.model,e.full_name,s.name)) like :search) and (:status='' or a.status=:status) and (:condition='' or a.condition=:condition) and (:type='' or a.asset_type=:type)"+(subsidiary==null?"":" and x.subsidiary_id=:subsidiary");
  String order=Map.of("assetCode","a.asset_code","purchaseDate","a.purchase_date","status","a.status","brand","a.brand","createdAt","a.created_at").get(sort);
  if(order==null||!Set.of("asc","desc").contains(direction)) throw new BadRequestException("Unsupported inventory sorting");
  long count=jdbc.queryForObject("select count(*)"+from,p,Long.class); p.put("limit",size); p.put("offset",page*size);
  return new Page(jdbc.queryForList("select a.*,exists(select 1 from asset_assignments reserved where reserved.asset_id=a.id and reserved.status='ACTIVE') or exists(select 1 from asset_requests reserved where reserved.asset_id=a.id and reserved.status='PENDING') reserved,e.full_name employee_name,e.email employee_email,x.employee_id,x.subsidiary_id,s.name subsidiary_name"+from+" order by "+order+" "+direction+",a.id limit :limit offset :offset",p),count,page,size);
 }
 public Page records(String kind,AuthenticatedEmployee actor,String search,String status,UUID assetId,UUID employeeId,int page,int size) {
  checkPage(page,size); String table=TABLES.get(kind); if(table==null) throw new NotFoundException("Asset section not found");
  var p=params(actor); p.put("search","%"+search.toLowerCase(Locale.ROOT)+"%"); p.put("status",status); p.put("limit",size); p.put("offset",page*size); p.put("asset",assetId); p.put("employee",employeeId);
  String select="t.*,e.full_name employee_name,e.email employee_email";
  if(kind.equals("repairs")&&!AssetService.manager(actor)) select="t.id,t.asset_id,t.employee_id,t.description,t.status,t.submitted_date,t.approved_date,t.start_date,t.completion_date,t.created_at,t.notes,e.full_name employee_name";
  String from=" from "+table+" t left join employees e on e.id=t.employee_id";
  if(!kind.equals("clearance")) {from+=" left join it_assets a on a.id=t.asset_id";select+=",a.asset_code";}
  if(Set.of("assignments","requests").contains(kind)) {from+=" left join subsidiaries s on s.id=t.subsidiary_id";select+=",s.name subsidiary_name";}
  if(kind.equals("returns")) select+=",case when t.status='PENDING' and t.expected_date<CURRENT_DATE then 'OVERDUE' else t.status end display_status";
  from+=" where "+scope(actor,kind)+" and lower(concat_ws(' ',e.full_name,e.email"+(!kind.equals("clearance")?",a.asset_code":"")+")) like :search";
  if(!status.isEmpty()) {
   if(kind.equals("audit-logs")) from+=" and t.action=:status";
   else if(kind.equals("returns")) from+=" and (case when t.status='PENDING' and t.expected_date<CURRENT_DATE then 'OVERDUE' else t.status end)=:status";
   else from+=" and t.status=:status";
  }
  if(assetId!=null&&!kind.equals("clearance")) from+=" and t.asset_id=:asset";
  if(employeeId!=null) from+=" and t.employee_id=:employee";
  long count=jdbc.queryForObject("select count(*)"+from,p,Long.class);
  return new Page(jdbc.queryForList("select "+select+from+" order by t.created_at desc,t.id limit :limit offset :offset",p),count,page,size);
 }
 static void checkPage(int page,int size) { if(page<0||page>100000||size<1||size>100) throw new BadRequestException("Invalid pagination (size 1–100)"); }
 public Map<String,Object> detail(UUID id,AuthenticatedEmployee actor) {
  service.view(id,actor); var p=Map.of("id",id);
  var rows=jdbc.queryForList("select a.* from it_assets a where a.id=:id",p); if(rows.isEmpty()) throw new NotFoundException("Asset not found");
  Map<String,Object> result=new LinkedHashMap<>(rows.getFirst());
  String personal=AssetService.reader(actor)?"":" and x.employee_id=:actor"; var args=params(actor); args.put("id",id);
  result.put("assignments",jdbc.queryForList("select x.*,e.full_name employee_name,e.email employee_email,s.name subsidiary_name from asset_assignments x join employees e on e.id=x.employee_id join subsidiaries s on s.id=x.subsidiary_id where x.asset_id=:id"+personal+" order by x.created_at desc",args));
  result.put("repair_status",jdbc.queryForObject("select coalesce((select status from asset_repairs where asset_id=:id order by created_at desc limit 1),'NOT_REQUIRED')",p,String.class));
  String own=AssetService.manager(actor)?"":" and employee_id=:actor";
  result.put("replacement_status",jdbc.queryForObject("select coalesce((select status from asset_replacement_requests where asset_id=:id"+own+" order by created_at desc limit 1),'NOT_REQUIRED')",args,String.class));
  result.put("return_status",jdbc.queryForObject("select coalesce((select case when status='PENDING' and expected_date<CURRENT_DATE then 'OVERDUE' else status end from asset_returns where asset_id=:id"+own+" order by created_at desc limit 1),'NOT_APPLICABLE')",args,String.class));
  if(AssetService.manager(actor)||!AssetService.reader(actor)) result.put("issues",jdbc.queryForList("select title,description,category,priority,status,created_at,notes from asset_issues where asset_id=:id"+own+" order by created_at desc",args));
  result.put("clearance_status",jdbc.queryForObject("select coalesce((select c.status from employee_asset_clearances c join employee_clearance_items ci on ci.clearance_id=c.id join asset_assignments x on x.id=ci.assignment_id where x.asset_id=:id"+(AssetService.reader(actor)?"":" and c.employee_id=:actor")+" order by c.created_at desc limit 1),'NOT_REQUIRED')",args,String.class));
  return result;
 }
 public List<Map<String,Object>> history(UUID id,AuthenticatedEmployee actor) {
  if(actor.role().name().equals("HR")) throw new ForbiddenException("HR may view assignments and clearance, not asset audit history");
  service.view(id,actor); var p=params(actor);p.put("id",id);
  return jdbc.queryForList("select l.*,e.full_name actor_name from asset_audit_logs l left join employees e on e.id=l.created_by where l.asset_id=:id"+(AssetService.reader(actor)?"":" and l.employee_id=:actor")+" order by l.created_at desc",p);
 }
 public List<Map<String,Object>> employees(String search,AuthenticatedEmployee actor) {
  AssetService.hr(actor); return jdbc.queryForList("select id,full_name,email,is_active,subsidiary_id from employees where lower(concat_ws(' ',full_name,email)) like :search order by full_name limit 50",Map.of("search","%"+search.toLowerCase(Locale.ROOT)+"%"));
 }
 public List<Map<String,Object>> subsidiaries(AuthenticatedEmployee actor) { AssetService.hr(actor); return jdbc.queryForList("select id,name,active from subsidiaries order by name",Map.of()); }
 public Map<String,Object> dashboard(AuthenticatedEmployee actor) {
  AssetService.hr(actor); Map<String,Object> result=new LinkedHashMap<>();
  for(var metric:Map.of("pendingAssetRequests","select count(*) from asset_requests where status='PENDING'","totalAssets","select count(*) from it_assets","availableAssets","select count(*) from it_assets where status='AVAILABLE' and not exists(select 1 from asset_assignments x where x.asset_id=it_assets.id and x.status='ACTIVE') and not exists(select 1 from asset_requests r where r.asset_id=it_assets.id and r.status='PENDING')","assignedAssets","select count(*) from it_assets where status='ASSIGNED'","underRepair","select count(*) from it_assets where status='UNDER_REPAIR'","damagedAssets","select count(*) from it_assets where condition in ('DAMAGED','UNUSABLE')","pendingReplacements","select count(*) from asset_replacement_requests where status in ('PENDING','UNDER_REVIEW','APPROVED')","pendingReturns","select count(*) from asset_returns where status in ('PENDING','OVERDUE')","pendingClearances","select count(*) from employee_asset_clearances where status in ('PENDING','NOT_CLEARED')").entrySet()) result.put(metric.getKey(),jdbc.queryForObject(metric.getValue(),Map.of(),Long.class));
  for(var group:Map.of("byType","asset_type","byStatus","status","byCondition","condition").entrySet()) result.put(group.getKey(),jdbc.queryForList("select "+group.getValue()+" label,count(*) value from it_assets group by "+group.getValue()+" order by 1",Map.of()));
  result.put("bySubsidiary",jdbc.queryForList("select s.name label,count(*) value from asset_assignments x join subsidiaries s on s.id=x.subsidiary_id where x.status='ACTIVE' and x.approval_status='APPROVED' group by s.name order by s.name",Map.of()));
  if(AssetService.manager(actor)) {
   result.put("monthlyRepairs",jdbc.queryForList("select to_char(m.month,'YYYY-MM') label,count(r.id) value from generate_series(date_trunc('month',CURRENT_DATE)-interval '11 months',date_trunc('month',CURRENT_DATE),interval '1 month') m(month) left join asset_repairs r on date_trunc('month',r.created_at)=m.month group by m.month order by m.month",Map.of()));
   result.put("recentActivity",jdbc.queryForList("select l.action,l.created_at,l.notes,a.asset_code,e.full_name actor_name from asset_audit_logs l left join it_assets a on a.id=l.asset_id left join employees e on e.id=l.created_by order by l.created_at desc limit 20",Map.of()));
  }
  return result;
 }
 public Map<String,Object> clearance(UUID id,AuthenticatedEmployee actor) {
  AssetService.hr(actor); var rows=jdbc.queryForList("select c.*,e.full_name,e.email,e.employee_number,s.name subsidiary_name,o.full_name officer from employee_asset_clearances c join employees e on e.id=c.employee_id left join subsidiaries s on s.id=e.subsidiary_id left join employees o on o.id=c.cleared_by where c.id=:id",Map.of("id",id));
  if(rows.isEmpty()) throw new NotFoundException("Clearance not found"); var out=new LinkedHashMap<>(rows.getFirst());
  out.put("assets",jdbc.queryForList("select a.asset_code,a.serial_number,a.condition,x.status,x.assignment_date,r.actual_date,r.condition return_condition from employee_clearance_items i join asset_assignments x on x.id=i.assignment_id join it_assets a on a.id=x.asset_id left join asset_returns r on r.assignment_id=x.id and r.status='RETURNED' where i.clearance_id=:id order by x.assignment_date",Map.of("id",id)));
  Object employee=out.get("employee_id");
  out.put("outstandingAssets",jdbc.queryForList("select a.asset_code,x.approval_status from asset_assignments x join it_assets a on a.id=x.asset_id where x.employee_id=:id and x.status='ACTIVE'",Map.of("id",employee)));
  out.put("unresolvedIssues",jdbc.queryForObject("select count(*) from asset_issues where employee_id=:id and status not in ('RESOLVED','CLOSED')",Map.of("id",employee),Long.class));
  out.put("outstandingReturns",jdbc.queryForObject("select count(*) from asset_returns where employee_id=:id and status in ('PENDING','OVERDUE')",Map.of("id",employee),Long.class)); return out;
 }
 public List<Map<String,Object>> report(String report,AuthenticatedEmployee actor) {
  AssetService.hr(actor);
  if(report.equals("CLEARANCE")) return jdbc.queryForList("select e.full_name,e.email,c.status,c.clearance_date,o.full_name officer from employee_asset_clearances c join employees e on e.id=c.employee_id left join employees o on o.id=c.cleared_by order by c.created_at desc",Map.of());
  if(report.equals("BY_EMPLOYEE")) return jdbc.queryForList("select e.full_name,e.email,count(*) assets from asset_assignments x join employees e on e.id=x.employee_id where x.status='ACTIVE' and x.approval_status='APPROVED' group by e.id order by e.full_name",Map.of());
  if(report.equals("BY_SUBSIDIARY")) return jdbc.queryForList("select s.name subsidiary,count(*) assets from asset_assignments x join subsidiaries s on s.id=x.subsidiary_id where x.status='ACTIVE' and x.approval_status='APPROVED' group by s.id order by s.name",Map.of());
  AssetService.manage(actor);
  return switch(report) {
   case "INVENTORY","AVAILABLE","DAMAGED","UNDER_REPAIR" -> jdbc.queryForList("select a.asset_code,a.asset_type,a.brand,a.model,a.serial_number,a.condition,a.status,a.purchase_date,e.full_name employee,s.name subsidiary from it_assets a left join asset_assignments x on x.asset_id=a.id and x.status='ACTIVE' and x.approval_status='APPROVED' left join employees e on e.id=x.employee_id left join subsidiaries s on s.id=x.subsidiary_id"+switch(report){case "AVAILABLE"->" where a.status='AVAILABLE' and not exists(select 1 from asset_assignments q where q.asset_id=a.id and q.status='ACTIVE') and not exists(select 1 from asset_requests r where r.asset_id=a.id and r.status='PENDING')";case "DAMAGED"->" where a.condition in ('DAMAGED','UNUSABLE')";case "UNDER_REPAIR"->" where a.status='UNDER_REPAIR'";default->"";}+" order by a.asset_code",Map.of());
   case "REPAIR_COSTS" -> jdbc.queryForList("select a.asset_code,count(*) repairs,coalesce(sum(r.estimated_cost),0) estimated_cost,coalesce(sum(r.actual_cost),0) actual_cost from asset_repairs r join it_assets a on a.id=r.asset_id group by a.id order by a.asset_code",Map.of());
   case "PENDING_REPLACEMENTS" -> jdbc.queryForList("select a.asset_code,e.full_name,r.reason,r.status,r.created_at from asset_replacement_requests r join it_assets a on a.id=r.asset_id join employees e on e.id=r.employee_id where r.status in ('PENDING','UNDER_REVIEW','APPROVED') order by r.created_at",Map.of());
   case "OUTSTANDING_RETURNS" -> jdbc.queryForList("select a.asset_code,e.full_name,r.expected_date,case when r.expected_date<CURRENT_DATE then 'OVERDUE' else r.status end status from asset_returns r join it_assets a on a.id=r.asset_id join employees e on e.id=r.employee_id where r.status in ('PENDING','OVERDUE') order by r.expected_date",Map.of());
   default -> throw new BadRequestException("Unknown asset report");
  };
 }
}
