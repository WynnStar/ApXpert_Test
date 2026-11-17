package tw.holdings.holding;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;

@Configuration(proxyBeanMethods = false)
class ApiErrorDispatch {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiErrorDispatch.class);

    @Bean
    FilterRegistrationBean<Filter> apiErrorDispatchFilter(ObjectMapper json) {
        Filter filter = (request, response, chain) -> {
            var httpRequest = (HttpServletRequest) request;
            var httpResponse = (HttpServletResponse) response;
            var originalUri = (String) request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
            var status = (Integer) request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
            String apiPrefix = httpRequest.getContextPath() + "/api/";
            if (originalUri == null || status == null || httpResponse.isCommitted()
                    || !originalUri.startsWith(apiPrefix)) {
                chain.doFilter(request, response);
                return;
            }

            if (status >= 500) {
                var exception = (Throwable) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
                if (exception != null) {
                    LOGGER.error("API request failed with HTTP " + status, exception);
                }
            }
            httpResponse.setStatus(status);
            httpResponse.setCharacterEncoding(StandardCharsets.UTF_8.name());
            httpResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
            json.writeValue(httpResponse.getOutputStream(), bodyFor(status));
        };

        var registration = new FilterRegistrationBean<>(filter);
        registration.setName("apiErrorDispatchFilter");
        registration.setDispatcherTypes(DispatcherType.ERROR);
        return registration;
    }

    private ApiErrorResponse bodyFor(int status) {
        return switch (status) {
            case 404 -> new ApiErrorResponse("NOT_FOUND", "找不到指定的 API");
            case 405 -> new ApiErrorResponse("METHOD_NOT_ALLOWED", "此 API 不支援指定的 HTTP 方法");
            case 415 -> new ApiErrorResponse("UNSUPPORTED_MEDIA_TYPE", "請使用 application/json 傳送資料");
            case 500 -> new ApiErrorResponse("INTERNAL_ERROR", "伺服器發生錯誤，請稍後再試");
            default -> new ApiErrorResponse("HTTP_" + status, "無法完成請求");
        };
    }
}
