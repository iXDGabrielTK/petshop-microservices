package auth.dto.response;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginResponse {
    private String access_token;
    private String refresh_token;
    private String usuario_Id;
    private String nome;
    private String email;

    public LoginResponse(String access_token, String refresh_token, String usuario_Id, String nome, String email) {
        this.access_token = access_token;
        this.refresh_token = refresh_token;
        this.usuario_Id = usuario_Id;
        this.nome = nome;
        this.email = email;
    }
}