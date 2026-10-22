package inv.inventory.usecase;

import common.exception.BusinessException;
import common.exception.ResourceNotFoundException;
import inv.inventory.api.ComandoEstoqueVenda;
import inv.inventory.api.EstoqueCommandService;
import inv.inventory.api.ItemComando;
import inv.inventory.domain.model.MovimentacaoEstoque;
import inv.inventory.domain.model.Produto;
import inv.inventory.domain.model.TipoMovimentacao;
import inv.inventory.infrastructure.messaging.EstoqueAtingiuMinimoEvent;
import inv.inventory.infrastructure.messaging.EstoqueBaixoMessage;
import inv.inventory.infrastructure.persistence.MovimentacaoRepository;
import inv.inventory.infrastructure.persistence.ProdutoMetadadosEstoque;
import inv.inventory.infrastructure.persistence.ProdutoRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class EstoqueService implements EstoqueCommandService {

    /** Ordem global de aquisição de row locks em `produtos` (ADR-0002, adendo DEBT-ARCH-002 / F5). */
    static final Comparator<ItemComando> ORDEM_DE_LOCK = Comparator.comparingLong(ItemComando::produtoId);

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

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void reservar(ComandoEstoqueVenda comando) {
        if (comando.itens().isEmpty()) return;
        Map<Long, ProdutoMetadadosEstoque> metadados = carregarMetadados(comando);

        for (ItemComando item : emOrdemDeLock(comando)) {
            ProdutoMetadadosEstoque meta = exigir(metadados, item.produtoId());
            BigDecimal novoSaldo = produtoRepository.reservarEstoqueAtomo(item.produtoId(), item.quantidade());
            if (novoSaldo == null) {
                throw new BusinessException("Estoque insuficiente para o produto: " + meta.nome());
            }
            BigDecimal saldoAnterior = novoSaldo.add(item.quantidade());
            verificarEAlertarEstoqueBaixo(meta, saldoAnterior, novoSaldo);
            registrarMovimentacao(item.produtoId(), item.quantidade(), TipoMovimentacao.RESERVA,
                    "Reserva de estoque para Venda #" + comando.vendaId(), comando.vendaId());
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void confirmarBaixa(ComandoEstoqueVenda comando) {
        for (ItemComando item : emOrdemDeLock(comando)) {
            if (produtoRepository.confirmarBaixaEstoqueAtomo(item.produtoId(), item.quantidade()) == null) {
                throw new BusinessException("Inconsistência: Tentativa de baixar mais estoque reservado do que o existente para: "
                        + nomeDoProduto(item.produtoId()));
            }
            registrarMovimentacao(item.produtoId(), item.quantidade(), TipoMovimentacao.SAIDA,
                    "Baixa definitiva da Venda #" + comando.vendaId(), comando.vendaId());
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void estornarReserva(ComandoEstoqueVenda comando) {
        for (ItemComando item : emOrdemDeLock(comando)) {
            if (produtoRepository.estornarReservaEstoqueAtomo(item.produtoId(), item.quantidade()) == null) {
                throw new BusinessException("Inconsistência: Tentativa de cancelar reserva inexistente para: "
                        + nomeDoProduto(item.produtoId()));
            }
            registrarMovimentacao(item.produtoId(), item.quantidade(), TipoMovimentacao.CANCELAMENTO_RESERVA,
                    "Estorno de reserva. Venda #" + comando.vendaId(), comando.vendaId());
        }
    }

    /** Stream.sorted em fonte ordenada é estável: duplicados do mesmo produto mantêm a ordem relativa. */
    private static List<ItemComando> emOrdemDeLock(ComandoEstoqueVenda comando) {
        return comando.itens().stream().sorted(ORDEM_DE_LOCK).toList();
    }

    private Map<Long, ProdutoMetadadosEstoque> carregarMetadados(ComandoEstoqueVenda comando) {
        Set<Long> ids = comando.itens().stream().map(ItemComando::produtoId).collect(Collectors.toSet());
        return produtoRepository.buscarMetadadosEstoque(ids).stream()
                .collect(Collectors.toMap(ProdutoMetadadosEstoque::id, Function.identity()));
    }

    private static ProdutoMetadadosEstoque exigir(Map<Long, ProdutoMetadadosEstoque> metadados, long produtoId) {
        ProdutoMetadadosEstoque meta = metadados.get(produtoId);
        if (meta == null) {
            throw new ResourceNotFoundException("Produto não encontrado: " + produtoId);
        }
        return meta;
    }

    /** Só no caminho de erro: o UPDATE com 0 linhas não aborta a transação no PostgreSQL. */
    private String nomeDoProduto(long produtoId) {
        return produtoRepository.buscarMetadadosEstoque(List.of(produtoId)).stream()
                .findFirst().map(ProdutoMetadadosEstoque::nome).orElse("#" + produtoId);
    }

    private void registrarMovimentacao(long produtoId, BigDecimal quantidade, TipoMovimentacao tipo, String observacao, long vendaId) {
        MovimentacaoEstoque mov = new MovimentacaoEstoque();
        mov.setProduto(produtoRepository.getReferenceById(produtoId));
        mov.setQuantidade(quantidade);
        mov.setTipo(tipo);
        mov.setDataHora(LocalDateTime.now());
        mov.setObservacao(observacao);
        mov.setVendaId(vendaId);
        movimentacaoRepository.save(mov);
    }

    private void verificarEAlertarEstoqueBaixo(ProdutoMetadadosEstoque meta, BigDecimal antes, BigDecimal depois) {
        BigDecimal minimo = meta.estoqueMinimo();
        if (minimo != null && antes.compareTo(minimo) > 0 && depois.compareTo(minimo) <= 0) {
            eventPublisher.publishEvent(new EstoqueAtingiuMinimoEvent(
                    new EstoqueBaixoMessage(1, UUID.randomUUID().toString(), meta.nome(), depois, minimo)));
        }
    }

    @Deprecated(forRemoval = true)
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

    @Deprecated(forRemoval = true)
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

    @Deprecated(forRemoval = true)
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
