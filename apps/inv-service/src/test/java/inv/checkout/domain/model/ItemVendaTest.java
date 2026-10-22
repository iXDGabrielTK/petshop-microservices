package inv.checkout.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ItemVenda — Testes Unitários de Domínio")
class ItemVendaTest {

    private final Venda vendaPadrao = new Venda();

    @Nested
    @DisplayName("Invariantes de criação via ItemVenda.criar(...)")
    class InvariantesCriacao {

        @Test
        @DisplayName("Deve falhar ao criar com venda nula (NullPointerException)")
        void deveFalharComVendaNula() {
            assertThatNullPointerException()
                    .isThrownBy(() -> ItemVenda.criar(
                            null,
                            1L,
                            "Produto Teste",
                            new BigDecimal("10.00"),
                            new BigDecimal("1.000")
                    ))
                    .withMessage("venda é obrigatória");
        }

        @ParameterizedTest(name = "produtoId inválido: {0}")
        @NullSource
        @ValueSource(longs = {0L, -1L, -99L})
        @DisplayName("Deve falhar com produtoId nulo ou menor/igual a zero")
        void deveFalharComProdutoIdInvalido(Long produtoIdInvalido) {
            assertThatThrownBy(() -> ItemVenda.criar(
                    vendaPadrao,
                    produtoIdInvalido,
                    "Produto Teste",
                    new BigDecimal("10.00"),
                    new BigDecimal("1.000")
            ))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("produtoId deve ser positivo");
        }

