package com.neuringo.neuringobe.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.neuringo.neuringobe.classroom.controller.ClassroomController;
import com.neuringo.neuringobe.classroom.service.ClassroomService;
import com.neuringo.neuringobe.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.request.ServletWebRequest;

// 기본 프로필 보안 체인은 인증을 요구하고 CSRF 가 켜져 있어, 인증 사용자와 CSRF 토큰을 넣어야 요청이 Controller 까지 온다.
@WebMvcTest(ClassroomController.class)
@Import(SecurityConfig.class)
@WithMockUser
@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ClassroomService classroomService;

    @Test
    void fillsFieldErrorsForInvalidRequestBody() throws Exception {
        mockMvc.perform(
                        post("/api/v1/classrooms")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("name"))
                .andExpect(jsonPath("$.error.fieldErrors[0].message").isNotEmpty())
                .andExpect(jsonPath("$.error.fieldErrors[0].rejectedValue").doesNotExist());
    }

    // 축소안: JSON 문법 오류도 400 INVALID_REQUEST 로 응답한다(MALFORMED_JSON 구분은 후속).
    @Test
    void mapsMalformedJsonToInvalidRequest() throws Exception {
        mockMvc.perform(
                        post("/api/v1/classrooms")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void mapsFieldTypeMismatchToInvalidRequest() throws Exception {
        mockMvc.perform(
                        post("/api/v1/classrooms")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": {}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void mapsNonJsonBodyToUnsupportedMediaType() throws Exception {
        mockMvc.perform(
                        post("/api/v1/classrooms")
                                .with(csrf())
                                .contentType(MediaType.TEXT_PLAIN)
                                .content("테스트반"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void mapsMalformedUuidToInvalidRequest() throws Exception {
        mockMvc.perform(get("/api/v1/classrooms/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.path").value("/api/v1/classrooms/not-a-uuid"))
                .andExpect(jsonPath("$.error.traceId").isNotEmpty())
                .andExpect(jsonPath("$.error.fieldErrors").isEmpty());
    }

    @Test
    void mapsUnknownPathToResourceNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void keepsAllowHeaderForUnsupportedMethod() throws Exception {
        mockMvc.perform(delete("/api/v1/classrooms").with(csrf()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void hidesExceptionMessageAndLogsSameTraceIdForUnexpectedError(CapturedOutput output)
            throws Exception {
        given(classroomService.list()).willThrow(new IllegalStateException("테스트아동 민감 메시지"));

        String body =
                mockMvc.perform(get("/api/v1/classrooms"))
                        .andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                        .andExpect(content().string(not(containsString("테스트아동"))))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String traceId = JsonPath.read(body, "$.error.traceId");
        assertThat(output).contains("traceId=" + traceId).doesNotContain("테스트아동");
    }

    @Test
    void skipsErrorResponseWhenResponseIsAlreadyCommitted() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/classrooms");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCommitted(true);
        IllegalStateException ex = new IllegalStateException("테스트");

        assertThat(handler.handleUnexpected(ex, request, response)).isNull();
        assertThat(
                        handler.handleExceptionInternal(
                                ex,
                                null,
                                new HttpHeaders(),
                                HttpStatus.BAD_REQUEST,
                                new ServletWebRequest(request, response)))
                .isNull();
    }
}
