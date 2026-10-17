package com.jlas.billing.payos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.billing.config.PayOsProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Reference values computed independently with Python's hmac/sha256. */
class PayOsClientSignatureTest {
    private final PayOsClient client = new PayOsClient(new PayOsProperties("c", "a", "test-checksum-key",
            "https://api-merchant.payos.vn", "http://localhost:8080", 15, 3));

    @Test
    void signsPaymentRequestsLikePayOs() {
        assertThat(client.sign("amount=10000&cancelUrl=https://x/c&description=JLAS PREM&orderCode=123&returnUrl=https://x/r"))
                .isEqualTo("28e09215b7e53ecddb152336cf8a9e5e4f6010d8097acc38c583e3e0ee28f969");
    }

    @Test
    void verifiesResponseDataSortedByKeyWithNullAsEmpty() throws Exception {
        var data = new ObjectMapper().readTree("{\"status\":\"PENDING\",\"orderCode\":123,\"desc\":null,\"bin\":\"970422\",\"amount\":10000}");
        String expected = "e8fc0468bd833857d1c89d43edbba5f3fb868d51eb1b11a80b5d45011b02546a";
        assertThat(client.verify(data, expected)).isTrue();
        assertThat(client.verify(data, expected.replace('e', 'f'))).isFalse();
        assertThat(client.verify(data, null)).isFalse();
    }
}
