package kr.hetagram.stock;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HighPriceSafetyTest {
    @TempDir Path directory;
    private GameConfig config;
    private Market market;
    private final UUID investor=UUID.randomUUID();

    @BeforeEach void setup() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml"))
            try(var in=getClass().getResourceAsStream("/"+name)) {
                Files.copy(Objects.requireNonNull(in),directory.resolve(name));
            }
        var yaml=YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile());
        yaml.set("game.max-price",1_000_000_000_000L);
        yaml.set("news.reversal-chance",0);
        yaml.save(directory.resolve("config.yml").toFile());
        config=GameConfig.load(directory.toFile());market=new Market(config);market.running=true;
    }

    private Market.Account millionShareHolder(UUID id,String stock) {
        var account=market.account(id);account.cash=Market.cents("1000000000000");
        market.setPrice(stock,Market.cents("101"));market.buy(id,stock,1_000_000);
        return account;
    }

    private void forceRise(String stock) { market.quote(stock).effect=new Market.Effect(1,1,Long.MAX_VALUE); }

    @Test void pricePassesOldOneBillionLimitAndReachesConfiguredOneTrillionLimit() {
        market.setPrice("redtech",Market.cents("1000000000"));forceRise("redtech");
        market.tick(new Random(1));
        assertTrue(market.quote("redtech").price>Market.cents("1000000000"));
        assertEquals(Market.cents("1000000000000"),config.rules().maxPrice());
        market.setPrice("redtech",config.rules().maxPrice());forceRise("redtech");market.tick(new Random(2));
        var quote=market.quote("redtech");
        assertEquals(config.rules().maxPrice(),quote.price);
        assertEquals(quote.price,quote.history.getLast().high());
    }

    @Test void millionSharesRemainSafeAcrossAutomaticTickNewsRankingAndFullSale() {
        var account=millionShareHolder(investor,"redtech");
        long limit=(Long.MAX_VALUE-account.cash)/1_000_000;
        market.setPrice("redtech",limit);forceRise("redtech");market.tick(new Random(3));
        var quote=market.quote("redtech");
        assertEquals(limit,quote.price);assertEquals(limit,quote.history.getLast().high());
        market.news("mill-up-04","redtech",new Random(4));
        assertEquals(limit,quote.price);assertEquals(limit,quote.history.getLast().high());
        long equity=Math.addExact(account.cash,market.value(account));
        assertTrue(equity>0);assertTrue(Long.MAX_VALUE-equity<1_000_000);
        assertEquals(equity,market.rankings("equity",1).getFirst().money());
        market.sell(investor,"redtech",-1);
        assertEquals(equity,account.cash);assertTrue(account.holdings.isEmpty());
    }

    @Test void safeLimitIncludesOtherCompaniesCashAndEveryShareholder() {
        var first=millionShareHolder(investor,"redtech");
        market.setPrice("cloud",Market.cents("101"));market.buy(investor,"cloud",1_000_000);
        market.setPrice("redtech",Market.cents("10000000000"));
        UUID other=UUID.randomUUID();var second=market.account(other);second.cash=5_000_000_000_000_000_000L;
        market.buy(other,"cloud",1_000_000);
        BigInteger max=BigInteger.valueOf(Long.MAX_VALUE),quantity=BigInteger.valueOf(1_000_000);
        long firstLimit=max.subtract(BigInteger.valueOf(first.cash)).subtract(BigInteger.valueOf(market.positionValue("redtech",first.holdings.get("redtech")))).divide(quantity).longValueExact();
        long secondLimit=max.subtract(BigInteger.valueOf(second.cash)).divide(quantity).longValueExact();
        assertTrue(secondLimit<firstLimit);
        market.setPrice("cloud",secondLimit);forceRise("cloud");market.tick(new Random(5));
        assertEquals(secondLimit,market.quote("cloud").price);
        assertDoesNotThrow(()->Math.addExact(first.cash,market.value(first)));
        assertDoesNotThrow(()->Math.addExact(second.cash,market.value(second)));
        assertEquals(2,market.rankings("equity",10).size());
    }

    @Test void explicitPriceAboveShareholderLimitIsRejectedBeforeAnyQuoteMutation() {
        var account=millionShareHolder(investor,"redtech");
        long limit=(Long.MAX_VALUE-account.cash)/1_000_000;
        var quote=market.quote("redtech");forceRise("redtech");
        long price=quote.price,previous=quote.previous;var effect=quote.effect;var history=List.copyOf(quote.history);

        var error=assertThrows(IllegalArgumentException.class,()->market.setPrice("redtech",limit+1));

        assertTrue(error.getMessage().contains("계정 자산 한도"));
        assertEquals(price,quote.price);assertEquals(previous,quote.previous);assertSame(effect,quote.effect);
        assertEquals(history,List.copyOf(quote.history));assertFalse(quote.delisted);
        assertEquals(1_000_000,account.holdings.get("redtech").quantity);
    }

    @Test void fullBuyUsesLargeIntermediateProductsAndHonorsTotalCostBudget() {
        market.setPrice("redtech",config.rules().maxPrice());
        var account=market.account(investor);account.cash=config.rules().maxPrice();
        assertEquals(1,market.maxBuy(account,market.quote("redtech")));
        assertThrows(IllegalArgumentException.class,()->market.buy(investor,"redtech",1_000_000));
        assertEquals(config.rules().maxPrice(),account.cash);assertTrue(account.holdings.isEmpty());
        account.holdings.put("cloud",new Market.Position(1,Long.MAX_VALUE-config.rules().maxPrice()+1));
        assertEquals(0,market.maxBuy(account,market.quote("redtech")));
        assertThrows(IllegalArgumentException.class,()->market.buy(investor,"redtech",1));
        assertEquals(config.rules().maxPrice(),account.cash);assertFalse(account.holdings.containsKey("redtech"));
        account.holdings.clear();
        assertEquals(1,market.buy(investor,"redtech",-1).quantity());
        assertEquals(0,account.cash);
    }

    @Test void invalidExistingEquityIsReportedWithoutDelistingAndLargeAverageIsExact() {
        var account=market.account(investor);account.cash=Long.MAX_VALUE;
        account.holdings.put("redtech",new Market.Position(1,10000));
        var quote=market.quote("redtech");long price=quote.price;var history=List.copyOf(quote.history);
        assertThrows(IllegalArgumentException.class,()->market.setPrice("redtech",quote.spec.delist()));
        assertEquals(price,quote.price);assertFalse(quote.delisted);assertEquals(history,List.copyOf(quote.history));
        long exact=Long.MAX_VALUE-5;
        assertEquals(exact,new Market.Position(1,exact).average());
    }
}