        @ParameterizedTest(name = "nomeProdutoSnapshot inválido: ''{0}''")
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "\n"})
        @DisplayName("Deve falhar com nomeProdutoSnapshot nulo ou em branco")
        void deveFalharComNomeProdutoSnapshotInvalido(String nomeInvalido) {
            assertThatThrownBy(() -> ItemVenda.criar(
                    vendaPadrao,
                    1L,
                    nomeInvalido,
                    new BigDecimal("10.00"),
                    new BigDecimal("1.000")
            ))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("nomeProdutoSnapshot é obrigatório");
        }

        @Test
        @DisplayName("Deve falhar com precoUnitarioSnapshot nulo")
        void deveFalharComPrecoUnitarioNulo() {
            assertThatThrownBy(() -> ItemVenda.criar(
                    vendaPadrao,
                    1L,
                    "Produto Teste",
                    null,
                    new BigDecimal("1.000")
            ))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("precoUnitarioSnapshot não pode ser negativo");
        }

        @ParameterizedTest(name = "precoUnitarioSnapshot negativo: {0}")
        @ValueSource(strings = {"-0.01", "-1.00", "-100.50"})
        @DisplayName("Deve falhar com precoUnitarioSnapshot negativo")
        void deveFalharComPrecoUnitarioNegativo(String precoNegativo) {
            assertThatThrownBy(() -> ItemVenda.criar(
                    vendaPadrao,
                    1L,
                    "Produto Teste",
                    new BigDecimal(precoNegativo),
                    new BigDecimal("1.000")
            ))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("precoUnitarioSnapshot não pode ser negativo");
        }

        @Test
        @DisplayName("Deve permitir criação com precoUnitarioSnapshot igual a zero")
        void devePermitirPrecoUnitarioZero() {
            ItemVenda item = ItemVenda.criar(
                    vendaPadrao,
                    1L,
                    "Amostra Grátis",
                    BigDecimal.ZERO,
                    new BigDecimal("1.000")
            );

            assertThat(item.getPrecoUnitarioSnapshot()).isEqualTo(BigDecimal.ZERO);
            assertThat(item.getSubTotal()).isEqualByComparingTo(new BigDecimal("0.00"));
        }

        @Test
        @DisplayName("Deve falhar com quantidade nula")
        void deveFalharComQuantidadeNula() {
            assertThatThrownBy(() -> ItemVenda.criar(
                    vendaPadrao,
                    1L,
                    "Produto Teste",
                    new BigDecimal("10.00"),
                    null
            ))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("quantidade deve ser positiva");
        }

        @ParameterizedTest(name = "quantidade inválida: {0}")
        @ValueSource(strings = {"0", "0.000", "-0.001", "-1", "-10"})
        @DisplayName("Deve falhar com quantidade menor ou igual a zero")
        void deveFalharComQuantidadeMenorOuIgualAZero(String qtdInvalida) {
            assertThatThrownBy(() -> ItemVenda.criar(
                    vendaPadrao,
                    1L,
                    "Produto Teste",
                    new BigDecimal("10.00"),
                    new BigDecimal(qtdInvalida)
            ))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("quantidade deve ser positiva");
        }

        @Test
        @DisplayName("Deve criar ItemVenda com sucesso quando todos os dados forem válidos")
        void deveCriarItemVendaComSucesso() {
            Long produtoId = 42L;
            String nome = "Ração Premium 15kg";
            BigDecimal preco = new BigDecimal("189.90");
            BigDecimal quantidade = new BigDecimal("2.500");

            ItemVenda item = ItemVenda.criar(vendaPadrao, produtoId, nome, preco, quantidade);

            assertThat(item).isNotNull();
            assertThat(item.getId()).isNull();
            assertThat(item.getVenda()).isSameAs(vendaPadrao);
            assertThat(item.getProdutoId()).isEqualTo(produtoId);
            assertThat(item.getNomeProdutoSnapshot()).isEqualTo(nome);
            assertThat(item.getPrecoUnitarioSnapshot()).isEqualTo(preco);
            assertThat(item.getQuantidade()).isEqualTo(quantidade);
        }
    }

    @Nested
    @DisplayName("Cálculo de getSubTotal()")
    class CalculoSubTotal {

        @Test
        @DisplayName("Deve calcular subtotal corretamente com valores válidos")
        void deveCalcularSubTotalValoresValidos() {
            ItemVenda item = ItemVenda.criar(
                    vendaPadrao,
                    1L,
                    "Coleira Antipulgas",
                    new BigDecimal("45.50"),
                    new BigDecimal("3")
            );

            assertThat(item.getSubTotal()).isEqualByComparingTo(new BigDecimal("136.50"));
            assertThat(item.getSubTotal().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("Deve calcular subtotal corretamente com quantidade fracionária")
        void deveCalcularSubTotalQuantidadeFracionaria() {
            ItemVenda item = ItemVenda.criar(
                    vendaPadrao,
                    1L,
                    "Ração a Granel",
                    new BigDecimal("15.30"),
                    new BigDecimal("2.500")
            );

            // 15.30 * 2.500 = 38.25000 -> scale 2: 38.25
            assertThat(item.getSubTotal()).isEqualByComparingTo(new BigDecimal("38.25"));
            assertThat(item.getSubTotal().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("Deve aplicar arredondamento HALF_EVEN (Banker's rounding) a duas casas decimais")
        void deveAplicarArredondamentoHalfEven() {
            // 1 * 10.545 -> arredonda para 10.54 (4 é par)
            ItemVenda itemPar = ItemVenda.criar(
                    vendaPadrao,
                    1L,
                    "Item 1",
                    new BigDecimal("10.545"),
                    BigDecimal.ONE
            );
            assertThat(itemPar.getSubTotal()).isEqualTo(new BigDecimal("10.54"));

            // 1 * 10.555 -> arredonda para 10.56 (5 é ímpar, arredonda para par)
            ItemVenda itemImpar = ItemVenda.criar(
                    vendaPadrao,
                    2L,
                    "Item 2",
                    new BigDecimal("10.555"),
                    BigDecimal.ONE
            );
            assertThat(itemImpar.getSubTotal()).isEqualTo(new BigDecimal("10.56"));
        }

        @Test
        @DisplayName("Deve retornar 0.00 com escala 2 quando quantidade for nula")
        void deveRetornarZeroQuandoQuantidadeForNula() {
            ItemVenda item = ItemVenda.criar(
                    vendaPadrao,
                    1L,
                    "Item Teste",
                    new BigDecimal("29.90"),
                    new BigDecimal("1")
            );
            ReflectionTestUtils.setField(item, "quantidade", null);

            BigDecimal subTotal = item.getSubTotal();
            assertThat(subTotal).isEqualTo(new BigDecimal("0.00"));
            assertThat(subTotal.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("Deve retornar 0.00 com escala 2 quando precoUnitarioSnapshot for nulo")
        void deveRetornarZeroQuandoPrecoUnitarioForNulo() {
            ItemVenda item = ItemVenda.criar(
                    vendaPadrao,
                    1L,
                    "Item Teste",
                    new BigDecimal("29.90"),
                    new BigDecimal("1")
            );
            ReflectionTestUtils.setField(item, "precoUnitarioSnapshot", null);

            BigDecimal subTotal = item.getSubTotal();
            assertThat(subTotal).isEqualTo(new BigDecimal("0.00"));
            assertThat(subTotal.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("Deve retornar 0.00 com escala 2 quando ambos os campos forem nulos (instância vazia)")
        void deveRetornarZeroQuandoAmbosForemNulos() {
            ItemVenda itemVazio = new ItemVenda();

            BigDecimal subTotal = itemVazio.getSubTotal();
            assertThat(subTotal).isEqualTo(new BigDecimal("0.00"));
            assertThat(subTotal.scale()).isEqualTo(2);
        }
    }
}
