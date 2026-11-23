package com.adesa.attendance.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.adesa.attendance.employee.EmployeeRole;
import com.adesa.attendance.security.AuthenticatedEmployee;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import java.time.ZoneId;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.http.MediaType;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MediaImportIntegrationTest {
    @Container static final PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final String ROOT = "/api/v1/media";
    private static final String RUNTIME = "test_media_runtime";
    private static final String PASSWORD = "test-role-password";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate owner;
    @Autowired ObjectMapper json;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", db::getJdbcUrl);
        r.add("spring.datasource.username", db::getUsername);
        r.add("spring.datasource.password", db::getPassword);
        r.add("ADESA_MEDIA_DB_URL", db::getJdbcUrl);
        r.add("ADESA_MEDIA_DB_USERNAME", () -> RUNTIME);
        r.add("ADESA_MEDIA_DB_PASSWORD", () -> PASSWORD);
    }

    @BeforeAll static void provision(@Autowired JdbcTemplate owner) {
        owner.execute("create role test_media_runtime login password 'test-role-password'");
        owner.execute("grant usage on schema media_private to test_media_runtime");
        owner.execute("""
                grant execute on function media_private.category(uuid),
                    media_private.stage_workbook(uuid,text,text,bytea,jsonb),
                    media_private.workbook_history(uuid), media_private.workbook_preview(uuid,uuid),
                    media_private.board_reviews(uuid,uuid), media_private.save_board_review(uuid,uuid,int,int,jsonb),
                    media_private.register(uuid), media_private.mutate(uuid,text,text,jsonb),
                    media_private.link_review(uuid,jsonb), media_private.import_links(uuid,uuid),
                    media_private.reminder_history(uuid), media_private.reminder_plan(uuid),
                    media_private.complete_import_board(uuid,uuid,int,jsonb)
                to test_media_runtime
                """);
        owner.execute("create role test_media_worker login password 'test-role-password'");
        owner.execute("grant usage on schema media_private to test_media_worker");
        owner.execute("""
                grant execute on function media_private.prepare_reminders(date), media_private.claim_reminder(date),
                    media_private.finish_reminder(uuid,uuid,text) to test_media_worker
                """);
    }

    @Test void restrictsAccessAndPreservesOriginalAndAuditAcrossRetries() throws Exception {
        UUID admin = employee("ADMIN", null);
        UUID businessManager = employee("EMPLOYEE", "BUSINESS_MANAGER");
        UUID coo = employee("EMPLOYEE", "COO");
        UUID operator = employee("EMPLOYEE", "OPERATOR");
        UUID hr = employee("HR", null);
        UUID staff = employee("EMPLOYEE", null);
        var bytes = ExistingMediaWorkbookTest.fixture();
        mvc.perform(get(ROOT + "/imports")).andExpect(status().isUnauthorized());
        for (UUID denied : List.of(hr, staff)) {
            String role = denied.equals(hr) ? "HR" : "EMPLOYEE";
            mvc.perform(get(ROOT + "/access").with(as(denied, role))).andExpect(jsonPath("$.allowed").value(false));
            mvc.perform(multipart(ROOT + "/imports").file(upload(bytes)).with(csrf()).with(as(denied, role)))
                    .andExpect(status().isForbidden());
        }
        for (UUID allowed : List.of(admin, businessManager, coo, operator)) {
            String role = allowed.equals(admin) ? "ADMIN" : "EMPLOYEE";
            mvc.perform(multipart(ROOT + "/imports/preview").file(upload(bytes)).with(csrf()).with(as(allowed, role)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.drafts.length()").value(2));
        }
        assertThat(owner.queryForObject("select count(*) from media_private.import_batches", Long.class)).isZero();
        mvc.perform(multipart(ROOT + "/imports").file(upload(bytes)).with(csrf()).with(as(operator, "EMPLOYEE")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.inserted").value(1));
        mvc.perform(multipart(ROOT + "/imports").file(upload(bytes)).with(csrf()).with(as(coo, "EMPLOYEE")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.skipped").value(1));
        UUID batch = owner.queryForObject("select id from media_private.import_batches", UUID.class);
        assertThat(owner.queryForObject("select original from media_private.import_batches", byte[].class)).isEqualTo(bytes);
        assertThat(owner.queryForObject("select count(*) from media_private.import_audit", Long.class)).isEqualTo(1);
        mvc.perform(get(ROOT + "/imports/" + batch + "/discrepancies").with(as(businessManager, "EMPLOYEE")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("VENDOR_EXPIRY_CONFLICT")));
        mvc.perform(get(ROOT + "/imports/" + batch).with(as(staff, "EMPLOYEE"))).andExpect(status().isForbidden());

        var restricted = new JdbcTemplate(new DriverManagerDataSource(db.getJdbcUrl(), RUNTIME, PASSWORD));
        assertThatThrownBy(() -> restricted.execute("delete from media_private.import_batches")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> restricted.execute("select * from media_private.import_batches")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> restricted.queryForList("select * from media_private.workbook_history(?)", staff))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> restricted.execute("update media_private.import_audit set action='WORKBOOK_STAGED'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> owner.execute("truncate media_private.import_audit")).isInstanceOf(DataAccessException.class);

        verifyBoardAndLedger(batch, admin, businessManager, coo, operator, staff, restricted);
    }

    private void verifyBoardAndLedger(UUID batch, UUID admin, UUID businessManager, UUID coo, UUID operator,
                                     UUID staff, JdbcTemplate restricted) throws Exception {
        String reviewPath = ROOT + "/imports/" + batch + "/reviews/2";
        mvc.perform(put(reviewPath).with(as(staff,"EMPLOYEE")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("location","Ring Road")))).andExpect(status().isForbidden());
        var review = Map.of("revision",0,"location","Ring Road North","client","Test Client","vendor","Test Vendor",
                "vendorExpiry","2026-10-01","clientExpiry","2026-10-01","vendorApproved",true,"clientApproved",true,"notes","Confirmed source dates");
        mvc.perform(put(reviewPath).with(as(operator,"EMPLOYEE")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(review))).andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1)).andExpect(jsonPath("$.vendorApproved").value(true));
        mvc.perform(put(reviewPath).with(as(operator,"EMPLOYEE")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(review))).andExpect(status().isConflict());
        assertThat(owner.queryForObject("select count(*) from media_private.board_review_audit", Long.class)).isEqualTo(1);
        assertThat(owner.queryForObject("select snapshot->'drafts'->0->'values'->>'OOH Location' from media_private.import_batches where id=?",String.class,batch))
                .isEqualTo("Ring Road");
        UUID vendor = id(save("party",operator,"EMPLOYEE",Map.of("kind","VENDOR","name","Test Vendor")));
        UUID client = id(save("party",operator,"EMPLOYEE",Map.of("kind","CLIENT","name","Test Client")));
        UUID board = id(mvc.perform(post(ROOT + "/register/link-import").with(as(operator,"EMPLOYEE")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("sourceBatch",batch,"sourceRow",2,
                    "code","MEDIA-1","vendorId",vendor,"clientId",client)))));
        assertThat(owner.queryForObject("select count(*) from media_private.contracts where board_id=? and not confirmed",Long.class,board)).isEqualTo(2);
        mvc.perform(post(ROOT + "/register/link-import").with(as(operator,"EMPLOYEE")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("sourceBatch",batch,"sourceRow",2,"boardId",board))))
                .andExpect(status().isConflict());
        LocalDate day = LocalDate.now(ZoneId.of("Africa/Lagos"));
        LocalDate start = day.minusDays(5), end=day.plusDays(30);
        UUID vendorContract = confirm(board,"VENDOR",vendor,operator,start,end,100);
        UUID clientContract = confirm(board,"CLIENT",client,operator,start,end,150);
        save("contract",operator,"EMPLOYEE",Map.of("boardId",board,"kind","CLIENT","partyId",client,
                "startDate",start,"endDate",end.plusDays(1),"amount","150","confirmed",true)).andExpect(status().isConflict());
        UUID obligation = id(save("obligation",operator,"EMPLOYEE",Map.of("contractId",clientContract,"reference","INV-1",
                "amount","150.00","invoiceSent",true,"dueDate",day.plusDays(7))));
        command("payment","record",operator,"EMPLOYEE",
                Map.of("obligationId",obligation,"amount","45.20","paidOn",day,"reference","receipt-1"))
                .andExpect(status().isBadRequest());
        command("payment","record",operator,"EMPLOYEE",
                Map.of("obligationId",obligation,"amount","45.20","paidOn",day,"reference","receipt-1","slaLink","javascript:alert(1)"))
                .andExpect(status().isBadRequest());
        UUID payment = id(command("payment","record",operator,"EMPLOYEE",
                Map.of("obligationId",obligation,"amount","45.20","paidOn",day,"reference","receipt-1","slaLink","https://example.com/sla")));
        command("payment","record",operator,"EMPLOYEE",Map.of("obligationId",obligation,"amount","105","paidOn",day,"reference","too-large","slaLink","https://example.com/sla"))
                .andExpect(status().isBadRequest());
        command("payment","record",operator,"EMPLOYEE",Map.of("obligationId",obligation,"amount","1","paidOn",day,"reference","receipt-1","slaLink","https://example.com/sla"))
                .andExpect(status().isConflict());
        UUID corrected = id(command("payment","correct",operator,"EMPLOYEE",Map.of("id",payment,"amount","40.10",
                "paidOn",day,"reference","receipt-1","reason","Correct transfer amount","slaLink","https://example.com/sla")));
        assertThat(owner.queryForObject("select sla_link from media_private.transactions where id=?", String.class, corrected))
                .isEqualTo("https://example.com/sla");
        assertThat(owner.queryForObject("select media_private.net_paid(?)", java.math.BigDecimal.class, obligation))
                .isEqualByComparingTo("40.10");
        mvc.perform(get(ROOT + "/reports/PROFITABILITY/preview").param("board",board.toString()).with(as(coo,"EMPLOYEE")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows[0][5]").value("50.00")).andExpect(jsonPath("$.rows[0][7]").value("40.10"));
        for (String format : List.of("pdf","xlsx")) {
            byte[] exported = mvc.perform(get(ROOT + "/reports/BOARDS").param("format",format).with(as(operator,"EMPLOYEE")))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            assertThat(exported).startsWith(format.equals("pdf") ? new byte[]{37,80,68,70} : new byte[]{80,75});
        }
        mvc.perform(get(ROOT + "/reports/BOARDS").param("format","pdf").with(as(staff,"EMPLOYEE")))
                .andExpect(status().isForbidden());
        command("payment","reverse",operator,"EMPLOYEE",Map.of("id",corrected,"reason","Reconciled as returned"))
                .andExpect(status().isOk());
        assertThat(owner.queryForObject("select media_private.net_paid(?)",java.math.BigDecimal.class,obligation)).isZero();
        assertThatThrownBy(() -> restricted.execute("delete from media_private.transactions")).isInstanceOf(DataAccessException.class);

        var worker = new JdbcTemplate(new DriverManagerDataSource(db.getJdbcUrl(), "test_media_worker", PASSWORD));
        assertThat(worker.queryForObject("select media_private.prepare_reminders(?)",Integer.class,day)).isZero();
        owner.execute("""
                   insert into media_private.reminder_recipients(employee_id,verified_email)
                   select e.id,e.email from public.employees e join media_private.memberships m on e.id=m.employee_id
                   """);
        var jobs = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = jobs.submit(() -> worker.queryForObject("select media_private.prepare_reminders(?)",Integer.class,day));
            var second = jobs.submit(() -> worker.queryForObject("select media_private.prepare_reminders(?)",Integer.class,day));
            assertThat(List.of(first.get(10,java.util.concurrent.TimeUnit.SECONDS),second.get(10,java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(0,9);
        } finally {
            jobs.shutdownNow();
        }
        assertThat(worker.queryForObject("select media_private.prepare_reminders(?)",Integer.class,day)).isZero();
        String claim = worker.queryForObject("select media_private.claim_reminder(?)::text",String.class,day);
        assertThat(claim).isNotNull();
        JsonNode queued = json.readTree(claim);
        worker.queryForObject("select media_private.finish_reminder(?, ?, ?)::text",String.class,
                UUID.fromString(queued.path("id").asText()),UUID.fromString(queued.path("claim_token").asText()),"LOCAL_CAPTURED");
        assertThat(owner.queryForObject("select count(*) from media_private.reminder_attempts where status='LOCAL_CAPTURED'",Long.class)).isEqualTo(1);
        id(save("contract",operator,"EMPLOYEE",Map.of("boardId",board,"kind","VENDOR","partyId",vendor,
                "startDate",end.plusDays(1),"endDate",end.plusDays(90),"amount",110,"confirmed",true,"renewsId",vendorContract)));
        id(command("payment","record",operator,"EMPLOYEE",Map.of("obligationId",obligation,"amount","150","paidOn",day,"reference","receipt-full","slaLink","https://example.com/sla")));
        worker.queryForObject("select media_private.prepare_reminders(?)",Integer.class,day);
        assertThat(owner.queryForObject("select count(*) from media_private.reminders where status='PENDING' and entity_id in (?,?)",Long.class,vendorContract,obligation)).isZero();
        assertThat(owner.queryForObject("select count(*) from media_private.reminder_candidates(?) where stage=7 and entity_id=?",Long.class,end.minusDays(7),clientContract)).isEqualTo(1);
        assertThat(owner.queryForObject("select count(*) from media_private.reminder_candidates(?) where stage=0 and entity_id=?",Long.class,day.plusDays(7),obligation)).isZero();

        var captures = new java.util.ArrayList<String>();
        var mailSink = new com.adesa.attendance.mail.MailService() {
            @Override public void send(String to, String subject, String body, String html) {
                assertThat(subject).contains("ADESA Media");
                assertThat(body).contains("MEDIA-1", "/media/boards?board=" + board);
                assertThat(html).contains("View board");
                captures.add(to);
            }
            @Override public boolean isDeliveryEnabled() { return false; }
        };
        new MediaReminderWorker(new MediaImportService(new ExistingMediaWorkbook(),json,"","",""),mailSink,
                db.getJdbcUrl(),"test_media_worker",PASSWORD,false,"http://localhost:5175").tick();
        assertThat(captures).isNotEmpty().doesNotHaveDuplicates();
        assertThat(owner.queryForList("select verified_email from media_private.reminder_recipients",String.class))
                .containsAll(captures);

        verifyGuidedCompletion(batch, operator, staff, day);

        var lifecycle = Map.of("id",board,"revision",1,"reason","Review complete","acknowledgeHistory",true);
        command("board","archive",operator,"EMPLOYEE",lifecycle).andExpect(status().isForbidden());
        assertThatThrownBy(() -> restricted.queryForObject("select media_private.mutate(?, 'ARCHIVE', 'BOARD', cast(? as jsonb))",
                UUID.class,operator,json.writeValueAsString(lifecycle))).isInstanceOf(DataAccessException.class);
        command("board","archive",businessManager,"EMPLOYEE",Map.of("id",board,"revision",1,"reason","Review complete"))
                .andExpect(status().isBadRequest());
        command("board","archive",businessManager,"EMPLOYEE",lifecycle).andExpect(status().isOk());
        for (UUID member : List.of(businessManager,coo)) {
            for (String action : List.of("restore","purge")) command("board",action,member,"EMPLOYEE",
                Map.of("id",board,"revision",2,"reason","Lifecycle test","acknowledgeHistory",true)).andExpect(status().isForbidden());
        }
        command("board","purge",admin,"ADMIN",Map.of("id",board,"revision",2,"reason","Lifecycle test","acknowledgeHistory",true))
                .andExpect(status().isBadRequest());
        command("board","restore",admin,"ADMIN",Map.of("id",board,"revision",2,"reason","Required for review"))
                .andExpect(status().isOk());
        var disposable = id(save("party",operator,"EMPLOYEE",Map.of("kind","CLIENT","name","Disposable fixture")));
        command("party","archive",coo,"EMPLOYEE",Map.of("id",disposable,"revision",1,"reason","Unused fixture")).andExpect(status().isOk());
        command("party","purge",admin,"ADMIN",Map.of("id",disposable,"revision",2,"reason","Unused fixture","acknowledgeHistory",true))
                .andExpect(status().isOk());
        mvc.perform(get(ROOT + "/register").with(as(coo,"EMPLOYEE"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.audit[?(@.action == 'REVIEW_SAVED')]").isNotEmpty())
                .andExpect(jsonPath("$.audit[?(@.action == 'CORRECT')].before_values").isNotEmpty());
        mvc.perform(get(ROOT + "/register").with(as(operator,"EMPLOYEE"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.audit").isEmpty());
        assertThatThrownBy(() -> owner.execute("truncate media_private.audit")).isInstanceOf(DataAccessException.class);
    }

    private void verifyGuidedCompletion(UUID batch, UUID operator, UUID staff, LocalDate day) throws Exception {
        String path = ROOT + "/imports/" + batch + "/complete/3";
        Map<String, Object> vendor = Map.of("name","Guided Vendor","startDate",day,"endDate",day.plusDays(30),
                "amount","100.00","createObligation",true,"obligationAmount","85.00","dueDate",day.plusDays(7),"reference","GUIDE-V");
        Map<String, Object> client = Map.of("name","Guided Client","startDate",day,"endDate",day.plusDays(30),
                "amount","175.00","createObligation",false);
        var data = new java.util.HashMap<String,Object>(Map.of("revision",0,"confirmed",true,"location","Guided place",
                "vendor",vendor,"client",client));
        mvc.perform(post(path).with(as(staff,"EMPLOYEE")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(data))).andExpect(status().isForbidden());
        var missing = new java.util.HashMap<>(vendor);
        missing.remove("dueDate");
        data.put("vendor",missing);
        mvc.perform(post(path).with(as(operator,"EMPLOYEE")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(data))).andExpect(status().isBadRequest());
        assertThat(owner.queryForObject("select count(*) from media_private.boards where location='Guided place'",Long.class)).isZero();
        assertThat(owner.queryForObject("select count(*) from media_private.parties where name='Guided Vendor'",Long.class)).isZero();
        data.put("vendor",vendor);
        UUID board = id(mvc.perform(post(path).with(as(operator,"EMPLOYEE")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(data))));
        assertThat(owner.queryForObject("select count(*) from media_private.contracts where board_id=? and confirmed",Long.class,board)).isEqualTo(2);
        assertThat(owner.queryForObject("select count(*) from media_private.obligations o join media_private.contracts c on c.id=o.contract_id where c.board_id=?",Long.class,board)).isEqualTo(1);
        assertThat(owner.queryForObject("select count(*) from media_private.reminder_candidates(?) where payload->>'boardId'=?",Long.class,day,board.toString())).isEqualTo(3);
        mvc.perform(get(ROOT + "/reminders/plan").with(as(operator,"EMPLOYEE"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.upcoming[?(@.payload.boardId == '" + board + "')]").isNotEmpty());
    }

    private UUID confirm(UUID board, String side, UUID counterparty, UUID actor, LocalDate start, LocalDate end, int amount) throws Exception {
        UUID draft = owner.queryForObject("select id from media_private.contracts where board_id=? and kind=?", UUID.class,board,side);
        return id(save("contract",actor,"EMPLOYEE",Map.of("id",draft,"revision",1,"partyId",counterparty,
                "startDate",start,"endDate",end,"amount",amount,"confirmed",true)));
    }

    private ResultActions save(String entity, UUID actor,String role,Map<String,?> data) throws Exception {
        return command(entity,"save",actor,role,data);
    }

    private ResultActions command(String entity,String action,UUID actor,String role,Map<String,?> data) throws Exception {
        return mvc.perform(post(ROOT + "/register/" + entity + "/" + action).with(as(actor,role)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(data)));
    }

    private UUID id(ResultActions action) throws Exception {
        return UUID.fromString(json.readTree(action.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("id").asText());
    }

    private UUID employee(String role, String category) {
        String key = UUID.randomUUID().toString().substring(0, 12);
        UUID id = owner.queryForObject("""
                insert into employees (employee_number,full_name,email,password_hash,role,is_active)
                values (?, 'Media Fixture', ?, 'disabled-fixture', ?, true) returning id
                """, UUID.class, key, key + "@example.test", role);
        if (category != null) owner.update("insert into media_private.memberships values (?, ?)", id, category);
        return id;
    }

    private static RequestPostProcessor as(UUID id, String role) {
        var principal = new AuthenticatedEmployee(id, "media@example.test", "Media fixture", EmployeeRole.valueOf(role));
        return authentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private static MockMultipartFile upload(byte[] bytes) {
        return new MockMultipartFile("workbook", "Adesa Media Client Status Sheet.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
    }
}
