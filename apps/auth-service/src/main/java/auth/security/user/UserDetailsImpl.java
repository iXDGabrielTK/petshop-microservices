package auth.security.user;

import auth.model.Usuario;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;

/**
 * Implementação do UserDetails para o Spring Security.
 * Armazena os detalhes do usuário autenticado.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserDetailsImpl implements UserDetails {

    private Long id;

    private String nome;

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private String email;

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private String senha;

    private Collection<? extends GrantedAuthority> authorities;

    public UserDetailsImpl(Usuario usuario, Collection<? extends GrantedAuthority> authorities) {
        this.id = usuario.getId();
        this.nome = usuario.getNome();
        this.email = usuario.getEmail();
        this.senha = usuario.getSenha();
        this.authorities = authorities;
    }

    @Override
    public String getPassword() {
        return senha;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @JsonProperty("password")
    public void setPassword(String password) {
        this.senha = password;
    }

    @JsonProperty("username")
    public void setUsername(String username) {
        this.email = username;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    @JsonIgnore
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    @JsonIgnore
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    @JsonIgnore
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    @JsonIgnore
    public boolean isEnabled() {
        return true;
    }
}