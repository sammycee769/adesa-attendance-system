package com.adesa.attendance.campaign;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DataIntegrityViolationException;
@RestControllerAdvice(assignableTypes={
    CampaignPublicController.class,CampaignStaffController.class
}
) class CampaignErrors {
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> policy(ResponseStatusException e){
        return ResponseEntity.status(e.getStatusCode()).header("Cache-Control","no-store").body(Map.of("message",e.getReason()==null?"Campaign request could not be completed.":e.getReason()));
    }
    @ExceptionHandler({
        IllegalArgumentException.class,ClassCastException.class,NullPointerException.class
    }
    ) ResponseEntity<?> validation(Exception e){
        return ResponseEntity.badRequest().body(Map.of("message","Check all required fields and their formats."));
    }
    @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<?> duplicate(Exception e){
        return ResponseEntity.status(409).body(Map.of("message","These details conflict with an existing registration or record. Resume the existing session or refresh current records."));
    }
}
