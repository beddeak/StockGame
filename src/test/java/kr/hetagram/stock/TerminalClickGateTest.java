package kr.hetagram.stock;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TerminalClickGateTest {
    @Test void repeatedAttemptsInSameTickAreRejectedAndNextTickIsAllowed() {
        var gate=new TerminalClickGate();var player=UUID.randomUUID();
        assertTrue(gate.allow(player,100));
        assertFalse(gate.allow(player,100));
        assertFalse(gate.allow(player,100));
        assertTrue(gate.allow(player,101));
        assertFalse(gate.allow(player,101));
    }

    @Test void differentPlayersCanEachTradeOnceInTheSameTick() {
        var gate=new TerminalClickGate();var first=UUID.randomUUID();var second=UUID.randomUUID();
        assertTrue(gate.allow(first,50));assertTrue(gate.allow(second,50));
        assertFalse(gate.allow(first,50));assertFalse(gate.allow(second,50));
        assertTrue(gate.allow(first,51));assertFalse(gate.allow(second,50));
    }

    @Test void forgetClearsOnlyThatPlayerAndClearReleasesAllPlayers() {
        var gate=new TerminalClickGate();var first=UUID.randomUUID();var second=UUID.randomUUID();
        assertTrue(gate.allow(first,200));assertTrue(gate.allow(second,200));
        gate.forget(first);
        assertTrue(gate.allow(first,200));assertFalse(gate.allow(second,200));
        gate.clear();
        assertTrue(gate.allow(first,200));assertTrue(gate.allow(second,200));
        assertFalse(gate.allow(first,200));assertFalse(gate.allow(second,200));
    }
}
