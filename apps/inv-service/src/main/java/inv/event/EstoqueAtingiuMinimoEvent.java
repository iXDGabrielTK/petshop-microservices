package inv.event;

import inv.dto.EstoqueBaixoMessage;

public record EstoqueAtingiuMinimoEvent(EstoqueBaixoMessage payload) {
}