package inv.inventory.api;

/**
 * Open Host Service de comandos de estoque para o Checkout (protocolo Try-Confirm-Cancel).
 *
 * <p><b>Contrato transacional:</b> todas as operações exigem uma transação ativa
 * ({@code Propagation.MANDATORY}) e participam do mesmo commit/rollback do chamador (ADR-0002).
 *
 * <p><b>Contrato de concorrência:</b> a implementação processa os itens em ordem crescente de
 * {@code produtoId}, independente da ordem recebida, e executa um UPDATE atômico por item.
 * Isso garante uma ordem global de aquisição de row locks (prevenção de deadlock — ADR-0002, adendo).
 *
 * <p><b>Falhas:</b> {@code common.exception.BusinessException} com a mensagem do primeiro item
 * (em ordem de {@code produtoId}) que violar a pré-condição. O chamador deve deixar a transação reverter.
 */
public interface EstoqueCommandService {

    /** Try: move saldo de disponível para reservado e registra movimentação RESERVA. */
    void reservar(ComandoEstoqueVenda comando);

    /** Confirm: consome o saldo reservado (baixa definitiva) e registra movimentação SAIDA. */
    void confirmarBaixa(ComandoEstoqueVenda comando);

    /** Cancel: devolve o saldo reservado ao disponível e registra movimentação CANCELAMENTO_RESERVA. */
    void estornarReserva(ComandoEstoqueVenda comando);
}
