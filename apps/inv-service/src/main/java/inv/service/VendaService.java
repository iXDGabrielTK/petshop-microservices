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
import inv.model.TipoMovimentacao;
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

        LocalDateTime agora = LocalDateTime.now();

        for (ItemVendaRequest item : pedido.itens()) {
            Produto produto = mapaProdutos.get(item.produtoId());

            if (produto.getQuantidadeEstoque().compareTo(item.quantidade()) < 0) {
                throw new BusinessException("Estoque insuficiente para: " + produto.getNome());
            }

            produto.setQuantidadeEstoque(produto.getQuantidadeEstoque().subtract(item.quantidade()));

            MovimentacaoEstoque mov = new MovimentacaoEstoque();
            mov.setProduto(produto);
            mov.setTipo(TipoMovimentacao.SAIDA);
            mov.setQuantidade(item.quantidade());
            mov.setDataHora(agora);
            mov.setMotivo("Venda PDV");
            movimentacoes.add(mov);

            if (produto.getEstoqueMinimo() != null &&
                    produto.getQuantidadeEstoque().compareTo(produto.getEstoqueMinimo()) <= 0) {
                alertasParaPublicar.add(new EstoqueBaixoMessage(
                        produto.getNome(),
                        produto.getQuantidadeEstoque(),
                        produto.getEstoqueMinimo()
                ));
            }
            valorTotal = valorTotal.add(produto.getPrecoVenda().multiply(item.quantidade()));
        }

        produtoRepository.saveAll(produtosEncontrados);
        movimentacaoRepository.saveAll(movimentacoes);

        alertasParaPublicar.forEach(msg -> eventPublisher.publishEvent(new EstoqueAtingiuMinimoEvent(msg)));

        return new ReciboResponse("Venda realizada com sucesso!", valorTotal, LocalDateTime.now());
    }
}