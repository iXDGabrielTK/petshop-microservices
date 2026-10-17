package inv.inventory.api;

public record ResumoEstoque(long produtosAbaixoDoMinimo) {
    public ResumoEstoque {
        if (produtosAbaixoDoMinimo < 0) {
            throw new IllegalArgumentException("produtosAbaixoDoMinimo < 0");
        }
    }
}
