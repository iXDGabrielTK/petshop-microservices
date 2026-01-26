package inv.service;

import common.exception.BusinessException;
import common.exception.ResourceNotFoundException;
import inv.dto.ItemVendaRequest;
import inv.dto.ReciboResponse;
import inv.dto.VendaRequest;
import inv.model.Produto;
import inv.repository.ProdutoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
public class VendaService {

    private final ProdutoRepository produtoRepository;

    public VendaService(ProdutoRepository produtoRepository) {
        this.produtoRepository = produtoRepository;
    }

    @Transactional
    public ReciboResponse realizarVenda(VendaRequest pedido) {
        BigDecimal valorTotal = BigDecimal.ZERO;

        for (ItemVendaRequest item : pedido.itens()) {
            Produto produto = produtoRepository.findById(item.produtoId())
                    .orElseThrow(() -> new ResourceNotFoundException("Produto não encontrado: ID " + item.produtoId()));

            // Validação de Estoque
            if (produto.getQuantidadeEstoque().compareTo(item.quantidade()) < 0) {
                throw new BusinessException(String.format(
                        "Estoque insuficiente para o produto '%s'. Disponível: %s, Solicitado: %s",
                        produto.getNome(), produto.getQuantidadeEstoque(), item.quantidade()
                ));
            }

            // Baixa no Estoque
            produto.setQuantidadeEstoque(produto.getQuantidadeEstoque().subtract(item.quantidade()));
            produtoRepository.save(produto);

            // Cálculo do Preço (Qtd * Preço Unitário)
            BigDecimal subtotal = produto.getPrecoVenda().multiply(item.quantidade());
            valorTotal = valorTotal.add(subtotal);
        }

        return new ReciboResponse("Venda realizada com sucesso!", valorTotal, LocalDateTime.now());
    }
}