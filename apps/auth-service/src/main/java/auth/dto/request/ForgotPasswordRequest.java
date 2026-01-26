package auth.dto.request;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ForgotPasswordRequest {
    @NotBlank(message = "O email não pode estar vazio")
    @Email(message = "Email inválido")
    private String email;
}