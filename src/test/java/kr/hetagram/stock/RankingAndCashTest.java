package kr.hetagram.stock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RankingAndCashTest {
    @TempDir Path directory;
    private GameConfig config;
    private Market market;
    private final UUID investor=UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final UUID saver=UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach void setup() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml"))
            try(var in=getClass().getResourceAsStream("/"+name)) {
                Files.copy(Objects.requireNonNull(in),directory.resolve(name));
            }
        config=GameConfig.load(directory.toFile());
        market=new Market(config);
        market.running=true;
    }

    @Test void cashAndEquityRankingsUseTheirOwnTotalsAndDeterministicTies() {
        market.account(investor).name="ALPHA";
        market.account(saver).name="alpha";
        assertEquals(List.of(saver,investor),market.rankings("cash",10).stream().map(Market.Rank::player).toList());
        market.account(investor).name="Aaron";
        market.account(saver).name="zulu";
        assertEquals(List.of(investor,saver),market.rankings("cash",10).stream().map(Market.Rank::player).toList());

        // A fixed price makes the ranking arithmetic independent of the editable default start price.
        market.setPrice("redtech",Market.cents("10000"));
        market.buy(investor,"redtech",20);
        market.setPrice("redtech",Market.cents("20000"));

        var cash=market.rankings("cash",10);
        assertEquals(List.of(saver,investor),cash.stream().map(Market.Rank::player).toList());
        assertEquals(Market.cents("1000000"),cash.getFirst().money());
        assertEquals(Market.cents("800000"),cash.getLast().money());
        var equity=market.rankings("equity",10);
        assertEquals(List.of(investor,saver),equity.stream().map(Market.Rank::player).toList());
        assertEquals(Market.cents("1200000"),equity.getFirst().money());
        assertEquals("Aaron",equity.getFirst().name());
        assertEquals(List.of(equity.getFirst()),market.rankings("equity",1));
    }

    @Test void initialCashRoundtripPreservesExistingAssetsAndAppliesToNewAndResetAccounts() throws Exception {
        market.buy(investor,"redtech",3);
        var original=market.account(investor);
        long cash=original.cash,cost=original.holdings.get("redtech").cost;
        long initialCash=Market.cents("123456.78");
        var updated=config.withInterval(600).withInitialCash(initialCash);
        updated.ui().save(directory.resolve("config.yml").toFile());
        var restoredConfig=GameConfig.load(directory.toFile());
        assertEquals(initialCash,restoredConfig.rules().initialCash());
        assertEquals(600,restoredConfig.rules().tickSeconds());
        assertEquals(config.stocks(),restoredConfig.stocks());
        assertEquals(config.news(),restoredConfig.news());

        market.reconfigure(restoredConfig);

        assertSame(original,market.account(investor));
        assertEquals(cash,original.cash);
        assertEquals(3,original.holdings.get("redtech").quantity);
        assertEquals(cost,original.holdings.get("redtech").cost);
        assertEquals(initialCash,market.account(saver).cash);
        market.reset();
        assertTrue(market.accounts.isEmpty());
        assertEquals(initialCash,market.account(investor).cash);
        assertTrue(market.account(investor).holdings.isEmpty());
        assertEquals(600,market.config.rules().tickSeconds());
    }

    @Test void preResetBackupIsUniqueIncludesUnsavedChangesAndDoesNotMutateLiveState() throws Exception {
        market.setPrice("redtech",Market.cents("10000"));
        market.buy(investor,"redtech",4);
        var store=new StateStore(directory.toFile());
        store.save(market);
        market.setPrice("redtech",Market.cents("15000"));
        market.sell(investor,"redtech",1);
        market.news("shield-up-01","deepoil",new Random(2));
        market.account(investor).name="투자자";
        market.clockMillis=4500;market.nextNewsMillis=9000;market.resetGeneration=2;
        market.latestHeadline="저장 직전 속보";
        var account=market.account(investor);var quote=market.quote("deepoil");
        long price=quote.price;
        var history=List.copyOf(quote.history);var effect=quote.effect;

        Path first=store.backupBeforeReset(market);
        Path second=store.backupBeforeReset(market);

        assertNotEquals(first,second);
        assertEquals(directory.resolve("backups"),first.getParent());
        assertTrue(first.getFileName().toString().startsWith("before-reset-"));
        assertTrue(first.getFileName().toString().endsWith(".yml"));
        assertArrayEquals(Files.readAllBytes(first),Files.readAllBytes(second));
        assertArrayEquals(Files.readAllBytes(first),Files.readAllBytes(directory.resolve("state.yml")));
        assertSame(account,market.account(investor));assertSame(quote,market.quote("deepoil"));
        assertEquals(Market.cents("975000"),account.cash);assertEquals(Market.cents("5000"),account.realized);
        assertEquals(3,account.holdings.get("redtech").quantity);
        assertEquals(price,quote.price);assertEquals(history,List.copyOf(quote.history));assertEquals(effect,quote.effect);
        assertTrue(market.running);assertEquals(4500,market.clockMillis);assertEquals(9000,market.nextNewsMillis);
        assertEquals(2,market.resetGeneration);assertEquals("저장 직전 속보",market.latestHeadline);

        Path restoreDirectory=Files.createDirectory(directory.resolve("restored"));
        Files.copy(first,restoreDirectory.resolve("state.yml"));
        var restored=new Market(config);
        new StateStore(restoreDirectory.toFile()).load(restored);
        var restoredAccount=restored.account(investor);
        assertEquals(account.cash,restoredAccount.cash);assertEquals(account.realized,restoredAccount.realized);
        assertEquals("투자자",restoredAccount.name);assertEquals(3,restoredAccount.holdings.get("redtech").quantity);
        assertEquals(Market.cents("30000"),restoredAccount.holdings.get("redtech").cost);
        assertEquals(price,restored.quote("deepoil").price);assertEquals(effect,restored.quote("deepoil").effect);
        assertEquals(history,List.copyOf(restored.quote("deepoil").history));
        assertTrue(restored.running);assertEquals(4500,restored.clockMillis);assertEquals(9000,restored.nextNewsMillis);
        assertEquals(2,restored.resetGeneration);assertEquals("저장 직전 속보",restored.latestHeadline);
    }

    @Test void accountNamesLatestNewsAndResetGenerationSurviveSaveLoad() throws Exception {
        market.account(investor).name="주식왕_새벽들";
        market.account(saver).name="OtherPlayer";
        market.resetGeneration=7;
        market.latestHeadline="새벽들 회사, 호밀 신제품 출시!\n추가 속보";
        var store=new StateStore(directory.toFile());
        store.save(market);

        var restored=new Market(config);
        store.load(restored);

        assertEquals("주식왕_새벽들",restored.account(investor).name);
        assertEquals("OtherPlayer",restored.account(saver).name);
        assertEquals(7,restored.resetGeneration);
        assertEquals(market.latestHeadline,restored.latestHeadline);
        restored.reset();
        assertEquals(8,restored.resetGeneration);
        assertEquals("",restored.latestHeadline);
    }
}
