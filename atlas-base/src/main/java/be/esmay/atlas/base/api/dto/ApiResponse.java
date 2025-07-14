package be.esmay.atlas.base.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ApiResponse<T> {

    private final String status;
    private final T data;
    private final String message;
    private final Long timestamp;

    private ApiResponse(String status, T data, String message) {
        this.status = status;
        this.data = data;
        this.message = message;
        this.timestamp = System.currentTimeMillis();
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("success", data, null);
    }

    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>("success", data, message);
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>("error", null, message);
    }
}