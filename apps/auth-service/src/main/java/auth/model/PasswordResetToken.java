package auth.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Duration;
import java.time.LocalDateTime;
@Entity
@Getter
@Setter
@NoArgsConstructor
public class PasswordResetToken {

    private static final Duration EXPIRACAO = Duration.ofMinutes(30);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String token;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id", nullable = false, unique = true)
    private Usuario usuario;

    @Column(nullable = false)
    private LocalDateTime expiryDate;

    public PasswordResetToken(String token, Usuario usuario) {
        this.token = token;
        this.usuario = usuario;
        renovar();
    }

    public void renovar() {
        this.expiryDate = LocalDateTime.now().plus(EXPIRACAO);
    }

    public void atualizarToken(String novoToken) {
        this.token = novoToken;
        renovar();
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(this.expiryDate);
    }
}
