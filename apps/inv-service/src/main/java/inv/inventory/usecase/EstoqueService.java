package inv.inventory.usecase;

import inv.inventory.infrastructure.messaging.EstoqueBaixoMessage;
import inv.inventory.infrastructure.messaging.EstoqueAtingiuMinimoEvent;
import inv.inventory.domain.model.MovimentacaoEstoque;
import inv.inventory.domain.model.Produto;
import inv.inventory.domain.model.TipoMovimentacao;
import inv.inventory.infrastructure.persistence.MovimentacaoRepository;
import inv.inventory.infrastructure.persistence.ProdutoRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
public class EstoqueService {

    private final ProdutoRepository produtoRepository;
    private final MovimentacaoRepository movimentacaoRepository;
    private final ApplicationEventPublisher eventPublisher;

    public EstoqueService(ProdutoRepository produtoRepository,
                          MovimentacaoRepository movimentacaoRepository,
                          ApplicationEventPublisher eventPublisher) {
        this.produtoRepository = produtoRepository;
        this.movimentacaoRepository = movimentacaoRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reservarEstoqueParaVenda(Produto produto, BigDecimal quantidade, Long vendaId) {
        BigDecimal saldoAnterior = produto.getEstoqueDisponivel();
        BigDecimal novoSaldo = produtoRepository.reservarEstoqueAtomo(produto.getId(), quantidade);

        if (novoSaldo == null) {
            throw new common.exception.BusinessException("Estoque insuficiente para o produto: " + produto.getNome());
        }

        verificarEAlertarEstoqueBaixo(produto, saldoAnterior, novoSaldo);

        MovimentacaoEstoque mov = new MovimentacaoEstoque();
        mov.setProduto(produto);
        mov.setQuantidade(quantidade);
        mov.setTipo(TipoMovimentacao.RESERVA);
        mov.setDataHora(LocalDateTime.now());
        mov.setObservacao("Reserva de estoque para Venda #" + vendaId);
        mov.setVendaId(vendaId);

        movimentacaoRepository.save(mov);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void confirmarBaixaEstoque(Produto produto, BigDecimal quantidade, Long vendaId) {
        BigDecimal novoSaldoReservado = produtoRepository.confirmarBaixaEstoqueAtomo(produto.getId(), quantidade);

        if (novoSaldoReservado == null) {
            throw new common.exception.BusinessException("Inconsistência: Tentativa de baixar mais estoque reservado do que o existente para: " + produto.getNome());
        }

        MovimentacaoEstoque mov = new MovimentacaoEstoque();
        mov.setProduto(produto);
        mov.setQuantidade(quantidade);
        mov.setTipo(TipoMovimentacao.SAIDA);
        mov.setDataHora(LocalDateTime.now());
        mov.setObservacao("Baixa definitiva da Venda #" + vendaId);
        mov.setVendaId(vendaId);

        movimentacaoRepository.save(mov);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void estornarReservaEstoque(Produto produto, BigDecimal quantidade, Long vendaId) {
        BigDecimal novoSaldoDisponivel = produtoRepository.estornarReservaEstoqueAtomo(produto.getId(), quantidade);

        if (novoSaldoDisponivel == null) {
            throw new common.exception.BusinessException("Inconsistência: Tentativa de cancelar reserva inexistente para: " + produto.getNome());
        }

        MovimentacaoEstoque mov = new MovimentacaoEstoque();
        mov.setProduto(produto);
        mov.setQuantidade(quantidade);
        mov.setTipo(TipoMovimentacao.CANCELAMENTO_RESERVA);
        mov.setDataHora(LocalDateTime.now());
        mov.setObservacao("Estorno de reserva. Venda #" + vendaId);
        mov.setVendaId(vendaId);

        movimentacaoRepository.save(mov);
    }

    private void verificarEAlertarEstoqueBaixo(Produto produto, BigDecimal antes, BigDecimal depois) {
        BigDecimal minimo = produto.getEstoqueMinimo();
        if (minimo != null && antes.compareTo(minimo) > 0 && depois.compareTo(minimo) <= 0) {
            eventPublisher.publishEvent(new EstoqueAtingiuMinimoEvent(
                    new EstoqueBaixoMessage(
                            1,
                            java.util.UUID.randomUUID().toString(),
                            produto.getNome(),
                            depois,
                            minimo
                    )
            ));
        }
    }
}