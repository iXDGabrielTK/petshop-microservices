package inv.service;

import common.exception.BusinessException;
import inv.dto.EstoqueBaixoMessage;
import inv.event.EstoqueAtingiuMinimoEvent;
import inv.model.MovimentacaoEstoque;
import inv.model.Produto;
import inv.model.TipoMovimentacao;
import inv.model.Venda;
import inv.repository.ProdutoRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
public class EstoqueService {

    private final ProdutoRepository produtoRepository;
    private final ApplicationEventPublisher eventPublisher;

    public EstoqueService(ProdutoRepository produtoRepository,
                          ApplicationEventPublisher eventPublisher) {
        this.produtoRepository = produtoRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void baixarEstoquePorVenda(Produto produto, BigDecimal quantidade, Venda vendaContexto) {

        BigDecimal novoSaldo = produtoRepository.decrementarEretornarSaldo(produto.getId(), quantidade);

        if (novoSaldo == null) {
            throw new BusinessException("Estoque insuficiente para o produto: " + produto.getNome());
        }

        BigDecimal saldoAnterior = novoSaldo.add(quantidade);
        verificarEAlertarEstoqueBaixo(produto, saldoAnterior, novoSaldo);

        produto.setQuantidadeEstoque(novoSaldo);

        MovimentacaoEstoque mov = new MovimentacaoEstoque();
        mov.setProduto(produto);
        mov.setQuantidade(quantidade);
        mov.setTipo(TipoMovimentacao.SAIDA);
        mov.setDataHora(LocalDateTime.now());
        mov.setObservacao("Venda automatizada");

        vendaContexto.adicionarMovimentacao(mov);
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