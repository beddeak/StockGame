package kr.hetagram.stock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ParkourRewardsTest {
    @TempDir Path directory;
    private Market market;
    private final UUID player=UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach void setup() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml"))
            try(var in=getClass().getResourceAsStream("/"+name)) {Files.copy(Objects.requireNonNull(in),directory.resolve(name));}
        market=new Market(GameConfig.load(directory.toFile()));
    }

    @Test void exactCentRewardAndCooldownAreOnlyQuotedUntilTheCallerCommits() {
        var account=market.account(player);account.cash=123456;account.realized=-54321;account.parkourRewardReadyAt=1000;
        var receipt=ParkourRewards.quote(market,account,12345,1000,300);
        assertEquals(135801,receipt.cashAfter());assertEquals(301000,receipt.readyAt());
        assertEquals(123456,account.cash);assertEquals(1000,account.parkourRewardReadyAt);assertEquals(-54321,account.realized);
        assertEquals(1000,ParkourRewards.quote(market,account,1,1000,0).readyAt());
    }

    @Test void anUnexpiredCooldownAndInvalidInputsNeverChangeMoneyOrTheExistingDeadline() {
        var account=market.account(player);account.cash=999;account.parkourRewardReadyAt=2000;
        assertThrows(IllegalArgumentException.class,()->ParkourRewards.quote(market,account,1,1999,300));
        assertThrows(IllegalArgumentException.class,()->ParkourRewards.quote(market,account,0,2000,300));
        assertThrows(IllegalArgumentException.class,()->ParkourRewards.quote(market,account,-1,2000,300));
        assertThrows(IllegalArgumentException.class,()->ParkourRewards.quote(market,account,1,-1,300));
        assertThrows(IllegalArgumentException.class,()->ParkourRewards.quote(market,account,1,2000,-1));
        assertEquals(999,account.cash);assertEquals(2000,account.parkourRewardReadyAt);
        assertEquals(1000,ParkourRewards.quote(market,account,1,2000,300).cashAfter());
    }

    @Test void cashAssetAndTimestampOverflowAllLeaveTheWholeAccountUntouched() {
        var account=market.account(player);account.parkourRewardReadyAt=500;account.cash=Long.MAX_VALUE;
        assertThrows(ArithmeticException.class,()->ParkourRewards.quote(market,account,1,1000,300));
        assertEquals(Long.MAX_VALUE,account.cash);assertEquals(500,account.parkourRewardReadyAt);

        long value=market.quote("redtech").price;account.holdings.put("redtech",new Market.Position(1,value));account.cash=Long.MAX_VALUE-value;
        assertThrows(ArithmeticException.class,()->ParkourRewards.quote(market,account,1,1000,300));
        assertEquals(Long.MAX_VALUE-value,account.cash);assertEquals(500,account.parkourRewardReadyAt);
        assertEquals(1,account.holdings.get("redtech").quantity);assertEquals(value,account.holdings.get("redtech").cost);

        account.cash=123;
        assertThrows(ArithmeticException.class,()->ParkourRewards.quote(market,account,1,Long.MAX_VALUE-999,1));
        assertEquals(123,account.cash);assertEquals(500,account.parkourRewardReadyAt);
        assertEquals(value,account.holdings.get("redtech").cost);
    }

    @Test void savedRewardDeadlineStillBlocksAnotherRewardAfterAServerRestartUntilTheExactBoundary() throws Exception {
        var account=market.account(player);long now=1792200000123L;
        var receipt=ParkourRewards.quote(market,account,Market.cents("50000"),now,300);
        account.cash=receipt.cashAfter();account.parkourRewardReadyAt=receipt.readyAt();
        var store=new StateStore(directory.toFile());store.save(market);
        var restarted=new Market(market.config);store.load(restarted);var restored=restarted.account(player);
        assertThrows(IllegalArgumentException.class,()->ParkourRewards.quote(restarted,restored,1,now+299999,300));
        assertEquals(receipt.cashAfter(),restored.cash);assertEquals(receipt.readyAt(),restored.parkourRewardReadyAt);
        var next=ParkourRewards.quote(restarted,restored,1,now+300000,300);
        assertEquals(receipt.cashAfter()+1,next.cashAfter());assertEquals(now+600000,next.readyAt());
    }
}
