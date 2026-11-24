package kr.hetagram.stock;

import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ResourceSalesTest {
    @TempDir Path directory;
    private Market market;
    private final UUID player=UUID.randomUUID();
    private static long money(String value) {return Market.cents(value);}

    @BeforeEach void setup() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml"))
            try(var input=getClass().getResourceAsStream("/"+name)) {
                Files.copy(Objects.requireNonNull(input),directory.resolve(name));
            }
        market=new Market(GameConfig.load(directory.toFile()));market.running=true;
    }

    @Test void quoteUsesExactCentsAndCountsOnlyCatalogItemsWithoutChangingTheInputs() {
        var items=Map.of(Material.COD,3,Material.RAW_IRON,2,Material.DIRT,64);
        var prices=Map.of(Material.COD,money("12.34"),Material.RAW_IRON,money("0.01"));
        var quote=ResourceSales.quote(items,prices);
        assertEquals(5,quote.items());assertEquals(money("37.04"),quote.amount());
        assertEquals(64,items.get(Material.DIRT));assertEquals(3,items.get(Material.COD));
        assertEquals(money("12.34"),prices.get(Material.COD));
        assertEquals(new ResourceSales.Quote(0,0),ResourceSales.quote(Map.of(Material.DIRT,64),prices));
        assertEquals(new ResourceSales.Quote(0,0),ResourceSales.quote(Map.of(Material.COD,0),prices));
    }

    @Test void itemCountAndValueCanExceedTheIntegerRangeWithoutWrapping() {
        var prices=Map.of(Material.COD,1234L,Material.RAW_IRON,1L);
        var quote=ResourceSales.quote(Map.of(Material.COD,Integer.MAX_VALUE,Material.RAW_IRON,Integer.MAX_VALUE),prices);
        assertEquals(2L*Integer.MAX_VALUE,quote.items());
        assertEquals(1235L*Integer.MAX_VALUE,quote.amount());
        assertEquals(Long.MAX_VALUE,ResourceSales.quote(Map.of(Material.COD,1),Map.of(Material.COD,Long.MAX_VALUE)).amount());
    }

    @Test void invalidQuantitiesPricesAndBothFormsOfLongOverflowAreRejected() {
        assertThrows(IllegalArgumentException.class,()->ResourceSales.quote(Map.of(Material.COD,-1),Map.of(Material.COD,100L)));
        assertThrows(IllegalArgumentException.class,()->ResourceSales.quote(Map.of(Material.DIRT,-1),Map.of(Material.COD,100L)));
        for(long invalid:List.of(0L,-1L))
            assertThrows(IllegalArgumentException.class,()->ResourceSales.quote(Map.of(Material.COD,1),Map.of(Material.COD,invalid)));
        assertThrows(ArithmeticException.class,()->ResourceSales.quote(Map.of(Material.COD,2),Map.of(Material.COD,Long.MAX_VALUE)));
        assertThrows(ArithmeticException.class,()->ResourceSales.quote(Map.of(Material.COD,1,Material.RAW_IRON,1),Map.of(Material.COD,Long.MAX_VALUE,Material.RAW_IRON,1L)));
    }

    @Test void creditPreflightRejectsCashAndTotalAssetOverflowBeforeChangingTheAccount() {
        var account=market.account(player);account.cash=Long.MAX_VALUE-1;
        assertEquals(Long.MAX_VALUE,ResourceSales.cashAfter(market,account,1));assertEquals(Long.MAX_VALUE-1,account.cash);
        assertThrows(ArithmeticException.class,()->ResourceSales.cashAfter(market,account,2));assertEquals(Long.MAX_VALUE-1,account.cash);
        for(long invalid:List.of(0L,-1L))assertThrows(IllegalArgumentException.class,()->ResourceSales.cashAfter(market,account,invalid));
        market.setPrice("redtech",money("1000"));
        account.holdings.put("redtech",new Market.Position(1,money("1000")));account.cash=Long.MAX_VALUE-money("1000");
        assertThrows(ArithmeticException.class,()->ResourceSales.cashAfter(market,account,1));
        assertEquals(Long.MAX_VALUE-money("1000"),account.cash);assertEquals(1,account.holdings.get("redtech").quantity);
        account.cash--;assertEquals(Long.MAX_VALUE-money("1000"),ResourceSales.cashAfter(market,account,1));
    }

    @Test void bankruptPlayersCanEarnOrdinaryResourceIncomeWithoutChangingTheDebtOrRealizedTradingProfit() {
        var loans=new Loans(market);loans.borrow(player,money("300000"));var account=market.account(player);var debt=account.debt;
        debt.bankrupt=true;account.cash=0;account.realized=money("1234.56");long balance=debt.balance();
        var quote=ResourceSales.quote(Map.of(Material.COD,10),Map.of(Material.COD,money("1000")));
        account.cash=ResourceSales.cashAfter(market,account,quote.amount());
        assertEquals(money("10000"),account.cash);assertSame(debt,account.debt);assertEquals(balance,debt.balance());
        assertTrue(debt.bankrupt);assertEquals(money("1234.56"),account.realized);
        assertThrows(IllegalArgumentException.class,()->market.buy(player,"redtech",1));
        loans.reconcile(player);assertEquals(0,account.cash);
        assertEquals(balance-money("10000"),debt.balance());
    }
}
