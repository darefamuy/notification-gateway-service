package com.abbank.notification.consumer;

import com.abbank.notification.model.CustomerProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Production {@link CustomerResolver} that calls an HTTP customer profile service.
 *
 * <p>Expected GET endpoint: {@code GET /customers/by-account/{accountId}}
 * Expected response body:
 * <pre>{@code
 * {
 *   "customerId":  1001,
 *   "accountId":   100001,
 *   "firstName":   "Adaeze",
 *   "lastName":    "Okafor",
 *   "email":       "adaeze.okafor@email.com",
 *   "phoneNumber": "+2348031001001"
 * }
 * }</pre>
 *
 * <p>Configure via {@code customer-resolver.http.base-url} and
 * {@code customer-resolver.http.timeout-ms} in {@code application.conf}.
 */
public class HttpCustomerResolver implements CustomerResolver {

    private static final Logger LOG = LoggerFactory.getLogger(HttpCustomerResolver.class);

    private final String       baseUrl;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public HttpCustomerResolver(final String baseUrl, final int timeoutMs) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build();
    }

    @Override
    public Optional<CustomerProfile> resolve(final Long accountId) {
        if (accountId == null) return Optional.empty();

        final String url = baseUrl + "/customers/by-account/" + accountId;
        final Request request = new Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .get()
                .build();

        try (Response response = http.newCall(request).execute()) {
            if (response.code() == 404) {
                LOG.warn("Customer not found for accountId={}", accountId);
                return Optional.empty();
            }

            if (!response.isSuccessful()) {
                LOG.error("Customer service error: accountId={} http={}", accountId, response.code());
                return Optional.empty();
            }

            final String body = response.body() != null ? response.body().string() : "";
            if (body.isBlank()) return Optional.empty();

            final JsonNode json = mapper.readTree(body);
            return Optional.of(new CustomerProfile(
                    json.path("customerId").asLong(),
                    json.path("accountId").asLong(),
                    json.path("firstName").asText(""),
                    json.path("lastName").asText(""),
                    json.path("email").asText(""),
                    json.path("phoneNumber").asText("")));
        } catch (Exception e) {
            LOG.error("Failed to resolve customer for accountId={}: {}", accountId, e.getMessage());
            return Optional.empty();
        }
    }
}
