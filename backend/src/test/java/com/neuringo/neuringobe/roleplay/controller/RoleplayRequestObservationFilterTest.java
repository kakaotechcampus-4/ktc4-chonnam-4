package com.neuringo.neuringobe.roleplay.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.common.TraceIds;
import com.neuringo.neuringobe.roleplay.dto.RoleplayTurnResponse;
import com.neuringo.neuringobe.roleplay.model.RoleplayDeliveryAction;
import com.neuringo.neuringobe.roleplay.model.RoleplayDeliveryStatus;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(OutputCaptureExtension.class)
class RoleplayRequestObservationFilterTest {
    private final RoleplayRequestObservationFilter filter =
            new RoleplayRequestObservationFilter(java.util.Optional.empty());

    @Test
    void usesServerGeneratedIdAndLogsOnlyTechnicalResults(CapturedOutput output) throws Exception {
        var request = request();
        request.addHeader("X-Trace-Id", "untrusted-input");
        request.setContent("아동 발화 비밀 원문".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();
        filter.doFilter(
                request,
                response,
                (req, res) -> {
                    assertThat(TraceIds.newTraceId()).isEqualTo(response.getHeader("X-Trace-Id"));
                    assertThat(TraceIds.newTraceId()).isNotEqualTo("untrusted-input");
                    RoleplayRequestObservationFilter.recordResult(
                            request,
                            new RoleplayTurnResponse(
                                    RoleplayDeliveryStatus.DELIVERED,
                                    RoleplayDeliveryAction.values()[0],
                                    List.of(),
                                    "아동 발화 비밀 원문",
                                    2L,
                                    false));
                });
        UUID.fromString(response.getHeader("X-Trace-Id"));
        assertThat(MDC.get(TraceIds.REQUEST_KEY)).isNull();
        assertThat(output)
                .contains("ROLEPLAY_REQUEST", "result=DELIVERED", "checkpoint=2", "replayed=false");
        assertThat(output)
                .doesNotContain("아동 발화 비밀 원문", "untrusted-input", request.getRequestURI());
    }

    @Test
    void restoresPreviousContextOnExceptionalExit(CapturedOutput output) {
        String previous = UUID.randomUUID().toString();
        MDC.put(TraceIds.REQUEST_KEY, previous);
        try {
            assertThatThrownBy(
                            () ->
                                    filter.doFilter(
                                            request(),
                                            new MockHttpServletResponse(),
                                            (req, res) -> {
                                                assertThat(TraceIds.newTraceId())
                                                        .isNotEqualTo(previous);
                                                throw new IOException("비밀 출력이 포함된 오류 원문");
                                            }))
                    .isInstanceOf(IOException.class);
            assertThat(MDC.get(TraceIds.REQUEST_KEY)).isEqualTo(previous);
            assertThat(output).contains("completion=ERROR").doesNotContain("비밀 출력이 포함된 오류 원문");
        } finally {
            MDC.remove(TraceIds.REQUEST_KEY);
        }
    }

    @Test
    void leavesOtherEndpointsAndMethodsUnchanged() throws Exception {
        var request = request();
        request.setMethod("GET");
        var response = new MockHttpServletResponse();
        filter.doFilter(
                request,
                response,
                (req, res) -> assertThat(MDC.get(TraceIds.REQUEST_KEY)).isNull());
        assertThat(response.getHeader("X-Trace-Id")).isNull();
        request.setMethod("POST");
        request.setRequestURI("/api/v1/other");
        filter.doFilter(request, response, (req, res) -> {});
        assertThat(response.getHeader("X-Trace-Id")).isNull();
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest(
                "POST", "/api/v1/roleplay-sessions/" + UUID.randomUUID() + "/text-inputs");
    }
}
