package inv.checkout.domain.model;

import lombok.Getter;

@Getter
public enum MetodoPagamento {
    DINHEIRO(false),
    PIX(false),
    CARTAO_CREDITO(true),
    CARTAO_DEBITO(true);

    private final boolean exigeProcessamento;

    MetodoPagamento(boolean exigeProcessamento) {
        this.exigeProcessamento = exigeProcessamento;
    }

}