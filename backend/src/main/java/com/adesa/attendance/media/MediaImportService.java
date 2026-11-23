package com.adesa.attendance.media;

import com.adesa.attendance.common.ForbiddenException;
import com.adesa.attendance.common.NotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MediaImportService {
    private final JdbcTemplate jdbc;
    private final ExistingMediaWorkbook workbook;
    private final ObjectMapper mapper;

    public MediaImportService(
            ExistingMediaWorkbook workbook, ObjectMapper mapper,
            @Value("${ADESA_MEDIA_DB_URL:}") String url,
            @Value("${ADESA_MEDIA_DB_USERNAME:}") String user,
            @Value("${ADESA_MEDIA_DB_PASSWORD:}") String password) {
        this.workbook = workbook;
        this.mapper = mapper;
        // Module credentials can call narrowly scoped functions and have no table grants.
        this.jdbc = url.isBlank() || user.isBlank() || password.isBlank()
                ? null : new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        if (jdbc != null) jdbc.setQueryTimeout(10);
    }

    public String category(UUID actor) {
        return jdbc == null ? null : jdbc.queryForObject("select media_private.category(?)", String.class, actor);
    }

    void require(UUID actor) {
        if (jdbc == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Media import is not configured.");
        if (category(actor) == null) throw new ForbiddenException("You do not have access to Media board imports.");
    }

    JdbcTemplate connection(UUID actor) {
        require(actor);
        return jdbc;
    }

    public ExistingMediaWorkbook.Preview preview(UUID actor, byte[] bytes) {
        require(actor);
        return workbook.inspect(bytes);
    }

    public record StageResult(UUID id, int inserted, int updated, int skipped, int flagged) {}

    public StageResult stage(UUID actor, String filename, byte[] bytes) {
        var preview = preview(actor, bytes);
        String digest;
        try {
            digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
        try {
            String snapshot = mapper.writeValueAsString(preview);
            return guarded(() -> jdbc.queryForObject(
                    "select * from media_private.stage_workbook(?, ?, ?, ?, cast(? as jsonb))",
                    (rs, ignored) -> {
                        boolean inserted = rs.getBoolean("inserted");
                        return new StageResult(rs.getObject("batch_id", UUID.class),
                                inserted ? 1 : 0, 0, inserted ? 0 : 1,
                                (int) preview.findings().stream().map(f -> f.sheet() + ":" + f.row()).distinct().count());
                    }, actor, digest, filename, bytes, snapshot));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not encode workbook preview", ex);
        }
    }

    public List<Map<String, Object>> history(UUID actor) {
        require(actor);
        return guarded(() -> jdbc.queryForList("select * from media_private.workbook_history(?)", actor));
    }

    public JsonNode storedPreview(UUID actor, UUID batch) {
        require(actor);
        String value = guarded(() -> jdbc.queryForObject(
                "select media_private.workbook_preview(?, ?)::text", String.class, actor, batch));
        if (value == null) throw new NotFoundException("Workbook batch does not exist.");
        return parse(value);
    }

    public record BoardReviewInput(
            @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(1_000_000) int revision,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 200) String location,
            @jakarta.validation.constraints.Size(max = 200) String client,
            @jakarta.validation.constraints.Size(max = 200) String vendor,
            java.time.LocalDate vendorExpiry,
            java.time.LocalDate clientExpiry,
            boolean vendorApproved,
            boolean clientApproved,
            @jakarta.validation.constraints.Size(max = 1000) String notes) {}

    public JsonNode boardReviews(UUID actor, UUID batch) {
        require(actor);
        return parse(guarded(() -> jdbc.queryForObject("select media_private.board_reviews(?, ?)::text",
                String.class, actor, batch)));
    }

    public JsonNode saveBoardReview(UUID actor, UUID batch, int row, BoardReviewInput input) {
        var snapshot = storedPreview(actor, batch);
        JsonNode draft = null;
        for (JsonNode candidate : snapshot.path("drafts")) {
            if (candidate.path("row").asInt() == row) {
                draft = candidate;
                break;
            }
        }
        if (draft == null) throw new NotFoundException("Staged board does not exist.");
        if (input.vendorApproved() && input.vendorExpiry() == null || input.clientApproved() && input.clientExpiry() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter an expiry date before approving it.");
        }
        JsonNode startText = draft.path("dates").path("initial Start Date");
        if (startText.isTextual()) {
            var start = java.time.LocalDate.parse(startText.asText());
            if (input.vendorExpiry() != null && input.vendorExpiry().isBefore(start)
                    || input.clientExpiry() != null && input.clientExpiry().isBefore(start)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expiry cannot be before the board start date.");
            }
        }
        var renewal = draft.path("dates").path("Renewed Date");
        if (input.vendorApproved() && renewal.isTextual()
                && input.vendorExpiry().isBefore(java.time.LocalDate.parse(renewal.asText()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vendor expiry cannot precede the renewal date.");
        }
        try {
            var detail = mapper.valueToTree(input);
            ((com.fasterxml.jackson.databind.node.ObjectNode) detail).remove("revision");
            String payload = mapper.writeValueAsString(detail);
            return parse(guarded(() -> jdbc.queryForObject(
                    "select media_private.save_board_review(?, ?, ?, ?, cast(? as jsonb))::text",
                    String.class, actor, batch, row, input.revision(), payload)));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not encode board review", ex);
        }
    }

    JsonNode parse(String value) {
        try {
            return mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored Media data could not be read", ex);
        }
    }

    public String discrepancyCsv(UUID actor, UUID batch) {
        var result = new StringBuilder("Sheet,Row,Column,Code,Message\r\n");
        for (JsonNode f : storedPreview(actor, batch).path("findings")) {
            result.append(csv(f.path("sheet").asText())).append(',').append(f.path("row").asInt()).append(',')
                    .append(csv(f.path("column").asText())).append(',').append(csv(f.path("code").asText()))
                    .append(',').append(csv(f.path("message").asText())).append("\r\n");
        }
        return result.toString();
    }

    private static String csv(String value) {
        if (value.stripLeading().matches("(?s)^[=+@-].*")) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    static <T> T guarded(java.util.function.Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException ex) {
            if (ex.getMostSpecificCause() instanceof java.sql.SQLException sql) {
                switch (sql.getSQLState()) {
                    case "42501" -> throw new ForbiddenException("You do not have permission for this Media action.");
                    case "P0002" -> throw new NotFoundException("The Media record does not exist.");
                    case "40001" -> throw new ResponseStatusException(HttpStatus.CONFLICT, "This record changed. Reload it and try again.");
                    case "22023" -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            sql.getMessage().lines().findFirst().orElse("Check the Media record.").replaceFirst("^ERROR: ", ""));
                    case "22007", "22P02", "23502", "23514" -> throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "Check required fields, dates, references, and amounts.");
                    default -> { }
                }
            }
            throw ex;
        }
    }
}
