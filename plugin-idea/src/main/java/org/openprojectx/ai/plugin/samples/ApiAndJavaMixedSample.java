package org.openprojectx.ai.plugin.samples;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Mixed sample for manual test-generation verification:
 * 1) contains API-call methods
 * 2) contains plain Java business methods
 */
public class ApiAndJavaMixedSample {

    private final HttpClient httpClient;

    public ApiAndJavaMixedSample() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build());
    }

    public ApiAndJavaMixedSample(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public int fetchUserStatusCode(String baseUrl, String userId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/users/" + userId))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }

    public String fetchOrderBody(String baseUrl, String orderId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/orders/" + orderId))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    public int sum(List<Integer> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }

        int total = 0;
        for (Integer value : values) {
            if (value != null) {
                total += value;
            }
        }
        return total;
    }

    public String normalizeName(String rawName) {
        if (rawName == null) {
            return "";
        }
        return rawName.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    public boolean isVipLevel(int score) {
        return score >= 900;
    }
}
