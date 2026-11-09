package com.adesa.attendance.assets;
import com.adesa.attendance.security.AuthenticatedEmployee;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.*;
import java.util.*;
import static com.adesa.attendance.assets.AssetRequests.*;

@RestController
@RequestMapping("/api/v1/assets")
@PreAuthorize("isAuthenticated()")
public class AssetController {
 private final AssetService service;
 private final AssetQueries queries;
 public AssetController(AssetService service,AssetQueries queries) {this.service=service;this.queries=queries;}
 private AuthenticatedEmployee actor(Authentication a) {return AuthenticatedEmployee.from(a);}
 @GetMapping("/available") public AssetQueries.Page available(@RequestParam(defaultValue="") String search,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return queries.availableAssets(search,page,size);}
 @GetMapping("/request-subsidiaries") public List<Map<String,Object>> requestSubsidiaries() {return queries.requestSubsidiaries();}
 @GetMapping("/requests") public AssetQueries.Page requests(Authentication a,@RequestParam(defaultValue="") String search,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return queries.records("requests",actor(a),search,status,null,null,page,size);}
 @PostMapping("/requests") @ResponseStatus(HttpStatus.CREATED) public Map<String,UUID> requestAsset(@Valid @RequestBody DeviceRequestInput r,Authentication a) {return Map.of("id",service.requestAsset(r,actor(a)));}
 @PostMapping("/requests/{id}/approve") @ResponseStatus(HttpStatus.NO_CONTENT) public void approveRequest(@PathVariable UUID id,@Valid @RequestBody Decision r,Authentication a) {service.requestDecision(id,true,r,actor(a));}
 @PostMapping("/requests/{id}/reject") @ResponseStatus(HttpStatus.NO_CONTENT) public void rejectRequest(@PathVariable UUID id,@Valid @RequestBody Decision r,Authentication a) {service.requestDecision(id,false,r,actor(a));}
 @PostMapping("/repairs/requests") @ResponseStatus(HttpStatus.CREATED) public Map<String,UUID> requestRepair(@Valid @RequestBody EmployeeRepairRequest r,Authentication a) {return Map.of("id",service.requestRepair(r,actor(a)));}
 @GetMapping public AssetQueries.Page inventory(Authentication a,@RequestParam(defaultValue="") String search,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String condition,@RequestParam(defaultValue="") String type,@RequestParam(required=false) UUID subsidiary,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="assetCode") String sort,@RequestParam(defaultValue="asc") String direction) {return queries.inventory(actor(a),search,status,condition,type,subsidiary,page,size,sort,direction);}
 @GetMapping("/{id}") public Map<String,Object> detail(@PathVariable UUID id,Authentication a) {return queries.detail(id,actor(a));}
 @GetMapping("/{id}/history") public List<Map<String,Object>> history(@PathVariable UUID id,Authentication a) {return queries.history(id,actor(a));}
 @PutMapping("/employees/{id}/subsidiary") @ResponseStatus(HttpStatus.NO_CONTENT) public void employeeSubsidiary(@PathVariable UUID id,@Valid @RequestBody EmployeeSubsidiary r,Authentication a) {service.employeeSubsidiary(id,r,actor(a));}
 @PostMapping("/{id}/account-loss") @ResponseStatus(HttpStatus.NO_CONTENT) public void accountLoss(@PathVariable UUID id,@Valid @RequestBody Decision r,Authentication a) {service.accountLoss(id,r,actor(a));}
 @GetMapping("/dashboard") public Map<String,Object> dashboard(Authentication a) {return queries.dashboard(actor(a));}
 @GetMapping("/employees") public List<Map<String,Object>> employees(@RequestParam(defaultValue="") String search,Authentication a) {return queries.employees(search,actor(a));}
 @GetMapping("/subsidiaries") public List<Map<String,Object>> subsidiaries(Authentication a) {return queries.subsidiaries(actor(a));}
 @GetMapping("/clearance/{id}/summary") public Map<String,Object> summary(@PathVariable UUID id,Authentication a) {return queries.clearance(id,actor(a));}
 @GetMapping("/clearance/{id}/pdf") public ResponseEntity<byte[]> clearancePdf(@PathVariable UUID id,Authentication a) {return file(AssetReports.clearance(queries.clearance(id,actor(a))),"application/pdf","clearance.pdf");}
 @GetMapping("/clearance/employee/{employeeId}") public AssetQueries.Page employeeClearance(@PathVariable UUID employeeId,Authentication a) {return queries.records("clearance",actor(a),"","",null,employeeId,0,100);}
 @GetMapping("/reports") public List<Map<String,Object>> reports(@RequestParam(defaultValue="INVENTORY") String report,Authentication a) {return queries.report(report,actor(a));}
 @GetMapping("/reports/export") public ResponseEntity<byte[]> export(@RequestParam(defaultValue="INVENTORY") String report,@RequestParam(defaultValue="csv") String format,Authentication a) {
  var rows=queries.report(report,actor(a));
  if(!Set.of("csv","pdf").contains(format)) throw new com.adesa.attendance.common.BadRequestException("Use CSV or PDF");
  return file(format.equals("pdf")?AssetReports.pdf(report,rows):AssetReports.csv(rows),format.equals("pdf")?"application/pdf":"text/csv;charset=UTF-8","assets."+format);
 }
 private ResponseEntity<byte[]> file(byte[] bytes,String type,String name) {return ResponseEntity.ok().contentType(MediaType.parseMediaType(type)).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+name+"\"").header(HttpHeaders.CACHE_CONTROL,"no-store").body(bytes);}
 @GetMapping("/assignments") public AssetQueries.Page assignments(Authentication a,@RequestParam(defaultValue="") String search,@RequestParam(defaultValue="") String status,@RequestParam(required=false) UUID assetId,@RequestParam(required=false) UUID employeeId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return queries.records("assignments",actor(a),search,status,assetId,employeeId,page,size);}
 @GetMapping("/issues") public AssetQueries.Page issues(Authentication a,@RequestParam(defaultValue="") String search,@RequestParam(defaultValue="") String status,@RequestParam(required=false) UUID assetId,@RequestParam(required=false) UUID employeeId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return queries.records("issues",actor(a),search,status,assetId,employeeId,page,size);}
 @GetMapping("/repairs") public AssetQueries.Page repairs(Authentication a,@RequestParam(defaultValue="") String search,@RequestParam(defaultValue="") String status,@RequestParam(required=false) UUID assetId,@RequestParam(required=false) UUID employeeId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return queries.records("repairs",actor(a),search,status,assetId,employeeId,page,size);}
 @GetMapping("/replacements") public AssetQueries.Page replacements(Authentication a,@RequestParam(defaultValue="") String search,@RequestParam(defaultValue="") String status,@RequestParam(required=false) UUID assetId,@RequestParam(required=false) UUID employeeId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return queries.records("replacements",actor(a),search,status,assetId,employeeId,page,size);}
 @GetMapping("/returns") public AssetQueries.Page returns(Authentication a,@RequestParam(defaultValue="") String search,@RequestParam(defaultValue="") String status,@RequestParam(required=false) UUID assetId,@RequestParam(required=false) UUID employeeId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return queries.records("returns",actor(a),search,status,assetId,employeeId,page,size);}
 @GetMapping("/clearance") public AssetQueries.Page clearance(Authentication a,@RequestParam(defaultValue="") String search,@RequestParam(defaultValue="") String status,@RequestParam(required=false) UUID assetId,@RequestParam(required=false) UUID employeeId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return queries.records("clearance",actor(a),search,status,assetId,employeeId,page,size);}
 @GetMapping("/audit-logs") public AssetQueries.Page auditlogs(Authentication a,@RequestParam(defaultValue="") String search,@RequestParam(defaultValue="") String status,@RequestParam(required=false) UUID assetId,@RequestParam(required=false) UUID employeeId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return queries.records("audit-logs",actor(a),search,status,assetId,employeeId,page,size);}
 @PostMapping("") @ResponseStatus(HttpStatus.CREATED) public Map<String,UUID> register(@Valid @RequestBody Registration r,Authentication a) {return Map.of("id",service.register(r,actor(a)));}
 @PostMapping("/assignments") @ResponseStatus(HttpStatus.CREATED) public Map<String,UUID> assign(@Valid @RequestBody Assign r,Authentication a) {return Map.of("id",service.assign(r,actor(a)));}
 @PostMapping("/issues") @ResponseStatus(HttpStatus.CREATED) public Map<String,UUID> report(@Valid @RequestBody Fault r,Authentication a) {return Map.of("id",service.report(r,actor(a)));}
 @PostMapping("/repairs") @ResponseStatus(HttpStatus.CREATED) public Map<String,UUID> repair(@Valid @RequestBody RepairRequest r,Authentication a) {return Map.of("id",service.repair(r,actor(a)));}
 @PostMapping("/replacements") @ResponseStatus(HttpStatus.CREATED) public Map<String,UUID> replacement(@Valid @RequestBody ReplacementRequest r,Authentication a) {return Map.of("id",service.replacement(r,actor(a)));}
 @PostMapping("/returns") @ResponseStatus(HttpStatus.CREATED) public Map<String,UUID> initiateReturn(@Valid @RequestBody ReturnRequest r,Authentication a) {return Map.of("id",service.initiateReturn(r,actor(a)));}
 @PostMapping("/clearance") @ResponseStatus(HttpStatus.CREATED) public Map<String,UUID> clearance(@Valid @RequestBody ClearanceRequest r,Authentication a) {return Map.of("id",service.clearance(r,actor(a)));}
 @PostMapping("/subsidiaries") @ResponseStatus(HttpStatus.CREATED) public Map<String,UUID> subsidiary(@Valid @RequestBody SubsidiaryRequest r,Authentication a) {return Map.of("id",service.subsidiary(r,actor(a)));}
 @PutMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void edit(@PathVariable UUID id,@Valid @RequestBody Edit r,Authentication a) {service.edit(id,r,actor(a));}
 @PostMapping("/{id}/status/{status}") @ResponseStatus(HttpStatus.NO_CONTENT) public void terminal(@PathVariable UUID id,@PathVariable String status,@Valid @RequestBody Decision r,Authentication a) {service.terminal(id,status,r,actor(a));}
 @PatchMapping("/issues/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void issue(@PathVariable UUID id,@Valid @RequestBody IssueUpdate r,Authentication a) {service.issue(id,r,actor(a));}
 @PatchMapping("/repairs/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void repairUpdate(@PathVariable UUID id,@Valid @RequestBody RepairUpdate r,Authentication a) {service.repairUpdate(id,r,actor(a));}
 @PostMapping("/replacements/{id}/fulfil") @ResponseStatus(HttpStatus.NO_CONTENT) public void fulfil(@PathVariable UUID id,@Valid @RequestBody Fulfil r,Authentication a) {service.fulfil(id,r,actor(a));}
 @PostMapping("/returns/{id}/complete") @ResponseStatus(HttpStatus.NO_CONTENT) public void receive(@PathVariable UUID id,@Valid @RequestBody ReturnReceipt r,Authentication a) {service.receive(id,r,actor(a));}
 @PostMapping("/assignments/{id}/approve") @ResponseStatus(HttpStatus.NO_CONTENT) public void assignmentsapprove(@PathVariable UUID id,@Valid @RequestBody Decision r,Authentication a) {service.assignmentDecision(id,true,r,actor(a));}
 @PostMapping("/assignments/{id}/reject") @ResponseStatus(HttpStatus.NO_CONTENT) public void assignmentsreject(@PathVariable UUID id,@Valid @RequestBody Decision r,Authentication a) {service.assignmentDecision(id,false,r,actor(a));}
 @PostMapping("/clearance/{id}/approve") @ResponseStatus(HttpStatus.NO_CONTENT) public void clearanceapprove(@PathVariable UUID id,@Valid @RequestBody Decision r,Authentication a) {service.clearanceDecision(id,true,r,actor(a));}
 @PostMapping("/clearance/{id}/reject") @ResponseStatus(HttpStatus.NO_CONTENT) public void clearancereject(@PathVariable UUID id,@Valid @RequestBody Decision r,Authentication a) {service.clearanceDecision(id,false,r,actor(a));}
 @PostMapping("/replacements/{id}/approve") @ResponseStatus(HttpStatus.NO_CONTENT) public void replacementapprove(@PathVariable UUID id,@Valid @RequestBody Decision r,Authentication a) {service.replacementDecision(id,"APPROVED",r,actor(a));}
 @PostMapping("/replacements/{id}/reject") @ResponseStatus(HttpStatus.NO_CONTENT) public void replacementreject(@PathVariable UUID id,@Valid @RequestBody Decision r,Authentication a) {service.replacementDecision(id,"REJECTED",r,actor(a));}
 @PostMapping("/replacements/{id}/review") @ResponseStatus(HttpStatus.NO_CONTENT) public void replacementreview(@PathVariable UUID id,@Valid @RequestBody Decision r,Authentication a) {service.replacementDecision(id,"UNDER_REVIEW",r,actor(a));}
}
