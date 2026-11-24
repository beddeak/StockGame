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

class NetWorthAndDebtTransferTest {
    @TempDir Path directory;
    private GameConfig config;
    private Market market;
    private Loans loans;
    private final UUID borrower=UUID.randomUUID(),saver=UUID.randomUUID();
    private static long money(String value){return Market.cents(value);}
    @BeforeEach void setup() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml"))
            try(var input=getClass().getResourceAsStream("/"+name)){Files.copy(Objects.requireNonNull(input),directory.resolve(name));}
        config=GameConfig.load(directory.toFile());market=new Market(config);market.running=true;loans=new Loans(market);
        market.account(borrower).name="Borrower";market.account(saver).name="Saver";
    }

    @Test void borrowingCannotImproveNetRankAndStockGainsStillCountWhileGrossMetricsRemainAvailable() {
        var borrowing=loans.borrow(borrower,money("3000000"));var account=market.account(borrower);assertEquals(money("300000"),borrowing.fee());
        assertEquals(money("3700000"),market.rankValue(account,"equity"));assertEquals(money("700000"),market.rankValue(account,"net-equity"));
        assertTrue(market.rankValue(account,"net-equity")<market.rankValue(market.account(saver),"net-equity"),"선이자만큼 순자산은 오히려 줄어듭니다");
        market.setPrice("redtech",money("100000"));market.buy(borrower,"redtech",5);market.setPrice("redtech",money("200000"));
        assertEquals(money("4200000"),market.rankValue(account,"equity"));assertEquals(money("3200000"),market.rankValue(account,"cash"));
        assertEquals(money("1200000"),market.rankValue(account,"net-equity"));assertEquals(money("200000"),market.rankValue(account,"net-cash"));
        assertEquals(borrower,market.rankings("net-equity",10).getFirst().player());assertEquals(saver,market.rankings("net-cash",10).getFirst().player());
        account.cash=0;account.holdings.clear();assertEquals(money("-3000000"),market.rankValue(account,"net-equity"));
        assertEquals(saver,market.rankings("net-equity",10).getFirst().player());
    }

    @Test void negativeNetWorthHandlesTheLongBoundaryWithoutWrappingAndStillRejectsUnknownMetrics() {
        loans.borrow(borrower,money("300000"));var account=market.account(borrower);account.cash=0;account.debt.principal=Long.MAX_VALUE;
        assertEquals(-Long.MAX_VALUE,market.rankValue(account,"net-cash"));assertEquals(-Long.MAX_VALUE,market.rankValue(account,"net-equity"));
        assertThrows(IllegalArgumentException.class,()->market.rankValue(account,"typo"));assertThrows(IllegalArgumentException.class,()->market.rankings("typo",5));
    }

    @Test void anyOutstandingDebtBlocksOutgoingTransfersButNotIncomingHelpAndCanBeDisabledExplicitly() {
        loans.borrow(borrower,money("300000"));var account=market.account(borrower);long before=account.cash;
        UUID absent=UUID.randomUUID();assertThrows(IllegalArgumentException.class,()->market.transfer(borrower,absent,1));
        assertFalse(market.accounts.containsKey(absent));assertEquals(before,account.cash);
        market.transfer(saver,borrower,money("1000"));assertEquals(before+money("1000"),account.cash);
        config.ui().set("loans.block-transfers-while-indebted",false);assertDoesNotThrow(()->market.transfer(borrower,saver,money("1000")));
        account.debt.bankrupt=true;assertThrows(IllegalArgumentException.class,()->market.transfer(borrower,saver,1));account.debt.bankrupt=false;
        config.ui().set("loans.block-transfers-while-indebted",true);loans.repay(borrower,-1);assertNull(account.debt);
        assertDoesNotThrow(()->market.transfer(borrower,saver,money("1000")));
    }
}
