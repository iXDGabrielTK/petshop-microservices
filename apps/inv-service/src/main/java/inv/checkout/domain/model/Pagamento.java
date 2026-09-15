package inv.checkout.domain.model;

import common.exception.BusinessException;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "pagamentos", uniqueConstraints = {
        @UniqueConstraint(name = "uk_pagamento_uuid", columnNames = "uuid_referencia") // Unicidade física
})
@Getter
@NoArgsConstructor
public class Pagamento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, updatable = false)
    private UUID uuidReferencia;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "venda_id", nullable = false)
    private Venda venda;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MetodoPagamento metodo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatusPagamento status = StatusPagamento.PENDENTE;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal valorEsperado;

    @Column(precision = 10, scale = 2)
    private BigDecimal valorRecebido = BigDecimal.ZERO;

    @Column(nullable = false)
    private boolean excedente = false;

    private String nsuTransacao;
    private LocalDateTime dataHoraPagamento;

    public Pagamento(Venda venda, MetodoPagamento metodo, BigDecimal valorEsperado, UUID uuidReferencia) {
        this.venda = venda;
        this.metodo = metodo;
        this.valorEsperado = valorEsperado;
        this.uuidReferencia = uuidReferencia;
    }

    public void marcarComoExcedenteParaEstorno() {
        this.excedente = true;
    }

    public void processar() {
        if (this.status != StatusPagamento.PENDENTE) {
            throw new BusinessException("Apenas pagamentos pendentes podem ser processados.");
        }
        this.status = StatusPagamento.PROCESSANDO;
    }

    public void confirmar(BigDecimal valorRecebido, String nsu) {
        if (this.status == StatusPagamento.CONFIRMADO) {
            return;
        }

        if (this.metodo.isExigeProcessamento() && this.status != StatusPagamento.PROCESSANDO) {
            throw new BusinessException("Transição inválida: Pagamento exige processamento prévio.");
        }

        this.valorRecebido = valorRecebido;
        this.nsuTransacao = nsu;
        this.status = StatusPagamento.CONFIRMADO;
        this.dataHoraPagamento = LocalDateTime.now();
    }

    public void falhar() {
        if (this.status != StatusPagamento.PROCESSANDO) {
            throw new BusinessException("Transição inválida para falha de pagamento.");
        }
        this.status = StatusPagamento.FALHOU;
    }
}