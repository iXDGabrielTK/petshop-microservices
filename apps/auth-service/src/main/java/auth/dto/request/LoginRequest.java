package auth.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Secure login request DTO with input validation
 */
@Getter
@Setter
public class LoginRequest {

    @NotBlank(message = "Email é obrigatório")
    @Email(message = "Email deve ser válido")
    private String email;

    @Setter
    @NotBlank(message = "Senha é obrigatória")
    private String senha;

    // Default constructor for JSON deserialization
    public LoginRequest() {
    }

    public LoginRequest(String email, String senha) {
        this.email = email;
        this.senha = senha;
    }

    @JsonProperty("email")
    public void setEmail(String email) {
        // Trim and sanitize input
        if (email != null) {
            email = email.trim();
            // Basic XSS prevention
            email = email.replaceAll("<", "&lt;").replaceAll(">", "&gt;");
        }
        this.email = email;
    }

    @Override
    public String toString() {
        // Don't include a password in toString for security
        return "LoginRequest{" +
                "email='" + email + '\'' +
                ", senha='[PROTECTED]'" +
                '}';
    }
}