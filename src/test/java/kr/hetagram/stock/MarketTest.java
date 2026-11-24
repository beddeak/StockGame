package kr.hetagram.stock;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MarketTest {
    @TempDir Path directory;
    GameConfig config;
    Market market;
    UUID player=UUID.randomUUID();
    @BeforeEach void setup() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml"))
            try(var in=getClass().getResourceAsStream("/"+name)){Files.copy(Objects.requireNonNull(in),directory.resolve(name));}
        // The user's editable company headlines/sectors must not redefine arithmetic fixtures.
        try(var in=getClass().getResourceAsStream("/market-fixture-news.yml")) {
            Files.copy(Objects.requireNonNull(in),directory.resolve("news.yml"),StandardCopyOption.REPLACE_EXISTING);
        }
        var stocks=new org.bukkit.configuration.file.YamlConfiguration();stocks.load(directory.resolve("stocks.yml").toFile());
        stocks.set("stocks.deepoil.sector","fixture-market");stocks.save(directory.resolve("stocks.yml").toFile());
        config=GameConfig.load(directory.toFile());market=new Market(config);market.running=true;
    }
    void change(String file,String before,String after) throws Exception { var p=directory.resolve(file);Files.writeString(p,Files.readString(p).replace(before,after));config=GameConfig.load(directory.toFile());market.reconfigure(config); }
    @Test void weightedAverageAndPartialSalePreserveCost() {
        // Use explicit arithmetic fixtures so changing the shipped starting balance does not change this case.
        market.setPrice("redtech",Market.cents("10000"));
        market.buy(player,"redtech",10);market.setPrice("redtech",Market.cents("5000"));market.buy(player,"redtech",10);
        var a=market.account(player);var p=a.holdings.get("redtech");assertEquals(Market.cents("7500"),p.average());
        var t=market.sell(player,"redtech",5);assertEquals(Market.cents("25000"),t.amount());assertEquals(Market.cents("112500"),p.cost);assertEquals(Market.cents("-12500"),a.realized);
    }
    @Test void fullBuyNeverOverdraws() {long cash=config.rules().initialCash(),price=market.quote("redtech").price;var t=market.buy(player,"redtech",-1);assertEquals(cash/price,t.quantity());assertEquals(cash%price,market.account(player).cash);}
    @Test void feeIncludedInAverageAndMaxBuy() throws Exception {
        market.setPrice("redtech",Market.cents("10000"));
        change("config.yml","buy-fee-percent: 0.0","buy-fee-percent: 1.0");var t=market.buy(player,"redtech",-1);
        assertEquals(99,t.quantity());assertEquals(Market.cents("10100"),market.account(player).holdings.get("redtech").average());assertEquals(Market.cents("100"),market.account(player).cash);
    }
    @Test void saleFeeDeducted() throws Exception {
        market.setPrice("redtech",Market.cents("10000"));
        change("config.yml","sell-fee-percent: 0.0","sell-fee-percent: 1.0");market.buy(player,"redtech",1);assertEquals(Market.cents("9900"),market.sell(player,"redtech",-1).amount());
    }
    @Test void insufficientCashDoesNotMutateAccount() {var a=market.account(player);assertThrows(IllegalArgumentException.class,()->market.buy(player,"redtech",101));assertEquals(Market.cents("1000000"),a.cash);assertTrue(a.holdings.isEmpty());}
    @Test void oversellDoesNotMutatePosition() {market.buy(player,"redtech",10);assertThrows(IllegalArgumentException.class,()->market.sell(player,"redtech",11));assertEquals(10,market.account(player).holdings.get("redtech").quantity);}
    @Test void zeroAndNegativeQuantitiesRejected() {assertThrows(IllegalArgumentException.class,()->market.buy(player,"redtech",0));assertThrows(IllegalArgumentException.class,()->market.buy(player,"redtech",-2));}
    @Test void noTradingOrTickWhenStopped() {market.running=false;long p=market.quote("redtech").price;market.tick(new Random(1));assertEquals(p,market.quote("redtech").price);assertThrows(IllegalArgumentException.class,()->market.buy(player,"redtech",1));}
    @Test void delistedStockIsWorthZeroAndCannotTrade() {long cost=market.quote("redtech").price*10;market.buy(player,"redtech",10);market.setPrice("redtech",Market.cents("100"));assertTrue(market.quote("redtech").delisted);assertEquals(0,market.value(market.account(player)));assertEquals(-cost,market.profit(market.account(player)));assertThrows(IllegalArgumentException.class,()->market.sell(player,"redtech",1));}
    @Test void adminCanRelist() {market.setPrice("redtech",Market.cents("1"));market.setPrice("redtech",Market.cents("2000"));assertFalse(market.quote("redtech").delisted);market.buy(player,"redtech",1);}
    @Test void priceBoundsAndPrecision() {assertThrows(IllegalArgumentException.class,()->market.setPrice("redtech",0));assertThrows(IllegalArgumentException.class,()->market.setPrice("redtech",Long.MAX_VALUE));assertThrows(ArithmeticException.class,()->Market.cents("0.001"));}
    @Test void sharesLimitIsEnforced() throws Exception {change("config.yml","max-shares-per-stock: 1000000","max-shares-per-stock: 5");assertEquals(5,market.buy(player,"redtech",-1).quantity());assertThrows(IllegalArgumentException.class,()->market.buy(player,"redtech",1));}
    @Test void removingHeldStockIsRejectedBeforeMutation() {
        market.buy(player,"redtech",1);var nextStocks=new LinkedHashMap<>(config.stocks());nextStocks.remove("redtech");
        var next=new GameConfig(config.rules(),nextStocks,config.news(),config.ui(),config.map());assertThrows(IllegalArgumentException.class,()->market.reconfigure(next));assertTrue(market.quotes.containsKey("redtech"));assertSame(config,market.config);
    }
    @Test void zeroBasisReturnsAreFinite() {assertEquals(0,Market.returns(10,0));}
    @Test void seededNewsHonorsSectorAndInstantEffect() throws Exception {
        change("config.yml","reversal-chance: 0.15","reversal-chance: 0.0");long old=market.quote("deepoil").price;
        var b=market.news("fixture-market-boost","deepoil",new Random(2));assertEquals("deepoil",b.stockId());assertFalse(b.reversed());assertTrue(market.quote("deepoil").price>old);assertEquals(.9,market.quote("deepoil").effect.rise());
    }
    @Test void reversalInvertsActualEffects() throws Exception {
        change("config.yml","reversal-chance: 0.05","reversal-chance: 1.0");assertEquals(1.0,config.rules().reversal());long old=market.quote("deepoil").price;
        var b=market.news("fixture-market-boost","deepoil",new Random(2));assertTrue(b.reversed());assertTrue(market.quote("deepoil").price<old);assertEquals(.1,market.quote("deepoil").effect.rise(),1e-10);
    }
    @Test void incompatibleNewsAndStockIsRejected() {assertThrows(IllegalArgumentException.class,()->market.news("fixture-market-boost","redtech",new Random(2)));}
    @Test void expiredEffectIsRemovedAndHistoryBounded() {
        market.news("fixture-market-boost","deepoil",new Random(2));market.clockMillis=1000000;for(int i=0;i<100;i++)market.tick(new Random(i));
        assertNull(market.quote("deepoil").effect);assertTrue(market.quote("deepoil").history.size()<=48);
    }
    @Test void fullSaleRemovesAllRoundingResidue() {
        market.buy(player,"redtech",3);market.setPrice("redtech",Market.cents("12345.67"));market.buy(player,"redtech",2);
        market.sell(player,"redtech",1);market.sell(player,"redtech",-1);assertTrue(market.account(player).holdings.isEmpty());
    }
    @Test void stateRoundtripPreservesAssetsQuotesNewsClockAndStop() throws Exception {
        market.buy(player,"redtech",7);market.news("fixture-market-boost","deepoil",new Random(1));market.clockMillis=3000;market.nextNewsMillis=20000;market.running=false;
        var store=new StateStore(directory.toFile());store.save(market);Market restored=new Market(config);store.load(restored);
        assertFalse(restored.running);assertEquals(3000,restored.clockMillis);assertEquals(20000,restored.nextNewsMillis);assertEquals(market.account(player).cash,restored.account(player).cash);assertEquals(7,restored.account(player).holdings.get("redtech").quantity);
        assertEquals(market.quote("deepoil").price,restored.quote("deepoil").price);assertEquals(market.quote("deepoil").effect,restored.quote("deepoil").effect);assertEquals(List.copyOf(market.quote("deepoil").history),List.copyOf(restored.quote("deepoil").history));
    }
    @Test void secondSaveCreatesBackup() throws Exception {var store=new StateStore(directory.toFile());store.save(market);market.buy(player,"redtech",1);store.save(market);assertTrue(Files.exists(directory.resolve("state.yml.bak")));}
    @Test void corruptStateIsRejectedAndNeverRewritten() throws Exception {var p=directory.resolve("state.yml");Files.writeString(p,"schema: 123\n");assertThrows(IllegalArgumentException.class,()->new StateStore(directory.toFile()).load(market));assertEquals("schema: 123\n",Files.readString(p));}
    @Test void invalidConfigRejectsNaN() throws Exception {
        var p=directory.resolve("stocks.yml");Files.writeString(p,Files.readString(p).replace("base-rise-chance: 0.5","base-rise-chance: .NaN"));assertThrows(IllegalArgumentException.class,()->GameConfig.load(directory.toFile()));
    }
    @Test void negativePeriodRejected() throws Exception {var p=directory.resolve("config.yml");Files.writeString(p,Files.readString(p).replace("tick-seconds: 4","tick-seconds: -1"));assertThrows(IllegalArgumentException.class,()->GameConfig.load(directory.toFile()));}
    @Test void malformedCashDoesNotSilentlyBecomeZero() throws Exception {
        market.account(player);var store=new StateStore(directory.toFile());store.save(market);var p=directory.resolve("state.yml");
        Files.writeString(p,Files.readString(p).replace("cash: 100000000","cash: broken"));assertThrows(IllegalArgumentException.class,()->store.load(new Market(config)));
    }
    @Test void invalidYamlIsRejected() throws Exception {Files.writeString(directory.resolve("news.yml"),"news: [broken\n");assertThrows(Exception.class,()->GameConfig.load(directory.toFile()));}
    @Test void duplicateBuyAfterAllInCannotCreateShares() {long quantity=config.rules().initialCash()/market.quote("redtech").price;market.buy(player,"redtech",-1);assertThrows(IllegalArgumentException.class,()->market.buy(player,"redtech",-1));assertEquals(quantity,market.account(player).holdings.get("redtech").quantity);}
    @Test void totalAssetsConservedAtFlatPriceWithoutFees() {var a=market.account(player);long before=a.cash;market.buy(player,"redtech",3);assertEquals(before,a.cash+market.value(a));market.sell(player,"redtech",-1);assertEquals(before,a.cash);assertEquals(0,a.realized);}
    @Test void zeroIntervalMeansOneTickAndFractionsRoundUp() {
        assertEquals(50,GameConfig.intervalMillis(0));assertEquals(50,GameConfig.intervalMillis(.001));assertEquals(100,GameConfig.intervalMillis(.051));
        assertEquals(1500,GameConfig.intervalMillis(1.5));assertEquals(300000,GameConfig.intervalMillis(300));assertEquals(600000,GameConfig.intervalMillis(600));assertEquals(1200000,GameConfig.intervalMillis(1200));
    }
    @Test void invalidIntervalDoesNotChangeConfigOrAssets() {
        market.buy(player,"redtech",1);long cash=market.account(player).cash;
        for(double value:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY,86401})assertThrows(IllegalArgumentException.class,()->config.withInterval(value));
        assertEquals(4,config.rules().tickSeconds());assertEquals(cash,market.account(player).cash);
    }
    @Test void customIntervalPersistsAndPreservesHoldings() throws Exception {
        market.buy(player,"redtech",2);long price=market.quote("redtech").price,cash=market.account(player).cash;
        var changed=config.withInterval(600);changed.ui().save(directory.resolve("config.yml").toFile());
        var restored=GameConfig.load(directory.toFile());assertEquals(600,restored.rules().tickSeconds());market.reconfigure(restored);
        assertEquals(price,market.quote("redtech").price);assertEquals(cash,market.account(player).cash);assertEquals(2,market.account(player).holdings.get("redtech").quantity);
    }
    @Test void sectorNewsCanChooseEitherMatchingStock() throws Exception {
        // The shipped companies now have different real sectors. Only this fixture joins two targets.
        var stocks=new org.bukkit.configuration.file.YamlConfiguration();stocks.load(directory.resolve("stocks.yml").toFile());
        stocks.set("stocks.blueenergy.sector","fixture-market");stocks.save(directory.resolve("stocks.yml").toFile());
        config=GameConfig.load(directory.toFile());market.reconfigure(config);
        Set<String> targets=new HashSet<>();var random=new Random(12);for(int i=0;i<100;i++){var b=market.news("fixture-market-boost",null,random);assertEquals("fixture-market",market.quote(b.stockId()).spec.sector());targets.add(b.stockId());}
        assertEquals(Set.of("deepoil","blueenergy"),targets);
    }
    @Test void fiveFullCompanyNamesAndBlocksAreLoaded() {
        assertEquals(5,config.stocks().size());assertEquals("새벽들제분",config.stocks().get("redtech").name());
        assertEquals("현무암방호공업",config.stocks().get("deepoil").name());
        assertEquals("SPONGE",config.stocks().get("blueenergy").displayBlock().name());
        assertEquals(List.of(100000L,200000L,50000L,250000L,100000L),config.stocks().values().stream().map(stock->stock.start()/100).toList());
    }
    @Test void configuredHologramLineBreaksRenderAsSeparateLines() {
        var text=(net.kyori.adventure.text.TextComponent)Text.component("수량: 0\\n평단: 0");
        assertEquals("수량: 0\n평단: 0",text.content());
    }
    @Test void resetDiscardsEveryAccountAndUsesCurrentInitialCash() throws Exception {
        UUID other=UUID.randomUUID();
        long start=market.quote("redtech").price;
        market.buy(player,"redtech",10);market.setPrice("redtech",start+Market.cents("2000"));market.sell(player,"redtech",5);
        market.buy(other,"cloud",2);var original=market.account(player);assertTrue(original.realized>0);
        change("config.yml","initial-cash: 1000000","initial-cash: 750000");

        market.reset();

        assertTrue(market.accounts.isEmpty());
        for(UUID id:List.of(player,other)) {
            var fresh=market.account(id);assertEquals(Market.cents("750000"),fresh.cash);
            assertEquals(0,fresh.realized);assertTrue(fresh.holdings.isEmpty());
        }
        assertNotSame(original,market.account(player));
    }
    @Test void resetRestoresCurrentConfiguredStartPricesAndClearsDelistingNewsAndHistory() throws Exception {
        var stocks=new org.bukkit.configuration.file.YamlConfiguration();
        stocks.load(directory.resolve("stocks.yml").toFile());stocks.set("stocks.redtech.start-price",12345);
        stocks.save(directory.resolve("stocks.yml").toFile());config=GameConfig.load(directory.toFile());market.reconfigure(config);
        market.tick(new Random(1));market.news("fixture-market-boost","deepoil",new Random(2));
        market.setPrice("redtech",Market.cents("1"));
        assertTrue(market.quote("redtech").delisted);assertNotNull(market.quote("deepoil").effect);

        market.reset();

        assertEquals(config.stocks().keySet(),market.quotes.keySet());
        for(var spec:config.stocks().values()) {
            var quote=market.quote(spec.id());assertEquals(spec.start(),quote.price,spec.id());
            assertEquals(spec.start(),quote.previous,spec.id());assertFalse(quote.delisted,spec.id());
            assertNull(quote.effect,spec.id());assertTrue(quote.history.isEmpty(),spec.id());
        }
        assertEquals(Market.cents("12345"),market.quote("redtech").price);
    }
    @Test void resetPreservesCustomIntervalAndSavesAStoppedFreshRound() throws Exception {
        config=config.withInterval(600);market.reconfigure(config);
        market.buy(player,"redtech",3);market.news("fixture-market-boost","deepoil",new Random(3));
        market.clockMillis=45000;market.nextNewsMillis=90000;
        var store=new StateStore(directory.toFile());store.save(market);

        market.reset();store.save(market);

        assertSame(config,market.config);assertEquals(600,market.config.rules().tickSeconds());
        Market restored=new Market(config);store.load(restored);
        assertFalse(restored.running);assertEquals(0,restored.clockMillis);assertEquals(0,restored.nextNewsMillis);
        assertTrue(restored.accounts.isEmpty());assertNull(restored.quote("deepoil").effect);
        assertTrue(restored.quote("deepoil").history.isEmpty());
        long price=restored.quote("redtech").price;restored.tick(new Random(4));assertEquals(price,restored.quote("redtech").price);
        assertThrows(IllegalArgumentException.class,()->restored.buy(player,"redtech",1));
        restored.running=true;restored.buy(player,"redtech",1);
        assertEquals(config.rules().initialCash()-price,restored.account(player).cash);
    }
}
