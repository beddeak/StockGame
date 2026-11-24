package kr.hetagram.stock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MarketReloadSnapshotTest {
    @TempDir Path directory;
    private GameConfig config;
    private Market market;
    private final UUID player=UUID.randomUUID();
    private static long money(String value){return Market.cents(value);}
    @BeforeEach void setup() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml"))
            try(var input=getClass().getResourceAsStream("/"+name)){Files.copy(Objects.requireNonNull(input),directory.resolve(name));}
        config=GameConfig.load(directory.toFile());market=new Market(config);market.running=true;
        market.setPrice("redtech",money("1000"));market.buy(player,"redtech",2);new Loans(market).borrow(player,money("300000"));
        market.clockMillis=45000;market.nextNewsMillis=90000;
        market.quote("redtech").effect=new Market.Effect(.8,1.5,120000,money("1000"),30);
    }
    private static GameConfig.Stock changed(GameConfig.Stock old,String id,String name,long delist) {
        return new GameConfig.Stock(id,name,old.sector(),old.icon(),old.start(),delist,old.minChange(),old.maxChange(),old.riseChance(),
                old.pedestal(),old.displayBlock(),List.of(id+"_alias"),old.skinName());
    }

    @Test void failedExternalNpcReloadRestoresQuoteIdentityOrderSpecsDelistingHistoryEffectsAndSettings() {
        var snapshot=new MarketReloadSnapshot(market);var quotes=new LinkedHashMap<>(market.quotes);var original=market.quote("redtech");
        var originalSpec=original.spec;var history=List.copyOf(original.history);var effect=original.effect;
        long price=original.price,previous=original.previous;var account=market.account(player);var position=account.holdings.get("redtech");var debt=account.debt;
        long cash=account.cash,cost=position.cost,quantity=position.quantity,realized=account.realized;
        var stocks=new LinkedHashMap<>(config.stocks());stocks.remove("cloud");
        stocks.put("redtech",changed(originalSpec,"redtech","changed name",money("1500")));
        var template=config.stocks().get("greenbio");stocks.put("extra1",changed(template,"extra1","extra one",template.delist()));
        stocks.put("extra2",changed(template,"extra2","extra two",template.delist()));
        var next=new GameConfig(config.rules(),stocks,config.news().stream().filter(news->!news.stocks().contains("cloud")).toList(),config.ui(),config.map());
        assertThrows(IllegalStateException.class,()->{
            market.reconfigure(next);market.nextNewsMillis=999999;
            assertTrue(original.delisted);assertEquals(0,original.price);assertNull(original.effect);assertEquals(6,market.quotes.size());
            throw new IllegalStateException("external NPC adapter failed");
        });
        snapshot.restore(market);snapshot.restore(market);
        assertSame(config,market.config);assertEquals(List.copyOf(quotes.keySet()),List.copyOf(market.quotes.keySet()));
        quotes.forEach((id,quote)->assertSame(quote,market.quote(id)));
        assertSame(originalSpec,original.spec);assertEquals(price,original.price);assertEquals(previous,original.previous);assertFalse(original.delisted);
        assertEquals(history,List.copyOf(original.history));assertSame(effect,original.effect);assertFalse(market.quotes.containsKey("extra1"));
        assertSame(account,market.account(player));assertSame(position,account.holdings.get("redtech"));assertSame(debt,account.debt);
        assertEquals(cash,account.cash);assertEquals(cost,position.cost);assertEquals(quantity,position.quantity);assertEquals(realized,account.realized);
        assertTrue(market.running);assertEquals(45000,market.clockMillis);assertEquals(90000,market.nextNewsMillis);
    }

    @Test void restoringQuoteConfigurationNeverReplacesAccountsOrRewindsTheirSeparateAssetChanges() {
        var snapshot=new MarketReloadSnapshot(market);var account=market.account(player);var position=account.holdings.get("redtech");var debt=account.debt;
        market.reconfigure(config.withSkin("redtech","Actor_One"));market.buy(player,"redtech",1);
        long cash=account.cash,cost=position.cost;assertEquals(3,position.quantity);
        snapshot.restore(market);
        assertSame(account,market.account(player));assertSame(position,account.holdings.get("redtech"));assertSame(debt,account.debt);
        assertEquals(cash,account.cash);assertEquals(cost,position.cost);assertEquals(3,position.quantity);
        assertSame(config,market.config);assertEquals(config.stocks().get("redtech").skinName(),market.quote("redtech").spec.skinName());
        assertEquals(45000,market.clockMillis);assertEquals(90000,market.nextNewsMillis);
    }
}
