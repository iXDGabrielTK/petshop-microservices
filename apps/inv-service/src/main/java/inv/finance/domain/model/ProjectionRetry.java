package inv.finance.domain.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "projection_retry_queue")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectionRetry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long lancamentoId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    private String erro;

    private int tentativas = 0;

    @Column(nullable = false)
    private String status = "PENDENTE";

    @Column(name = "proxima_execucao")
    private LocalDateTime proximaExecucao = LocalDateTime.now();

    public ProjectionRetry(Long lancamentoId, String payload, String erro) {
        this.lancamentoId = lancamentoId;
        this.payload = payload;
        this.erro = erro;
    }

    public void registrarFalha(String erroAtual) {
        this.tentativas++;
        this.erro = erroAtual;
        if (this.tentativas >= 5) {
            this.status = "FALHA_PERMANENTE";
        } else {
            this.proximaExecucao = LocalDateTime.now().plusMinutes((long) Math.pow(2, this.tentativas));
        }
    }

    public void marcarComoResolvido() {
        this.status = "RESOLVIDO";
    }
}