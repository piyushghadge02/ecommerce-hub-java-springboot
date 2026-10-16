package com.ecommerce.web;

/** Application exception carrying an HTTP status and a detail message.
 *  Rendered as {"detail": "..."} by GlobalExceptionHandler. */
public class ApiException extends RuntimeException {
    private final int status;

    public ApiException(int status, String detail) {
        super(detail);
        this.status = status;
    }

    public int getStatus() { return status; }

    public static ApiException badRequest(String detail) { return new ApiException(400, detail); }
    public static ApiException notFound(String detail) { return new ApiException(404, detail); }
    public static ApiException unprocessable(String detail) { return new ApiException(422, detail); }
    public static ApiException conflict(String detail) { return new ApiException(409, detail); }
    public static ApiException unavailable(String detail) { return new ApiException(503, detail); }
}
