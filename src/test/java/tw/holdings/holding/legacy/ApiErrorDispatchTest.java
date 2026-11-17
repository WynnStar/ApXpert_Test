package tw.holdings.holding.legacy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.datasource.url=jdbc:h2:mem:api-errors-test;DB_CLOSE_DELAY=-1",
                "quotes.market-status-sync=false"})
@ExtendWith(OutputCaptureExtension.class)
class ApiErrorDispatchTest {
    private static final String HOLDINGS = "/api/v1/holdings";
    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @MockitoBean HoldingService holdings;

    @Test
    void wrapsUnknownApiPathWithoutChangingWebErrors() throws Exception {
        var response = get("/api/v1/unknown", "text/html");
        assertError(response, 404, "NOT_FOUND", "找不到指定的 API");

        var web = get("/missing-web-page", "text/html");
        assertEquals(404, web.statusCode());
        assertTrue(web.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
    }

    @Test
    void wrapsUnsupportedMethodAndKeepsAllowHeader() throws Exception {
        var response = send(HttpRequest.newBuilder(uri(HOLDINGS))
                .method("PATCH", HttpRequest.BodyPublishers.noBody()).build());
        assertError(response, 405, "METHOD_NOT_ALLOWED", "此 API 不支援指定的 HTTP 方法");
        assertTrue(response.headers().firstValue("Allow").orElse("").contains("GET"));
    }

    @Test
    void wrapsUnsupportedContentType() throws Exception {
        var response = send(HttpRequest.newBuilder(uri(HOLDINGS))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("code=2330")).build());
        assertError(response, 415, "UNSUPPORTED_MEDIA_TYPE", "請使用 application/json 傳送資料");
    }

    @Test
    void logsUnexpectedExceptionWithoutExposingDetails(CapturedOutput output) throws Exception {
        String detail = "synthetic failure detail for server log";
        when(holdings.list()).thenThrow(new IllegalStateException(detail));

        var response = get(HOLDINGS, "application/json");
        assertError(response, 500, "INTERNAL_ERROR", "伺服器發生錯誤，請稍後再試");
        assertFalse(response.body().contains(detail));
        assertTrue(output.getAll().contains("IllegalStateException: " + detail));
    }

    private HttpResponse<String> get(String path, String accept) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).header("Accept", accept).GET().build());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private void assertError(HttpResponse<String> response, int status, String code, String message)
            throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        var expected = json.createObjectNode();
        expected.putObject("error").put("code", code).put("message", message);
        assertEquals(expected, json.readTree(response.body()));
    }
}
