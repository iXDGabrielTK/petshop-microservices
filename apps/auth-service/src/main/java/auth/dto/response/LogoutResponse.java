package auth.dto.response;

public record LogoutResponse(
        String message,
        boolean accessTokenRevoked,
        boolean refreshTokenRevoked
) {}