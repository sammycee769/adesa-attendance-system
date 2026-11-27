package com.adesa.attendance.vendor;
import java.util.Map;import org.springframework.web.bind.annotation.*;import org.springframework.http.*;import org.springframework.web.server.ResponseStatusException;import org.springframework.dao.DataIntegrityViolationException;
@RestControllerAdvice(assignableTypes={VendorPublicController.class,VendorAdminController.class})
public class VendorErrors {
 @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> policy(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).header("Cache-Control","no-store").body(Map.of("message",e.getReason()==null?"The vendor request could not be completed.":e.getReason()));}
 @ExceptionHandler({IllegalArgumentException.class,ClassCastException.class,NullPointerException.class}) public ResponseEntity<?> invalid(Exception e){return ResponseEntity.badRequest().header("Cache-Control","no-store").body(Map.of("message","Check the required fields, invitation and file formats."));}
 @ExceptionHandler(DataIntegrityViolationException.class) public ResponseEntity<?> conflict(Exception e){return ResponseEntity.status(409).header("Cache-Control","no-store").body(Map.of("message","This submission conflicts with an existing record. Refresh the form or contact the administrator."));}
}
