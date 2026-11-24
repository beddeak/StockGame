package kr.hetagram.stock;

import org.bukkit.configuration.file.YamlConfiguration;
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

class LoanTopUpTest {
    @TempDir Path directory;
    private GameConfig config;
    private Market market;
    private Loans loans;
    private final UUID player=UUID.randomUUID();
    private static long money(String value) {return Market.cents(value);}

    @BeforeEach void setup() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml"))
            try(var in=getClass().getResourceAsStream("/"+name)){Files.copy(Objects.requireNonNull(in),directory.resolve(name));}
        var y=YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile());
        y.set("loans.enabled",true);y.set("loans.require-low-assets",false);y.set("loans.allow-top-up",true);
        y.set("loans.min-principal",10000);y.set("loans.max-principal",3000000);y.set("loans.default-principal",300000);
        y.set("loans.bankruptcy-threshold",50000);y.set("loans.term-seconds",300);y.set("loans.interest-interval-seconds",60);
        y.set("loans.interest-percent",5);y.set("loans.max-interest-percent",100);y.set("loans.upfront-fee-percent",0);
        y.save(directory.resolve("config.yml").toFile());config=GameConfig.load(directory.toFile());
        market=new Market(config);market.running=true;loans=new Loans(market);
    }

    @Test void normalStartingAssetsCanBorrowAndTopUpWithoutRestartingTheOriginalLoanClock() {
        assertEquals(money("1000000"),market.account(player).cash);
        loans.borrow(player,money("300000"));var account=market.account(player);var debt=account.debt;
        assertEquals(money("1300000"),account.cash);
        long due=debt.dueAt,borrowed=debt.borrowedAt,nextInterest=debt.nextInterestAt;
        market.clockMillis=30000;loans.borrow(player,money("300000"));
        assertSame(debt,account.debt);assertEquals(money("600000"),debt.principal);assertEquals(money("600000"),debt.originalPrincipal);
        assertEquals(money("1600000"),account.cash);assertEquals(due,debt.dueAt);assertEquals(borrowed,debt.borrowedAt);
        assertEquals(nextInterest,debt.nextInterestAt);assertFalse(debt.bankrupt);assertEquals(0,debt.collectorRestUntil);
    }

    @Test void perRequestAndOutstandingPrincipalLimitsRejectWithoutChangingTheLoan() {
        loans.borrow(player,money("3000000"));var account=market.account(player);var debt=account.debt;long cash=account.cash;
        market.clockMillis=30000;
        for(String amount:List.of("10000","3000001","9999","0"))
            assertThrows(IllegalArgumentException.class,()->loans.borrow(player,money(amount)));
        assertEquals(cash,account.cash);assertEquals(money("3000000"),debt.principal);assertEquals(0,debt.interest);
        assertEquals(0,debt.totalInterest);assertEquals(60000,debt.nextInterestAt);
    }

    @Test void repayingPrincipalFreesTheLimitWhileOriginalPrincipalTracksAllBorrowing() {
        loans.borrow(player,money("3000000"));var account=market.account(player);var debt=account.debt;
        loans.repay(player,money("1000000"));assertEquals(money("2000000"),debt.principal);
        loans.borrow(player,money("1000000"));
        assertEquals(money("3000000"),debt.principal);assertEquals(money("4000000"),debt.originalPrincipal);
        assertEquals(money("4000000"),account.cash);assertEquals(300000,debt.dueAt);
        assertThrows(IllegalArgumentException.class,()->loans.borrow(player,money("10000")));
        // 만기 전에는 이자가 쌓이기만 하고 현금을 가져가지 않습니다.
        market.clockMillis=60000;loans.advance(Set.of(player));
        assertEquals(money("150000"),debt.totalInterest);assertEquals(money("150000"),debt.interest);assertEquals(money("4000000"),account.cash);
    }

    @Test void alreadyDueInterestUsesOldPrincipalAndFutureInstallmentsUseTheToppedUpPrincipal() {
        loans.borrow(player,money("300000"));var account=market.account(player);var debt=account.debt;
        market.clockMillis=60000;loans.borrow(player,money("300000"));
        assertEquals(money("15000"),debt.totalInterest);assertEquals(money("15000"),debt.interest);
        assertEquals(money("600000"),debt.principal);assertEquals(money("1600000"),account.cash);
        assertEquals(120000,debt.nextInterestAt);assertEquals(300000,debt.dueAt);
        market.clockMillis=120000;loans.advance(Set.of(player));
        assertEquals(money("45000"),debt.totalInterest);assertEquals(money("1600000"),account.cash);
    }

    @Test void bankruptLoansCannotBeToppedUpAndBorrowingAtMaturitySettlesFirstInsteadOfExtendingIt() {
        loans.borrow(player,money("300000"));var account=market.account(player);var debt=account.debt;
        account.cash=0;market.clockMillis=300000;
        // 만기가 지난 대출은 추가 차입보다 회수가 먼저입니다. 갚지 못하면 파산이라 더 빌릴 수 없습니다.
        assertThrows(IllegalArgumentException.class,()->loans.borrow(player,money("10000")));
        assertTrue(debt.bankrupt);assertEquals(300000,debt.dueAt);assertSame(debt,account.debt);
        assertEquals(money("300000"),debt.principal);assertEquals(money("75000"),debt.interest);assertEquals(money("300000"),debt.originalPrincipal);assertEquals(0,account.cash);
        // 파산 중에는 추가 차입이 거부되고 계정이 바뀌지 않습니다.
        assertThrows(IllegalArgumentException.class,()->loans.borrow(player,money("10000")));
        assertEquals(0,account.cash);assertEquals(money("375000"),debt.balance());assertSame(debt,account.debt);
    }

    @Test void toppedUpLoansAndPartialRepaymentsKeepTheirScheduleAfterSaveLoad() throws Exception {
        loans.borrow(player,money("300000"));market.clockMillis=30000;loans.borrow(player,money("200000"));loans.repay(player,money("100000"));
        var debt=market.account(player).debt;var store=new StateStore(directory.toFile());store.save(market);
        var restored=new Market(config);store.load(restored);var loaded=restored.account(player).debt;
        assertEquals(money("400000"),loaded.principal);assertEquals(money("500000"),loaded.originalPrincipal);
        assertEquals(debt.dueAt,loaded.dueAt);assertEquals(debt.borrowedAt,loaded.borrowedAt);
        assertEquals(debt.nextInterestAt,loaded.nextInterestAt);
        restored.clockMillis=60000;new Loans(restored).advance(Set.of(player));
        assertEquals(money("20000"),loaded.totalInterest);assertEquals(money("1400000"),restored.account(player).cash);
    }

    private void assertUnsettled(Loans.Debt debt) {
        assertEquals(0,debt.totalInterest);assertEquals(60000,debt.nextInterestAt);
    }
    @Test void overflowAndInvalidAssetBudgetsNeverPartiallyApplyTopUps() {
        loans.borrow(player,money("300000"));var account=market.account(player);var debt=account.debt;
        market.clockMillis=30000;long amount=money("10000"),normalCash=account.cash,original=debt.originalPrincipal;
        account.cash=Long.MAX_VALUE-5;
        assertThrows(ArithmeticException.class,()->loans.borrow(player,amount));assertEquals(Long.MAX_VALUE-5,account.cash);assertUnsettled(debt);
        account.cash=normalCash;debt.originalPrincipal=Long.MAX_VALUE-amount+1;
        assertThrows(ArithmeticException.class,()->loans.borrow(player,amount));assertEquals(Long.MAX_VALUE-amount+1,debt.originalPrincipal);assertUnsettled(debt);
        debt.originalPrincipal=original;debt.interest=Long.MAX_VALUE-debt.principal;
        assertThrows(ArithmeticException.class,()->loans.borrow(player,amount));assertEquals(Long.MAX_VALUE-debt.principal,debt.interest);assertUnsettled(debt);
        debt.interest=0;long price=market.quote("redtech").price;account.holdings.put("redtech",new Market.Position(1,price));
        account.cash=Long.MAX_VALUE-price-amount+1;
        assertThrows(ArithmeticException.class,()->loans.borrow(player,amount));assertEquals(Long.MAX_VALUE-price-amount+1,account.cash);assertUnsettled(debt);
        account.cash=normalCash;account.holdings.clear();account.holdings.put("redtech",new Market.Position(1,Long.MAX_VALUE));account.holdings.put("cloud",new Market.Position(1,1));
        assertThrows(IllegalArgumentException.class,()->loans.borrow(player,amount));assertEquals(normalCash,account.cash);assertUnsettled(debt);
        assertEquals(money("300000"),debt.principal);assertEquals(original,debt.originalPrincipal);
    }
}
