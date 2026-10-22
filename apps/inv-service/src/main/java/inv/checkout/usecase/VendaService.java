package inv.checkout.usecase;

import common.exception.BusinessException;
import common.exception.ResourceNotFoundException;
import inv.checkout.domain.model.ItemVenda;
import inv.checkout.domain.model.StatusVenda;
import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.persistence.VendaRepository;
import inv.checkout.infrastructure.web.dto.ItemReciboResponse;
import inv.checkout.infrastructure.web.dto.ItemVendaRequest;
import inv.checkout.infrastructure.web.dto.ReciboResponse;
import inv.checkout.infrastructure.web.dto.VendaRequest;
import inv.checkout.infrastructure.web.dto.VendaStatusResponse;
import inv.inventory.domain.model.Produto;
import inv.inventory.infrastructure.persistence.ProdutoRepository;
import inv.inventory.usecase.EstoqueService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class VendaService {

    private final ProdutoRepository produtoRepository;
    private final VendaRepository vendaRepository;
    private final EstoqueService estoqueService;

    public VendaService(ProdutoRepository produtoRepository,
                        VendaRepository vendaRepository,
                        EstoqueService estoqueService) {
        this.produtoRepository = produtoRepository;
        this.vendaRepository = vendaRepository;
        this.estoqueService = estoqueService;
    }

    @Retryable(
            retryFor = { OptimisticLockingFailureException.class },
            backoff = @Backoff(delay = 150, maxDelay = 500, random = true)
    )
    @Transactional
    public Venda iniciarVenda(VendaRequest pedido) {
        log.debug("Processando início de venda. Verificando disponibilidade de estoque...");

        Map<Long, Produto> produtosMap = carregarProdutos(pedido);

        Venda venda = vendaRepository.save(new Venda());

        for (ItemVendaRequest itemRequest : pedido.itens()) {
            Produto produto = produtosMap.get(itemRequest.produtoId());
            venda.adicionarItem(produto, itemRequest.quantidade());
            estoqueService.reservarEstoqueParaVenda(produto, itemRequest.quantidade(), venda.getId());
        }

        venda.irParaPagamento();
        return vendaRepository.save(venda);
    }

    @Transactional(readOnly = true)
    public VendaStatusResponse buscarStatusVenda(Long vendaId) {
        return vendaRepository.findById(vendaId)
                .map(venda -> new VendaStatusResponse(
                        venda.getId(),
                        venda.getVersion(),
                        venda.getStatus(),
                        venda.getValorTotal(),
                        venda.getValorPago(),
                        venda.getValorTroco()
                ))
                .orElseThrow(() -> new BusinessException("Venda não encontrada para o ID: " + vendaId));
    }

    @Transactional(readOnly = true)
    public ReciboResponse recuperarVendaAtiva() {
        List<StatusVenda> statusAtivos = List.of(StatusVenda.ABERTA, StatusVenda.AGUARDANDO_PAGAMENTO);

        return vendaRepository.findFirstByStatusInOrderByIdDesc(statusAtivos)
                .map(venda -> {
                    List<ItemReciboResponse> itensResponse = venda.getItens().stream()
                            .map(item -> new ItemReciboResponse(
                                    item.getNomeProdutoSnapshot(),
                                    item.getQuantidade(),
                                    item.getPrecoUnitarioSnapshot(),
                                    item.getSubTotal()
                            ))
                            .collect(Collectors.toList());

                    return new ReciboResponse(
                            venda.getId().toString(),
                            venda.getDataHoraAbertura().toString(),
                            venda.getValorTotal(),
                            itensResponse
                    );
                })
                .orElse(null);
    }

    @Transactional
    public void cancelarVendaExpirada(Long vendaId) {
        Venda venda = vendaRepository.findByIdWithLock(vendaId)
                .orElseThrow(() -> new BusinessException("Venda não encontrada"));

        switch (venda.getStatus()) {                                // guard pós-lock (F2 / Q10)
            case CANCELADA -> {
                log.info("Venda {} já está CANCELADA; expiração ignorada (idempotente).", vendaId);
                return;
            }
            case ABERTA, AGUARDANDO_PAGAMENTO -> { /* elegível: segue */ }
            default -> throw new BusinessException(
                    "Venda #" + vendaId + " não é elegível para cancelamento por expiração no status: " + venda.getStatus());
        }

        venda.cancelar();

        for (ItemVenda item : venda.getItens()) {
            estoqueService.estornarReservaEstoque(item.getProduto(), item.getQuantidade(), venda.getId());
        }

        vendaRepository.save(venda);
    }

    private Map<Long, Produto> carregarProdutos(VendaRequest pedido) {
        Set<Long> ids = pedido.itens().stream()
                .map(ItemVendaRequest::produtoId)
                .collect(Collectors.toSet());

        Map<Long, Produto> map = produtoRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Produto::getId, Function.identity()));

        if (map.size() != ids.size()) {
            throw new ResourceNotFoundException("Um ou mais produtos não foram encontrados.");
        }
        return map;
    }

    @SuppressWarnings("unused")
    @Recover
    public Venda recuperarFalhaDeLock(OptimisticLockingFailureException e, VendaRequest pedido) {
        log.warn("⚠️ Limite de retries atingido por contenção de lock no estoque. Venda abortada.");
        throw new BusinessException(
                "Alta concorrência nos itens selecionados. O estoque foi atualizado por outro caixa. Por favor, tente finalizar novamente."
        );
    }

    @SuppressWarnings("unused")
    @Recover
    public Venda recuperarFalhaGeral(RuntimeException e, VendaRequest pedido) {
        throw e;
    }
}
