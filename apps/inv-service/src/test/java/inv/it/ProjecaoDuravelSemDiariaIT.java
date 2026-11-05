package inv.it;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@TestPropertySource(properties = "finance.projection.diaria.enabled=false")
class ProjecaoDuravelSemDiariaIT extends ProjecaoDuravelSupport {
    @Test
    void deveRecuperarCreditoEEstornoEQuitarSemExigirDiaria() {
        registrarCreditoEEstorno();
        assertThat(contar("projection_retry_queue")).isEqualTo(2);
        tornarElegivel();

        scheduler.reprocessarFalhasDeProjecao();
        scheduler.reprocessarFalhasDeProjecao();

        assertThat(contar("projection_retry_queue")).isZero();
        assertThat(contar("financial_projection_checkpoint_v2")).isEqualTo(2);
        assertThat(contar("financial_projection_diaria_checkpoint")).isZero();
        assertThat(contar("financial_projection_diaria")).isZero();
        conferirVenda("0.00", "100.00");
        verify(diariaRepository, never()).aplicar(anyLong());
    }

    @Test
    void falhaAtrasadaNaoDeveExigirCheckpointDaDiariaDesabilitada() throws Exception {
        var event = registrarCredito();
        projectionListener.projetar(event);
        doThrow(new IllegalStateException("falha atrasada")).when(projectionListener).projetar(event);

        projectionListener.processarComIdempotencia(event);

        assertThat(contar("projection_retry_queue")).isZero();
        assertThat(contar("financial_projection_diaria_checkpoint")).isZero();
    }
}
