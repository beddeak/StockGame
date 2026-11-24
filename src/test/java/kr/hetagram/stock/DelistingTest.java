package kr.hetagram.stock;

import org.bukkit.configuration.file.YamlConfiguration;
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

class DelistingTest {
    @TempDir Path directory;
    private GameConfig config;
    private Market market;
    private final UUID player=UUID.randomUUID();

    @BeforeEach void setup() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml"))
            try(var in=getClass().getResourceAsStream("/"+name)) {
                Files.copy(Objects.requireNonNull(in),directory.resolve(name));
            }
        config=GameConfig.load(directory.toFile());
        market=new Market(config);
        market.running=true;
    }

    @Test void reachingExactDelistingThresholdWritesZeroCandleClearsNewsAndBlocksTrades() {
        market.buy(player,"redtech",2);
        market.news("mill-up-04","redtech",new Random(1));
        var quote=market.quote("redtech");
        assertNotNull(quote.effect);
        long before=quote.price,cash=market.account(player).cash,cost=market.account(player).holdings.get("redtech").cost;

        market.setPrice("redtech",quote.spec.delist());

        assertTrue(quote.delisted);
        assertEquals(0,quote.price);
        assertEquals(before,quote.previous);
        assertNull(quote.effect);
        var candle=quote.history.getLast();
        assertEquals(before,candle.open());
        assertEquals(0,candle.close());
        assertEquals(0,candle.low());
        assertEquals(0,market.value(market.account(player)));
        assertThrows(IllegalArgumentException.class,()->market.buy(player,"redtech",1));
        assertThrows(IllegalArgumentException.class,()->market.sell(player,"redtech",1));
        assertEquals(cash,market.account(player).cash);
        assertEquals(2,market.account(player).holdings.get("redtech").quantity);
        assertEquals(cost,market.account(player).holdings.get("redtech").cost);
    }

    @Test void zeroPriceAndRelistingFromZeroSurviveSaveLoadAndReset() throws Exception {
        market.buy(player,"redtech",1);
        market.setPrice("redtech",market.quote("redtech").spec.delist());
        var store=new StateStore(directory.toFile());
        store.save(market);
        Market restored=new Market(config);
        store.load(restored);
        assertTrue(restored.quote("redtech").delisted);
        assertEquals(0,restored.quote("redtech").price);
        assertEquals(0,restored.quote("redtech").history.getLast().low());
        assertEquals(1,restored.account(player).holdings.get("redtech").quantity);

        restored.setPrice("redtech",restored.quote("redtech").spec.start());
        store.save(restored);
        Market relisted=new Market(config);
        store.load(relisted);
        var quote=relisted.quote("redtech");
        assertFalse(quote.delisted);
        assertEquals(quote.spec.start(),quote.price);
        assertEquals(0,quote.previous);
        assertEquals(0,quote.history.getLast().open());
        assertEquals(0,quote.history.getLast().low());
        assertTrue(Double.isFinite(quote.change()));
        relisted.sell(player,"redtech",1);

        relisted.reset();
        store.save(relisted);
        Market reset=new Market(config);
        store.load(reset);
        assertFalse(reset.running);
        assertTrue(reset.accounts.isEmpty());
        assertFalse(reset.quote("redtech").delisted);
        assertEquals(config.stocks().get("redtech").start(),reset.quote("redtech").price);
        assertTrue(reset.quote("redtech").history.isEmpty());
    }

    @Test void legacyPositiveDelistedSaveLoadsAsZeroAndRetainsItsHistoryAndClock() throws Exception {
        var saved=new YamlConfiguration();
        saved.set("schema",1);
        saved.set("running",false);
        saved.set("clock",1234L);
        saved.set("next-news",5678L);
        saved.set("quotes.redtech.price",config.stocks().get("redtech").delist());
        saved.set("quotes.redtech.previous",Market.cents("200"));
        saved.set("quotes.redtech.delisted",true);
        saved.set("quotes.redtech.history",List.of("20000,20000,10000,10000"));
        saved.set("quotes.redtech.effect.rise",0.9);
        saved.set("quotes.redtech.effect.multiplier",2.0);
        saved.set("quotes.redtech.effect.expires",10000L);
        saved.save(directory.resolve("state.yml").toFile());
        String original=Files.readString(directory.resolve("state.yml"));

        var store=new StateStore(directory.toFile());
        store.load(market);

        assertTrue(market.quote("redtech").delisted);
        assertEquals(0,market.quote("redtech").price);
        long stalePrice=config.stocks().get("redtech").delist();
        assertEquals(stalePrice,market.quote("redtech").previous);
        assertNull(market.quote("redtech").effect);
        var expectedHistory=List.of(new Market.Candle(20000,20000,10000,10000),new Market.Candle(stalePrice,stalePrice,0,0));
        assertEquals(expectedHistory,List.copyOf(market.quote("redtech").history));
        assertEquals(1234,market.clockMillis);
        assertEquals(5678,market.nextNewsMillis);
        assertFalse(market.running);
        assertTrue(market.accounts.isEmpty());
        assertEquals(original,Files.readString(directory.resolve("state.yml")));
        store.save(market);
        Market restored=new Market(config);
        store.load(restored);
        assertEquals(0,restored.quote("redtech").price);
        assertTrue(restored.quote("redtech").delisted);
        assertEquals(expectedHistory,List.copyOf(restored.quote("redtech").history));
    }

    @Test void reloadingHigherDelistingThresholdWritesZeroAndOnlySetPriceRelists() throws Exception {
        market.buy(player,"redtech",2);
        market.news("mill-up-04","redtech",new Random(1));
        market.setPrice("redtech",Market.cents("500"));
        var quote=market.quote("redtech");
        // 관리자 가격 지정은 기존 뉴스를 끝냅니다. 이 검증은 reload의 뉴스 정리를 위한 별도 효과를 둡니다.
        quote.effect=new Market.Effect(.9,2,10000);
        assertNotNull(quote.effect);assertFalse(quote.delisted);
        var originalHistory=List.copyOf(quote.history);
        long cash=market.account(player).cash,cost=market.account(player).holdings.get("redtech").cost;
        var stocks=new YamlConfiguration();
        stocks.load(directory.resolve("stocks.yml").toFile());
        stocks.set("stocks.redtech.delist-price",600);
        stocks.save(directory.resolve("stocks.yml").toFile());

        market.reconfigure(GameConfig.load(directory.toFile()));

        assertTrue(quote.delisted);assertEquals(0,quote.price);assertEquals(Market.cents("500"),quote.previous);
        assertNull(quote.effect);assertEquals(originalHistory.size()+1,quote.history.size());
        assertEquals(originalHistory,List.copyOf(quote.history).subList(0,originalHistory.size()));
        assertEquals(new Market.Candle(Market.cents("500"),Market.cents("500"),0,0),quote.history.getLast());
        assertEquals(cash,market.account(player).cash);assertEquals(cost,market.account(player).holdings.get("redtech").cost);
        assertEquals(2,market.account(player).holdings.get("redtech").quantity);
        assertThrows(IllegalArgumentException.class,()->market.sell(player,"redtech",1));

        stocks.set("stocks.redtech.delist-price",50);
        stocks.save(directory.resolve("stocks.yml").toFile());
        market.reconfigure(GameConfig.load(directory.toFile()));
        var delistedHistory=List.copyOf(quote.history);
        market.tick(new Random(2));
        assertTrue(quote.delisted);assertEquals(0,quote.price);
        assertEquals(delistedHistory,List.copyOf(quote.history));
        assertThrows(IllegalArgumentException.class,()->market.buy(player,"redtech",1));

        market.setPrice("redtech",Market.cents("500"));
        assertFalse(quote.delisted);assertEquals(Market.cents("500"),quote.price);assertEquals(0,quote.previous);
        assertEquals(new Market.Candle(0,Market.cents("500"),0,Market.cents("500")),quote.history.getLast());
        assertEquals(1,market.sell(player,"redtech",1).quantity());
    }

    @Test void tinyDownwardMovesCannotStayFrozenAboveDelistingThreshold() throws Exception {
        var stocks=new YamlConfiguration();
        stocks.load(directory.resolve("stocks.yml").toFile());
        stocks.set("stocks.redtech.start-price",0.04);
        stocks.set("stocks.redtech.delist-price",0.01);
        stocks.set("stocks.redtech.min-change-percent",0.1);
        stocks.set("stocks.redtech.max-change-percent",0.1);
        stocks.set("stocks.redtech.base-rise-chance",0.0);
        stocks.save(directory.resolve("stocks.yml").toFile());
        market=new Market(GameConfig.load(directory.toFile()));
        market.running=true;
        assertEquals(4,market.quote("redtech").price);

        var random=new Random(2);
        market.tick(random);
        assertEquals(3,market.quote("redtech").price);
        market.tick(random);
        assertEquals(2,market.quote("redtech").price);
        market.tick(random);

        var quote=market.quote("redtech");
        assertTrue(quote.delisted);
        assertEquals(0,quote.price);
        assertEquals(new Market.Candle(2,2,0,0),quote.history.getLast());
        int candles=quote.history.size();
        market.tick(random);
        assertEquals(0,quote.price);
        assertEquals(candles,quote.history.size());
    }
}
