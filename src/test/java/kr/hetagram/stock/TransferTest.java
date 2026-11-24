package kr.hetagram.stock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TransferTest {
    @TempDir Path directory;
    private GameConfig config;
    private Market market;
    private final UUID sender=UUID.randomUUID(),recipient=UUID.randomUUID();
    private static long money(String value) {return Market.cents(value);}

    @BeforeEach void setup() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml"))
            try(var in=getClass().getResourceAsStream("/"+name)){Files.copy(Objects.requireNonNull(in),directory.resolve(name));}
        config=GameConfig.load(directory.toFile());market=new Market(config);market.running=true;
        market.account(sender).cash=money("100000");market.account(recipient).cash=money("5000");
    }

    @Test void transferPreservesCombinedCashAndDoesNotChangeHoldingsOrRealizedProfit() {
        market.setPrice("redtech",money("1000"));market.buy(sender,"redtech",2);market.buy(recipient,"redtech",1);
        var from=market.account(sender);var to=market.account(recipient);
        long cash=from.cash+to.cash,equity=cash+market.value(from)+market.value(to);
        market.transfer(sender,recipient,money("12345.67"));
        assertEquals(money("85654.33"),from.cash);assertEquals(money("16345.67"),to.cash);
        assertEquals(cash,from.cash+to.cash);assertEquals(equity,from.cash+to.cash+market.value(from)+market.value(to));
        assertEquals(2,from.holdings.get("redtech").quantity);assertEquals(1,to.holdings.get("redtech").quantity);
        assertEquals(0,from.realized);assertEquals(0,to.realized);
    }

    @Test void invalidTransfersAreRejectedWithoutChangingEitherAccountOrCreatingFailedRecipients() {
        long from=market.account(sender).cash,to=market.account(recipient).cash;
        assertThrows(IllegalArgumentException.class,()->market.transfer(sender,recipient,0));
        assertThrows(IllegalArgumentException.class,()->market.transfer(sender,recipient,-1));
        assertThrows(IllegalArgumentException.class,()->market.transfer(sender,sender,1));
        UUID absent=UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,()->market.transfer(sender,absent,from+1));
        assertFalse(market.accounts.containsKey(absent));assertEquals(from,market.account(sender).cash);assertEquals(to,market.account(recipient).cash);
    }

    @Test void recipientCashAndTotalEquityOverflowAreBothRejectedAtomically() {
        var from=market.account(sender);var to=market.account(recipient);long before=from.cash;
        to.cash=Long.MAX_VALUE-5;
        assertThrows(ArithmeticException.class,()->market.transfer(sender,recipient,10));
        assertEquals(before,from.cash);assertEquals(Long.MAX_VALUE-5,to.cash);
        market.setPrice("redtech",money("1000"));
        to.holdings.put("redtech",new Market.Position(1,money("1000")));to.cash=Long.MAX_VALUE-money("1000");
        assertThrows(ArithmeticException.class,()->market.transfer(sender,recipient,1));
        assertEquals(before,from.cash);assertEquals(Long.MAX_VALUE-money("1000"),to.cash);
    }

    @Test void bankruptPlayersCannotSendButCanReceiveHelp() {
        config.ui().set("loans.enabled",true);
        market.account(sender).cash=0;var loans=new Loans(market);loans.borrow(sender,money("300000"));
        assertThrows(IllegalArgumentException.class,()->market.transfer(sender,recipient,money("1000")));
        config.ui().set("loans.block-transfers-while-indebted",false);
        assertDoesNotThrow(()->market.transfer(sender,recipient,money("1000")));
        market.account(sender).cash=0;market.clockMillis=300000;loans.advance(Set.of());
        var debt=market.account(sender).debt;assertTrue(debt.bankrupt);
        market.transfer(recipient,sender,money("1000"));assertEquals(money("1000"),market.account(sender).cash);
        assertThrows(IllegalArgumentException.class,()->market.transfer(sender,recipient,1));
        assertEquals(money("1000"),market.account(sender).cash);
    }
}
