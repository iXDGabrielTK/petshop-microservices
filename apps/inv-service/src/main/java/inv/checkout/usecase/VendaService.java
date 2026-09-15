package inv.checkout.usecase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import common.exception.BusinessException;
import common.exception.ResourceNotFoundException;
import inv.checkout.domain.model.ItemVenda;
import inv.checkout.domain.model.Pagamento;
import inv.checkout.domain.model.StatusVenda;
import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.web.dto.ItemReciboResponse;
import inv.checkout.infrastructure.web.dto.ReciboResponse;
import inv.checkout.infrastructure.web.dto.VendaRequest;
import inv.checkout.infrastructure.web.dto.VendaStatusResponse;
import inv.checkout.infrastructure.messaging.event.VendaConcluidaEvent;
import inv.inventory.domain.model.Produto;
import inv.checkout.infrastructure.web.dto.ItemVendaRequest;
import inv.inventory.usecase.EstoqueService;
import inv.checkout.infrastructure.persistence.PagamentoRepository;
import inv.inventory.infrastructure.persistence.ProdutoRepository;
import inv.checkout.infrastructure.persistence.VendaRepository;
import inv.shared.outbox.model.Outbox;
import inv.shared.outbox.repository.OutboxRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class VendaService {

    private final ProdutoRepository produtoRepository;
    private final PagamentoRepository pagamentoRepository;
    private final VendaRepository vendaRepository;
    private final EstoqueService estoqueService;
    private final ApplicationEventPublisher eventPublisher;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;


    public VendaService(ProdutoRepository produtoRepository, PagamentoRepository pagamentoRepository,
                        VendaRepository vendaRepository,
                        EstoqueService estoqueService,
                        ApplicationEventPublisher eventPublisher, OutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.produtoRepository = produtoRepository;
        this.pagamentoRepository = pagamentoRepository;
        this.vendaRepository = vendaRepository;
        this.estoqueService = estoqueService;
        this.eventPublisher = eventPublisher;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
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

    @Transactional(propagation = Propagation.MANDATORY)
    public void processarPagamento(Long vendaId, Long pagamentoId) {

        Venda venda = vendaRepository.findByIdWithLock(vendaId)
                .orElseThrow(() -> new BusinessException("Venda não encontrada"));

        Pagamento pagamento = pagamentoRepository.findById(pagamentoId)
                .orElseThrow(() -> new BusinessException("Pagamento não encontrado"));

        if (venda.getStatus() == StatusVenda.CONCLUIDA) {
            log.warn("Tentativa de reprocessamento para venda já paga: {}", vendaId);
            return;
        }

        venda.registrarPagamentoConfirmado(pagamento);

        if (venda.estaPaga()) {
            venda.concluir();

            for (ItemVenda item : venda.getItens()) {
                estoqueService.confirmarBaixaEstoque(item.getProduto(), item.getQuantidade(), venda.getId());
            }

            registrarOutboxVendaConcluida(venda.getId());
        }

        vendaRepository.save(venda);
    }



    @Transactional
    public void cancelarVendaExpirada(Long vendaId) {
        Venda venda = vendaRepository.findByIdWithLock(vendaId)
                .orElseThrow(() -> new BusinessException("Venda não encontrada"));

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

    private void registrarOutboxVendaConcluida(Long vendaId) {
        try {
            VendaConcluidaEvent event = new VendaConcluidaEvent(vendaId);
            String payloadJson = objectMapper.writeValueAsString(event);

            Outbox outbox = new Outbox();
            outbox.setExchange("vendas.exchange");
            outbox.setRoutingKey("venda.concluida");
            outbox.setEventType(VendaConcluidaEvent.class.getName());
            outbox.setPayload(payloadJson);
            outbox.setVersion(1);
            outbox.setCreatedAt(LocalDateTime.now());

            outboxRepository.save(outbox);
        } catch (JsonProcessingException e) {
            log.error("Erro ao serializar evento VendaConcluidaEvent para venda ID: {}", vendaId, e);
            throw new BusinessException("Erro ao gerar evento de conclusão de venda.");
        }
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