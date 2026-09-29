package com.jlas.dictionary.handwriting;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandwritingBodyLimitFilterTest {

    private final HandwritingBodyLimitFilter filter = new HandwritingBodyLimitFilter();

    private static MockHttpServletRequest post(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", HandwritingBodyLimitFilter.PATH);
        request.setContent(body);
        return request;
    }

    @Test
    void rejectsAnOversizedContentLengthBeforeTheBodyIsEverRead() throws Exception {
        MockHttpServletRequest request = post(new byte[HandwritingBodyLimitFilter.MAX_BODY_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString(StandardCharsets.UTF_8).contains("PAYLOAD_TOO_LARGE"));
        assertNull(chain.getRequest(), "the request must not reach the controller");
    }

    @Test
    void letsAnAcceptableBodyThrough() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(post("{\"width\":360}".getBytes(StandardCharsets.UTF_8)), response, chain);

        assertNotNull(chain.getRequest());
        assertEquals(200, response.getStatus());
        assertEquals("{\"width\":360}", new String(chain.getRequest().getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void bodyAtExactlyTheLimitIsAccepted() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(post(new byte[HandwritingBodyLimitFilter.MAX_BODY_BYTES]), new MockHttpServletResponse(), chain);

        assertEquals(HandwritingBodyLimitFilter.MAX_BODY_BYTES, chain.getRequest().getInputStream().readAllBytes().length);
    }

    @Test
    void aChunkedBodyWithoutContentLengthIsCutOffWhileReading() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        // MockHttpServletRequest reports the content length itself, so hide it to simulate chunked transfer.
        MockHttpServletRequest chunked = new MockHttpServletRequest("POST", HandwritingBodyLimitFilter.PATH) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        chunked.setContent(new byte[HandwritingBodyLimitFilter.MAX_BODY_BYTES * 4]);

        filter.doFilter(chunked, new MockHttpServletResponse(), chain);

        assertThrows(IOException.class, () -> chain.getRequest().getInputStream().readAllBytes());
    }

    @Test
    void otherEndpointsAreNotTouched() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/other");
        request.setContent(new byte[HandwritingBodyLimitFilter.MAX_BODY_BYTES * 2]);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertEquals(request, chain.getRequest(), "not wrapped, not limited");
        MockHttpServletRequest get = new MockHttpServletRequest("GET", HandwritingBodyLimitFilter.PATH);
        MockFilterChain getChain = new MockFilterChain();
        filter.doFilter(get, new MockHttpServletResponse(), getChain);
        assertEquals(get, getChain.getRequest());
    }
}
