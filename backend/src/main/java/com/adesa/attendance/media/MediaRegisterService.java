package com.adesa.attendance.media;

import static com.adesa.attendance.media.MediaImportService.guarded;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class MediaRegisterService {
    private final MediaImportService imports;

    MediaRegisterService(MediaImportService imports) { this.imports = imports; }

    JsonNode register(UUID actor) {
        var db = imports.connection(actor);
        return imports.parse(guarded(() -> db.queryForObject("select media_private.register(?)::text", String.class, actor)));
    }

    JsonNode reminders(UUID actor) {
        var db = imports.connection(actor);
        return imports.parse(guarded(() -> db.queryForObject("select media_private.reminder_history(?)::text", String.class, actor)));
    }

    JsonNode reminderPlan(UUID actor) {
        var db = imports.connection(actor);
        return imports.parse(guarded(() -> db.queryForObject("select media_private.reminder_plan(?)::text", String.class, actor)));
    }

    JsonNode links(UUID actor, UUID batch) {
        var db = imports.connection(actor);
        return imports.parse(guarded(() -> db.queryForObject("select media_private.import_links(?, ?)::text",
                String.class, actor, batch)));
    }

    UUID link(UUID actor, JsonNode data) {
        var db = imports.connection(actor);
        if (!data.isObject() || data.toString().length() > 4000) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid Media board link.");
        }
        return guarded(() -> db.queryForObject("select media_private.link_review(?, cast(? as jsonb))",
                UUID.class, actor, data.toString()));
    }

    UUID completeImport(UUID actor, UUID batch, int row, JsonNode data) {
        var db = imports.connection(actor);
        if (!data.isObject() || data.toString().length() > 8000) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid workbook board details.");
        }
        return guarded(() -> db.queryForObject("select media_private.complete_import_board(?, ?, ?, cast(? as jsonb))",
                UUID.class, actor, batch, row, data.toString()));
    }

    UUID mutate(UUID actor, String action, String entity, JsonNode data) {
        var db = imports.connection(actor);
        if (!data.isObject() || data.toString().length() > 20_000) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid Media record.");
        }
        return guarded(() -> db.queryForObject("select media_private.mutate(?, ?, ?, cast(? as jsonb))",
                UUID.class, actor, action, entity, data.toString()));
    }
}
