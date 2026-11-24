package kr.hetagram.stock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CollectorGraceTest {
    @TempDir Path directory;
    private Loans.Debt bankruptAt(long millis) {var debt=new Loans.Debt();debt.bankrupt=true;debt.bankruptAt=millis;debt.originalPrincipal=debt.principal=30000000;return debt;}
    private boolean eligible(Loans.Debt debt,long clock,long grace) {return CollectorPolicy.eligible(debt,true,true,true,false,false,true,clock,grace);}

    @Test void graceStartsAtBankruptcyAndOnlyExpiresAtTheExactMarketTimeBoundary() {
        var debt=bankruptAt(600000);
        assertFalse(eligible(debt,600000,30000));assertFalse(eligible(debt,629999,30000));assertTrue(eligible(debt,630000,30000));
        assertEquals(30000,CollectorPolicy.graceRemainingMillis(debt,600000,30000));assertEquals(1,CollectorPolicy.graceRemainingMillis(debt,629999,30000));
        assertEquals(0,CollectorPolicy.graceRemainingMillis(debt,630000,30000));assertEquals(600000,debt.bankruptAt);
    }
    @Test void pauseOfflineAndRejoinDoNotRestartOrConsumeTheStoredGraceClock() {
        var debt=bankruptAt(100000);long clock=115000;
        assertFalse(CollectorPolicy.eligible(debt,true,false,true,false,false,true,clock,30000));
        assertFalse(CollectorPolicy.eligible(debt,true,true,false,false,false,true,clock,30000));
        assertEquals(15000,CollectorPolicy.graceRemainingMillis(debt,clock,30000));
        assertFalse(eligible(debt,129999,30000));assertTrue(eligible(debt,130000,30000));assertEquals(100000,debt.bankruptAt);
        var other=bankruptAt(125000);assertFalse(eligible(other,130000,30000));
    }
    @Test void graceHandlesHugeMarketClocksWithoutOverflowAndZeroMeansImmediate() {
        var debt=bankruptAt(Long.MAX_VALUE-20000);
        assertFalse(eligible(debt,Long.MAX_VALUE,30000));assertEquals(10000,CollectorPolicy.graceRemainingMillis(debt,Long.MAX_VALUE,30000));
        debt.bankruptAt=Long.MAX_VALUE-30000;assertTrue(eligible(debt,Long.MAX_VALUE,30000));
        assertTrue(eligible(debt,debt.bankruptAt,0));assertFalse(eligible(debt,debt.bankruptAt-1,0));
        assertFalse(eligible(debt,Long.MAX_VALUE,-1));debt.collectorRestUntil=Long.MAX_VALUE;assertTrue(eligible(debt,Long.MAX_VALUE,0));
        assertFalse(eligible(debt,Long.MAX_VALUE-1,0));
    }
    @Test void thirtySecondGraceAndTwoSecondAttacksCannotBeBypassedByExtraRefreshes() {
        var debt=bankruptAt(0);long nextAttack=Long.MAX_VALUE;int hits=0;long firstHit=-1;
        for(long tick=1;tick<=800;tick++) {
            if(eligible(debt,tick*50,30000)&&nextAttack==Long.MAX_VALUE)nextAttack=tick+40;
            assertFalse(CollectorPolicy.attackDue(false,tick,nextAttack,1,1.8,true));
            if(tick%5==0&&eligible(debt,tick*50,30000)&&CollectorPolicy.attackDue(true,tick,nextAttack,1,1.8,true)) {
                if(firstHit<0)firstHit=tick;hits++;nextAttack=tick+40;
            }
        }
        assertEquals(640,firstHit);assertEquals(5,hits);
    }
    @Test void onlyTheAssignedCollectorsLastHitCatchesThePlayerAndTheCollectorReturnsAfterItsRest() {
        var debt=bankruptAt(0);UUID player=UUID.randomUUID(),collector=UUID.randomUUID();long owed=debt.balance();
        assertTrue(CollectorPolicy.caughtByAssignedCollector(debt,player,player.toString(),collector,collector,40000,60000));
        assertEquals(100000,debt.collectorRestUntil);assertTrue(debt.bankrupt);assertEquals(owed,debt.balance());
        assertFalse(eligible(debt,99999,30000));assertTrue(eligible(debt,100000,30000),"빚이 남아 있으면 휴식 뒤 다시 찾아옵니다");
        assertEquals(60000,CollectorPolicy.graceRemainingMillis(debt,40000,30000));
    }
    @Test void EnvironmentalOtherPlayerAndOtherCollectorsDeathsDoNotGrantARest() {
        UUID player=UUID.randomUUID(),assigned=UUID.randomUUID(),other=UUID.randomUUID();
        var debt=bankruptAt(0);long owed=debt.balance();
        assertFalse(CollectorPolicy.caughtByAssignedCollector(debt,player,player.toString(),assigned,null,40000,60000));
        assertFalse(CollectorPolicy.caughtByAssignedCollector(debt,player,player.toString(),assigned,player,40000,60000));
        assertFalse(CollectorPolicy.caughtByAssignedCollector(debt,player,player.toString(),assigned,other,40000,60000));
        assertFalse(CollectorPolicy.caughtByAssignedCollector(debt,player,other.toString(),assigned,assigned,40000,60000));
        assertFalse(CollectorPolicy.caughtByAssignedCollector(debt,player,null,assigned,assigned,40000,60000));
        assertFalse(CollectorPolicy.caughtByAssignedCollector(debt,player,player.toString(),null,assigned,40000,60000));
        assertEquals(0,debt.collectorRestUntil);assertEquals(owed,debt.balance());assertTrue(eligible(debt,30000,30000));
    }
    @Test void restartRestoresTheOriginalBankruptcyTimeInsteadOfGrantingAnotherGrace() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml"))
            try(var stream=getClass().getResourceAsStream("/"+name)){Files.copy(Objects.requireNonNull(stream),directory.resolve(name));}
        var config=GameConfig.load(directory.toFile());var market=new Market(config);market.running=true;UUID player=UUID.randomUUID();
        var account=market.account(player);account.cash=0;new Loans(market).borrow(player,config.loanRules().defaultPrincipal());
        account.cash=0;var debt=account.debt;debt.bankrupt=true;debt.bankruptAt=debt.dueAt+180000;
        market.clockMillis=debt.bankruptAt+10000;var store=new StateStore(directory.toFile());store.save(market);
        byte[] saved=Files.readAllBytes(directory.resolve("state.yml"));var restored=new Market(config);store.load(restored);
        var loaded=restored.account(player).debt;assertEquals(debt.bankruptAt,loaded.bankruptAt);
        assertEquals(20000,CollectorPolicy.graceRemainingMillis(loaded,restored.clockMillis,30000));
        assertFalse(eligible(loaded,restored.clockMillis,30000));assertTrue(eligible(loaded,loaded.bankruptAt+30000,30000));
        assertArrayEquals(saved,Files.readAllBytes(directory.resolve("state.yml")));
    }
}
