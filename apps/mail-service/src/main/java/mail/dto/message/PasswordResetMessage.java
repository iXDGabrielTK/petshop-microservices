package mail.dto.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PasswordResetMessage implements Serializable {
    private String email;
    private String token;
    private String nomeUsuario;
}