package inv.service;

import common.exception.ResourceNotFoundException;
import inv.dto.ItemVendaRequest;
import inv.dto.ReciboResponse;
import inv.dto.VendaRequest;
import inv.model.Produto;
import inv.model.Venda;
import inv.repository.ProdutoRepository;
import inv.repository.VendaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    @Transactional
    public ReciboResponse realizarVenda(VendaRequest pedido) {
        // 1. Carregamento Eficiente (Batch Fetch)
        Map<Long, Produto> produtosMap = carregarProdutos(pedido);

        // 2. Construção da Venda (Orquestração de Domínio)
        Venda venda = new Venda();
        venda.setDataHora(LocalDateTime.now());

        // 3. Processamento dos Itens
        for (ItemVendaRequest itemRequest : pedido.itens()) {
            Produto produto = produtosMap.get(itemRequest.produtoId());

            // Adiciona na venda (Gera snapshot de preço/nome)
            venda.adicionarItem(produto, itemRequest.quantidade());

            estoqueService.baixarEstoquePorVenda(produto, itemRequest.quantidade(), venda);
        }

        // 4. Persistência (Cascade salva os ItensVenda automaticamente)
        vendaRepository.save(venda);

        return new ReciboResponse("Venda realizada com sucesso!", venda.getValorTotal(), venda.getDataHora());
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
}