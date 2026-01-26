package auth.dto.request;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ResetPasswordRequest {
    @NotBlank(message = "O token é obrigatório")
    private String token;

    @NotBlank(message = "A nova senha é obrigatória")
    private String newPassword;

    private String confirmPassword;}