package com.jlas.billing.payos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.billing.config.PayOsProperties;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * PayOS merchant API (no webhook): create a payment link, read its status, cancel it.
 * Every request is signed with the checksum key; a created link is only accepted when PayOS's
 * response signature verifies.
 */
@Component
public class PayOsClient {
    private static final Logger log = LoggerFactory.getLogger(PayOsClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PayOsProperties properties;
    private final RestTemplate http;

    public PayOsClient(PayOsProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(15_000);
        this.http = new RestTemplate(factory);
    }

    public record CreatedLink(String paymentLinkId, String checkoutUrl, String qrCode, String status) {
    }

    /** A payment as PayOS sees it. {@code reference} is the bank transfer reference of the first transaction. */
    public record RemotePayment(long orderCode, long amount, long amountPaid, String status, String reference) {
    }

    public CreatedLink create(long orderCode, long amount, String description, String returnUrl, String cancelUrl,
                              long expiredAtEpochSeconds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderCode", orderCode);
        body.put("amount", amount);
        body.put("description", description);
        body.put("returnUrl", returnUrl);
        body.put("cancelUrl", cancelUrl);
        body.put("expiredAt", expiredAtEpochSeconds);
        body.put("signature", sign("amount=" + amount + "&cancelUrl=" + cancelUrl + "&description=" + description
                + "&orderCode=" + orderCode + "&returnUrl=" + returnUrl));
        JsonNode data = call(HttpMethod.POST, "/v2/payment-requests", body, true);
        if (data.path("amount").asLong() != amount || data.path("orderCode").asLong() != orderCode) {
            throw gatewayError("PayOS trả về đơn không khớp", null);
        }
        return new CreatedLink(data.path("paymentLinkId").asText(), data.path("checkoutUrl").asText(),
                data.path("qrCode").asText(), data.path("status").asText());
    }

    public RemotePayment fetch(long orderCode) {
        JsonNode data = call(HttpMethod.GET, "/v2/payment-requests/" + orderCode, null, false);
        if (data.path("orderCode").asLong() != orderCode) throw gatewayError("PayOS trả về đơn không khớp", null);
        JsonNode first = data.path("transactions").path(0);
        return new RemotePayment(orderCode, data.path("amount").asLong(), data.path("amountPaid").asLong(),
                data.path("status").asText(), first.isMissingNode() ? null : first.path("reference").asText(null));
    }

    public RemotePayment cancel(long orderCode, String reason) {
        JsonNode data = call(HttpMethod.POST, "/v2/payment-requests/" + orderCode + "/cancel",
                Map.of("cancellationReason", reason), false);
        return new RemotePayment(orderCode, data.path("amount").asLong(), data.path("amountPaid").asLong(),
                data.path("status").asText(), null);
    }

    private JsonNode call(HttpMethod method, String path, Object body, boolean verifySignature) {
        if (!properties.configured()) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Thanh toán chưa được cấu hình.");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-client-id", properties.clientId());
        headers.set("x-api-key", properties.apiKey());
        headers.set(HttpHeaders.USER_AGENT, "jlas-backend/1.0"); // PayOS's firewall rejects some default agents
        headers.setContentType(MediaType.APPLICATION_JSON);
        JsonNode response;
        try {
            String raw = http.exchange(properties.baseUrl() + path, method,
                    new HttpEntity<>(body == null ? null : JSON.writeValueAsString(body), headers), String.class).getBody();
            response = JSON.readTree(raw);
        } catch (RestClientException | com.fasterxml.jackson.core.JacksonException exception) {
            throw gatewayError("Không kết nối được cổng thanh toán", exception);
        }
        if (!"00".equals(response.path("code").asText())) {
            log.warn("PayOS {} {} failed: code={} desc={}", method, path, response.path("code").asText(),
                    response.path("desc").asText());
            throw gatewayError("Cổng thanh toán từ chối yêu cầu", null);
        }
        JsonNode data = response.path("data");
        if (verifySignature && !verify(data, response.path("signature").asText())) {
            throw gatewayError("Chữ ký phản hồi của PayOS không hợp lệ", null);
        }
        return data;
    }

    /** PayOS signs flat data as its fields sorted by name, joined as {@code key=value&...}; null is empty. */
    boolean verify(JsonNode data, String signature) {
        if (!data.isObject() || signature == null || signature.isBlank()) return false;
        Map<String, String> fields = new TreeMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = data.fields(); it.hasNext(); ) {
            var field = it.next();
            fields.put(field.getKey(), field.getValue().isNull() ? "" : field.getValue().asText());
        }
        StringBuilder text = new StringBuilder();
        fields.forEach((key, value) -> text.append(text.isEmpty() ? "" : "&").append(key).append('=').append(value));
        return MessageDigest.isEqual(sign(text.toString()).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    String sign(String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.checksumKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 unavailable", exception);
        }
    }

    private static ApiException gatewayError(String message, Exception cause) {
        if (cause != null) log.warn("{}: {}", message, cause.getMessage());
        return new ApiException(ErrorCode.EXTERNAL_SERVICE_ERROR, message + ". Vui lòng thử lại sau.");
    }
}
