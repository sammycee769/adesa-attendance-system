package com.adesa.attendance.media;

import com.adesa.attendance.common.ApiError;
import com.adesa.attendance.security.AuthenticatedEmployee;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/media")
public class MediaImportController {
    private final MediaImportService service;
    private final MediaRegisterService register;
    private final MediaReports reports;

    public MediaImportController(MediaImportService service, MediaRegisterService register, MediaReports reports) {
        this.service = service;
        this.register = register;
        this.reports = reports;
    }

    @GetMapping("/register")
    public com.fasterxml.jackson.databind.JsonNode register(Authentication auth) {
        return register.register(actor(auth));
    }

    @PostMapping("/register/{entity}/{action}")
    public Map<String, UUID> mutate(Authentication auth, @PathVariable String entity, @PathVariable String action,
                                    @RequestBody com.fasterxml.jackson.databind.JsonNode record) {
        return Map.of("id", register.mutate(actor(auth), action.toUpperCase(Locale.ROOT),
                entity.toUpperCase(Locale.ROOT), record));
    }

    @PostMapping("/imports/{id}/complete/{row}")
    public Map<String, UUID> completeImport(Authentication auth, @PathVariable UUID id, @PathVariable int row,
                                           @RequestBody com.fasterxml.jackson.databind.JsonNode record) {
        return Map.of("id", register.completeImport(actor(auth), id, row, record));
    }

    @GetMapping("/imports/{id}/links")
    public com.fasterxml.jackson.databind.JsonNode links(Authentication auth, @PathVariable UUID id) {
        return register.links(actor(auth), id);
    }

    @PostMapping("/register/link-import")
    public Map<String, UUID> link(Authentication auth, @RequestBody com.fasterxml.jackson.databind.JsonNode record) {
        return Map.of("id", register.link(actor(auth), record));
    }

    @GetMapping("/reminders/plan")
    public com.fasterxml.jackson.databind.JsonNode reminderPlan(Authentication auth) {
        return register.reminderPlan(actor(auth));
    }

    @GetMapping("/reminders")
    public com.fasterxml.jackson.databind.JsonNode reminders(Authentication auth) {
        return register.reminders(actor(auth));
    }

    @GetMapping("/reports/{report}/preview")
    public MediaReports.Table reportPreview(Authentication auth, @PathVariable MediaReports.Report report,
            @RequestParam(required=false) UUID board, @RequestParam(required=false) UUID vendor,
            @RequestParam(required=false) UUID client, @RequestParam(required=false) String status,
            @RequestParam(required=false) java.time.LocalDate from, @RequestParam(required=false) java.time.LocalDate to,
            @RequestParam(defaultValue="false") boolean archived) {
        return reports.dataset(actor(auth), report, new MediaReports.Filter(board,vendor,client,status,from,to,archived));
    }

    @GetMapping("/reports/{report}")
    public ResponseEntity<byte[]> report(Authentication auth, @PathVariable MediaReports.Report report,
            @RequestParam String format, @RequestParam(required=false) UUID board,
            @RequestParam(required=false) UUID vendor, @RequestParam(required=false) UUID client,
            @RequestParam(required=false) String status, @RequestParam(required=false) java.time.LocalDate from,
            @RequestParam(required=false) java.time.LocalDate to, @RequestParam(defaultValue="false") boolean archived) {
        var table = reports.dataset(actor(auth), report, new MediaReports.Filter(board,vendor,client,status,from,to,archived));
        byte[] contents = reports.export(table,report,format);
        var contentType = "pdf".equals(format) ? MediaType.APPLICATION_PDF
                : MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        return ResponseEntity.ok().contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"media-" + report.name().toLowerCase(Locale.ROOT) + "." + format + "\"")
                .body(contents);
    }

    @GetMapping("/access")
    public Map<String, Object> access(Authentication auth) {
        String category = service.category(actor(auth));
        return category == null ? Map.of("allowed", false) : Map.of("allowed", true, "category", category);
    }

    @PostMapping(value = "/imports/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ExistingMediaWorkbook.Preview preview(Authentication auth, @RequestParam MultipartFile workbook) throws IOException {
        filename(workbook);
        return service.preview(actor(auth), workbook.getBytes());
    }

    @PostMapping(value = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MediaImportService.StageResult stage(Authentication auth, @RequestParam MultipartFile workbook) throws IOException {
        return service.stage(actor(auth), filename(workbook), workbook.getBytes());
    }

    @GetMapping("/imports")
    public List<Map<String, Object>> history(Authentication auth) {
        return service.history(actor(auth));
    }

    @GetMapping("/imports/{id}")
    public com.fasterxml.jackson.databind.JsonNode staged(Authentication auth, @PathVariable UUID id) {
        return service.storedPreview(actor(auth), id);
    }

    @GetMapping("/imports/{id}/reviews")
    public com.fasterxml.jackson.databind.JsonNode reviews(Authentication auth, @PathVariable UUID id) {
        return service.boardReviews(actor(auth), id);
    }

    @PutMapping("/imports/{id}/reviews/{row}")
    public com.fasterxml.jackson.databind.JsonNode saveReview(Authentication auth, @PathVariable UUID id,
            @PathVariable int row, @jakarta.validation.Valid @RequestBody MediaImportService.BoardReviewInput input) {
        return service.saveBoardReview(actor(auth), id, row, input);
    }

    @GetMapping(value = "/imports/{id}/discrepancies", produces = "text/csv")
    public ResponseEntity<String> discrepancies(Authentication auth, @PathVariable UUID id) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"workbook-discrepancies.csv\"")
                .body(service.discrepancyCsv(actor(auth), id));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> status(ResponseStatusException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        return ResponseEntity.status(status).body(ApiError.of(status.value(), status.getReasonPhrase(),
                ex.getReason(), request.getRequestURI()));
    }

    private static UUID actor(Authentication auth) { return AuthenticatedEmployee.from(auth).employeeId(); }

    private static String filename(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.length() > 255 || name.chars().anyMatch(c -> c < 32 || c == '/' || c == '\\')
                || !name.toLowerCase(Locale.ROOT).endsWith(".xlsx") || file.isEmpty() || file.getSize() > ExistingMediaWorkbook.MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload an .xlsx file up to 512 KB.");
        }
        return name;
    }
}
