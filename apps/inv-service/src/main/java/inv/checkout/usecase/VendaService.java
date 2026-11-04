package inv.checkout.usecase;

import common.exception.BusinessException;
import common.exception.ResourceNotFoundException;
import inv.checkout.domain.model.StatusVenda;
import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.messaging.event.VendaAtualizadaEvent;
import inv.checkout.infrastructure.persistence.VendaRepository;
import inv.checkout.infrastructure.web.dto.ItemReciboResponse;
import inv.checkout.infrastructure.web.dto.ItemVendaRequest;
import inv.checkout.infrastructure.web.dto.ReciboResponse;
import inv.checkout.infrastructure.web.dto.VendaRequest;
import inv.checkout.infrastructure.web.dto.VendaStatusResponse;
import inv.inventory.api.CatalogoQueryService;
import inv.inventory.api.ComandoEstoqueVenda;
import inv.inventory.api.EstoqueCommandService;
import inv.inventory.api.ItemComando;
import inv.inventory.api.ProdutoCheckoutView;
import inv.shared.event.VendaEstornadaEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class VendaService {

    private final VendaRepository vendaRepository;
    private final CatalogoQueryService catalogoQueryService;
    private final EstoqueCommandService estoqueCommandService;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${features.cancelamento-venda-concluida.enabled:false}")
    private boolean cancelamentoConcluidoHabilitado = true;

    public VendaService(VendaRepository vendaRepository,
                        CatalogoQueryService catalogoQueryService,
                        EstoqueCommandService estoqueCommandService,
                        ApplicationEventPublisher eventPublisher) {
        this.vendaRepository = vendaRepository;
        this.catalogoQueryService = catalogoQueryService;
        this.estoqueCommandService = estoqueCommandService;
        this.eventPublisher = eventPublisher;
    }

    @Autowired(required = false)
    public void setCancelamentoConcluidoHabilitado(
            @Value("${features.cancelamento-venda-concluida.enabled:false}") boolean cancelamentoConcluidoHabilitado) {
        this.cancelamentoConcluidoHabilitado = cancelamentoConcluidoHabilitado;
    }


    @Retryable(
            retryFor = { OptimisticLockingFailureException.class, PessimisticLockingFailureException.class },
            backoff = @Backoff(delay = 150, maxDelay = 500, random = true)
    )
    @Transactional
    public Venda iniciarVenda(VendaRequest pedido) {
        log.debug("Processando início de venda. Verificando disponibilidade de estoque...");

        Map<Long, ProdutoCheckoutView> produtos = carregarCatalogo(pedido);
        Venda venda = vendaRepository.save(new Venda());

        for (ItemVendaRequest itemRequest : pedido.itens()) {
            ProdutoCheckoutView prod = produtos.get(itemRequest.produtoId());
            venda.adicionarItem(prod.id(), prod.nome(), prod.precoVenda(), itemRequest.quantidade());
        }

        estoqueCommandService.reservar(comandoPara(venda));
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
        estoqueCommandService.estornarReserva(comandoPara(venda));
        vendaRepository.save(venda);
    }

    @Transactional
    public VendaStatusResponse cancelarVendaConcluida(Long vendaId) {
        if (!cancelamentoConcluidoHabilitado) {
            throw new CancelamentoVendaDesabilitadoException("Cancelamento de venda concluída temporariamente desabilitado");
        }

        Venda venda = vendaRepository.findByIdWithLock(vendaId)
                .orElseThrow(() -> new ResourceNotFoundException("Venda não encontrada: " + vendaId));

        if (venda.getStatus() != StatusVenda.CONCLUIDA) {
            throw new CancelamentoVendaConflitoException(
                    "Venda #" + vendaId + " não pode ser cancelada pois está no status: " + venda.getStatus());
        }

        venda.cancelarVendaConcluida();
        estoqueCommandService.devolverEstoque(comandoPara(venda));
        venda = vendaRepository.save(venda);

        BigDecimal valorTroco = venda.getValorTroco() != null ? venda.getValorTroco() : BigDecimal.ZERO;
        BigDecimal valorPago = venda.getValorPago() != null ? venda.getValorPago() : BigDecimal.ZERO;
        BigDecimal valorEstorno = valorPago.subtract(valorTroco);
        UUID uuidReferenciaEstorno = UUID.nameUUIDFromBytes(("ESTORNO:" + vendaId).getBytes(StandardCharsets.UTF_8));

        eventPublisher.publishEvent(new VendaEstornadaEvent(vendaId, uuidReferenciaEstorno, valorEstorno));
        VendaStatusResponse response = VendaStatusResponse.from(venda);
        eventPublisher.publishEvent(new VendaAtualizadaEvent(vendaId, response));

        return response;
    }


    private static ComandoEstoqueVenda comandoPara(Venda venda) {
        List<ItemComando> itens = (venda.getItens() == null) ? List.of() :
                venda.getItens().stream()
                        .map(item -> new ItemComando(item.getProdutoId(), item.getQuantidade()))
                        .toList();
        return new ComandoEstoqueVenda(venda.getId(), itens);
    }

    private Map<Long, ProdutoCheckoutView> carregarCatalogo(VendaRequest pedido) {
        Set<Long> ids = pedido.itens().stream()
                .map(ItemVendaRequest::produtoId)
                .collect(Collectors.toSet());

        Map<Long, ProdutoCheckoutView> map = catalogoQueryService.buscarParaCheckout(ids).stream()
                .collect(Collectors.toMap(ProdutoCheckoutView::id, Function.identity()));

        if (map.size() != ids.size()) {
            throw new ResourceNotFoundException("Um ou mais produtos não foram encontrados.");
        }
        return map;
    }

    @SuppressWarnings("unused")
    @Recover
    public Venda recuperarFalhaDeConcorrencia(ConcurrencyFailureException e, VendaRequest pedido) {
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
