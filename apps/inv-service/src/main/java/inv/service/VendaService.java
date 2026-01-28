package inv.service;

import common.exception.BusinessException;
import common.exception.ResourceNotFoundException;
import inv.dto.EstoqueBaixoMessage;
import inv.dto.ItemVendaRequest;
import inv.dto.ReciboResponse;
import inv.dto.VendaRequest;
import inv.event.EstoqueAtingiuMinimoEvent;
import inv.model.MovimentacaoEstoque;
import inv.model.Produto;
import inv.repository.MovimentacaoRepository;
import inv.repository.ProdutoRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class VendaService {

    private final ProdutoRepository produtoRepository;
    private final MovimentacaoRepository movimentacaoRepository;
    private final ApplicationEventPublisher eventPublisher;

    public VendaService(ProdutoRepository produtoRepository,
                        MovimentacaoRepository movimentacaoRepository,
                        ApplicationEventPublisher eventPublisher) {
        this.produtoRepository = produtoRepository;
        this.movimentacaoRepository = movimentacaoRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public ReciboResponse realizarVenda(VendaRequest pedido) {
        // 1. Buscar produtos para validar existência e pegar dados (preço, nome)
        Set<Long> idsProdutos = pedido.itens().stream()
                .map(ItemVendaRequest::produtoId)
                .collect(Collectors.toSet());

        List<Produto> produtosEncontrados = produtoRepository.findAllById(idsProdutos);

        if (produtosEncontrados.size() != idsProdutos.size()) {
            throw new ResourceNotFoundException("Um ou mais produtos do carrinho não foram encontrados.");
        }

        Map<Long, Produto> mapaProdutos = produtosEncontrados.stream()
                .collect(Collectors.toMap(Produto::getId, Function.identity()));

        List<MovimentacaoEstoque> movimentacoes = new ArrayList<>();
        List<EstoqueBaixoMessage> alertasParaPublicar = new ArrayList<>();
        BigDecimal valorTotal = BigDecimal.ZERO;

        // 2. Processar cada item
        for (ItemVendaRequest item : pedido.itens()) {
            Produto produto = mapaProdutos.get(item.produtoId());
            BigDecimal estoqueMinimo = produto.getEstoqueMinimo();

            // 1. Operação Atômica no Banco
            // Se não tiver estoque, isso retorna NULL
            BigDecimal novoSaldoReal = produtoRepository.decrementarEretornarSaldo(produto.getId(), item.quantidade());

            // 2. Validação de Estoque
            if (novoSaldoReal == null) {
                throw new BusinessException("Estoque insuficiente (concorrência) para: " + produto.getNome());
            }

            // 3. Engenharia Reversa (Micro-Otimização)
            // Agora que sabemos que não é null, podemos calcular quanto TINHA no banco antes desse update exato.
            BigDecimal saldoAnteriorCalculado = novoSaldoReal.add(item.quantidade());

            // 4. Atualiza o objeto em memória (para Nota Fiscal/Recibo)
            produto.setQuantidadeEstoque(novoSaldoReal);

            // 5. Lógica de Alerta (Usando o valor CALCULADO, não o da memória)
            // Usamos saldoAnteriorCalculado aqui para garantir que só dispara se ESTA thread cruzou a linha
            if (estoqueMinimo != null && cruzouLimite(saldoAnteriorCalculado, novoSaldoReal, estoqueMinimo)) {
                alertasParaPublicar.add(new EstoqueBaixoMessage(
                        produto.getNome(),
                        novoSaldoReal,
                        estoqueMinimo
                ));
            }

            valorTotal = valorTotal.add(produto.getPrecoVenda().multiply(item.quantidade()));
        }

        // 3. Salvar apenas as movimentações
        movimentacaoRepository.saveAll(movimentacoes);

        // 4. Publicar eventos
        alertasParaPublicar.forEach(msg -> eventPublisher.publishEvent(new EstoqueAtingiuMinimoEvent(msg)));

        return new ReciboResponse("Venda realizada com sucesso!", valorTotal, LocalDateTime.now());
    }

    /**
     * Verifica se houve uma TRANSIÇÃO de estado:
     * Antes estava confortável (> min) E agora ficou crítico (<= min).
     */
    private boolean cruzouLimite(BigDecimal antes, BigDecimal depois, BigDecimal limite) {
        return antes.compareTo(limite) > 0 && depois.compareTo(limite) <= 0;
    }
}