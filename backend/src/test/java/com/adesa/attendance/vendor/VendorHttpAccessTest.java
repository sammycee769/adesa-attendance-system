package com.adesa.attendance.vendor;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.adesa.attendance.security.*;
import com.adesa.attendance.config.*;
import com.adesa.attendance.employee.EmployeeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual application security chain and method guards, without a database/server socket. */
class VendorHttpAccessTest {
 static AnnotationConfigWebApplicationContext context;static MockMvc http;
 @BeforeAll static void setup(){context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());context.register(TestConfig.class);context.refresh();http=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();}
 @AfterAll static void close(){context.close();}
 @Test void nonAdministratorsCannotReadApplicationsDocumentsOrPdfs()throws Exception{
  for(String role:List.of("HR","EMPLOYEE","COO","FINANCE","PROCUREMENT"))for(String route:List.of("overview","links","notices","mail-preview","applications/00000000-0000-0000-0000-000000000001","applications/00000000-0000-0000-0000-000000000001/pdf","documents/download/stolen-ticket"))http.perform(get("/api/v1/vendor/admin/"+route).with(user("staff").roles(role,"CAMPAIGN_MANAGE"))).andExpect(status().isForbidden());
 }
 @Test void anonymousRequestsAreUnauthorised()throws Exception{http.perform(get("/api/v1/vendor/admin/overview")).andExpect(status().isUnauthorized());}
 @Test void administratorCanReadButMutationStillRequiresCsrf()throws Exception{
  http.perform(get("/api/v1/vendor/admin/overview").with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
  http.perform(post("/api/v1/vendor/admin/links").with(user("admin").roles("ADMIN")).contentType("application/json").content("{}")).andExpect(status().isForbidden());
  http.perform(post("/api/v1/vendor/admin/links").with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isOk());
 }
 @Configuration @EnableWebMvc @Import({SecurityConfig.class,VendorAdminController.class,ApiAuthenticationEntryPoint.class,ApiAccessDeniedHandler.class,SecurityErrorWriter.class})
 static class TestConfig {
  @Bean VendorAdmin admin(){var admin=mock(VendorAdmin.class);when(admin.overview()).thenReturn(Map.of("demo",true));when(admin.createLink(anyMap())).thenReturn(Map.of("id",UUID.randomUUID()));return admin;}
  @Bean EmployeeRepository employees(){return mock(EmployeeRepository.class);}
  @Bean AuthProperties authProperties(){return new AuthProperties();}
  @Bean AccountProperties accountProperties(){return new AccountProperties();}
  @Bean ObjectMapper objectMapper(){return new ObjectMapper().findAndRegisterModules();}
 }
}
