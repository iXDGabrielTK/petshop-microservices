package inv.finance.infrastructure.messaging;

import inv.finance.domain.model.TipoLancamento;
import inv.finance.infrastructure.persistence.LancamentoFinanceiroRepository;
import inv.shared.event.VendaPagaEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VendaPagaEventListenerTest {

    @Mock
    private LancamentoFinanceiroRepository lancamentoRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private RegistroPendenciaProjecao registroPendencia;

    private VendaPagaEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new VendaPagaEventListener(lancamentoRepository, eventPublisher, registroPendencia);
    }

    @Test
    @DisplayName("DEVE inserir lançamento e publicar LancamentoRegistradoEvent quando referência for inédita")
    void deveInserirLancamentoEPublicarEventoQuandoReferenciaInedita() {
        Long vendaId = 10L;
        UUID uuidRef = UUID.randomUUID();
        BigDecimal valor = new BigDecimal("100.00");
        VendaPagaEvent event = new VendaPagaEvent(vendaId, uuidRef, valor);

        when(lancamentoRepository.existsByReferencia(uuidRef)).thenReturn(false);
        when(lancamentoRepository.inserirIdempotente(vendaId, uuidRef, TipoLancamento.CREDITO.name(), valor))
                .thenReturn(Optional.of(1L));

        listener.onVendaPaga(event);

        verify(lancamentoRepository).existsByReferencia(uuidRef);
        verify(lancamentoRepository).inserirIdempotente(vendaId, uuidRef, TipoLancamento.CREDITO.name(), valor);

        ArgumentCaptor<LancamentoRegistradoEvent> captor = ArgumentCaptor.forClass(LancamentoRegistradoEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        LancamentoRegistradoEvent publicado = captor.getValue();
        assertThat(publicado.lancamentoId()).isEqualTo(1L);
        assertThat(publicado.vendaId()).isEqualTo(vendaId);
        assertThat(publicado.valorAssinado()).isEqualByComparingTo("100.00");
        assertThat(publicado.tipo()).isEqualTo(TipoLancamento.CREDITO);

        // Pendência de projeção persistida na transação do ledger, antes da publicação do evento
        InOrder ordem = inOrder(registroPendencia, eventPublisher);
        ordem.verify(registroPendencia).registrarNaTransacaoDoLedger(publicado);
        ordem.verify(eventPublisher).publishEvent(publicado);
    }

    @Test
    @DisplayName("DEVE ignorar processamento (no-op) quando existsByReferencia retornar true")
    void deveIgnorarProcessamentoQuandoReferenciaJaExistirPreviamente() {
        Long vendaId = 10L;
        UUID uuidRef = UUID.randomUUID();
        BigDecimal valor = new BigDecimal("100.00");
        VendaPagaEvent event = new VendaPagaEvent(vendaId, uuidRef, valor);

        when(lancamentoRepository.existsByReferencia(uuidRef)).thenReturn(true);

        listener.onVendaPaga(event);

        verify(lancamentoRepository).existsByReferencia(uuidRef);
        verify(lancamentoRepository, never()).inserirIdempotente(any(), any(), any(), any());
        verifyNoInteractions(eventPublisher);
        verifyNoInteractions(registroPendencia);
    }

    @Test
    @DisplayName("DEVE não publicar evento quando inserção concorrente sofrer ON CONFLICT (retorno Optional vazio)")
    void deveNaoPublicarEventoQuandoInsercaoConcorrenteRetornarVazio() {
        Long vendaId = 10L;
        UUID uuidRef = UUID.randomUUID();
        BigDecimal valor = new BigDecimal("100.00");
        VendaPagaEvent event = new VendaPagaEvent(vendaId, uuidRef, valor);

        when(lancamentoRepository.existsByReferencia(uuidRef)).thenReturn(false);
        when(lancamentoRepository.inserirIdempotente(vendaId, uuidRef, TipoLancamento.CREDITO.name(), valor))
                .thenReturn(Optional.empty());

        listener.onVendaPaga(event);

        verify(lancamentoRepository).existsByReferencia(uuidRef);
        verify(lancamentoRepository).inserirIdempotente(vendaId, uuidRef, TipoLancamento.CREDITO.name(), valor);
        verifyNoInteractions(eventPublisher);
        verifyNoInteractions(registroPendencia);
    }

    @Test
    @DisplayName("DEVE normalizar valor negativo para absoluto no lançamento a crédito")
    void deveNormalizarValorNegativoParaAbsoluto() {
        Long vendaId = 10L;
        UUID uuidRef = UUID.randomUUID();
        BigDecimal valorNegativo = new BigDecimal("-75.50");
        BigDecimal valorEsperado = new BigDecimal("75.50");
        VendaPagaEvent event = new VendaPagaEvent(vendaId, uuidRef, valorNegativo);

        when(lancamentoRepository.existsByReferencia(uuidRef)).thenReturn(false);
        when(lancamentoRepository.inserirIdempotente(vendaId, uuidRef, TipoLancamento.CREDITO.name(), valorEsperado))
                .thenReturn(Optional.of(2L));

        listener.onVendaPaga(event);

        verify(lancamentoRepository).inserirIdempotente(vendaId, uuidRef, TipoLancamento.CREDITO.name(), valorEsperado);

        ArgumentCaptor<LancamentoRegistradoEvent> captor = ArgumentCaptor.forClass(LancamentoRegistradoEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().valorAssinado()).isEqualByComparingTo("75.50");
    }

    @Test
    @DisplayName("DEVE assumir BigDecimal.ZERO quando valorRecebido for nulo")
    void deveAssumirValorZeroQuandoValorRecebidoForNulo() {
        Long vendaId = 10L;
        UUID uuidRef = UUID.randomUUID();
        VendaPagaEvent event = new VendaPagaEvent(vendaId, uuidRef, null);

        when(lancamentoRepository.existsByReferencia(uuidRef)).thenReturn(false);
        when(lancamentoRepository.inserirIdempotente(vendaId, uuidRef, TipoLancamento.CREDITO.name(), BigDecimal.ZERO))
                .thenReturn(Optional.of(3L));

        listener.onVendaPaga(event);

        verify(lancamentoRepository).inserirIdempotente(vendaId, uuidRef, TipoLancamento.CREDITO.name(), BigDecimal.ZERO);

        ArgumentCaptor<LancamentoRegistradoEvent> captor = ArgumentCaptor.forClass(LancamentoRegistradoEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().valorAssinado()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("DEVE ignorar com segurança (no-op) quando evento ou campos obrigatórios forem nulos")
    void deveIgnorarQuandoEventoOuCamposObrigatoriosForemNulos() {
        listener.onVendaPaga(null);
        listener.onVendaPaga(new VendaPagaEvent(null, UUID.randomUUID(), new BigDecimal("10.00")));
        listener.onVendaPaga(new VendaPagaEvent(10L, null, new BigDecimal("10.00")));

        verifyNoInteractions(lancamentoRepository, eventPublisher, registroPendencia);
    }
}
