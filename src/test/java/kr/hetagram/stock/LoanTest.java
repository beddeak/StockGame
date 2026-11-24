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

/** 선이자 0%로 원금·이자 계산만 확인하는 기본 사채 규칙입니다. 선이자는 LoanSharkTest에서 검증합니다. */
class LoanTest {
    @TempDir Path directory;
    private GameConfig config;
    private Market market;
    private Loans loans;
    private final UUID player=UUID.randomUUID(),other=UUID.randomUUID();
    private static long money(String value) {return Market.cents(value);}

    @BeforeEach void setup() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml"))
            try(var in=getClass().getResourceAsStream("/"+name)) {
                Files.copy(Objects.requireNonNull(in),directory.resolve(name));
            }
        var yaml=YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile());
        yaml.set("loans.enabled",true);yaml.set("loans.bankruptcy-threshold",50000);
        yaml.set("loans.min-principal",10000);yaml.set("loans.max-principal",500000);yaml.set("loans.default-principal",300000);
        yaml.set("loans.term-seconds",300);yaml.set("loans.interest-interval-seconds",60);yaml.set("loans.interest-percent",5);
        yaml.set("loans.max-interest-percent",100);yaml.set("loans.upfront-fee-percent",0);yaml.set("loans.due-warning-seconds",30);
        yaml.set("loans.require-low-assets",true);yaml.set("loans.allow-top-up",false);
        yaml.save(directory.resolve("config.yml").toFile());
        config=GameConfig.load(directory.toFile());market=new Market(config);market.running=true;loans=new Loans(market);
        market.account(player).cash=0;market.account(other).cash=0;
    }

    private void borrow(UUID player) {loans.borrow(player,money("300000"));}
    private List<Loans.Event> at(long clock,Set<UUID> online) {market.clockMillis=clock;return loans.advance(online);}
    private static long count(List<Loans.Event> events,Loans.Kind kind) {return events.stream().filter(e->e.kind()==kind).count();}

    @Test void onlyLowAssetPlayersCanBorrowOneLoanWithinConfiguredBounds() {
        market.account(player).cash=money("50000");borrow(player);
        assertEquals(money("350000"),market.account(player).cash);
        assertEquals(money("300000"),loans.balance(player));
        var debt=market.account(player).debt;
        assertThrows(IllegalArgumentException.class,()->borrow(player));
        assertSame(debt,market.account(player).debt);assertEquals(money("350000"),market.account(player).cash);
        market.account(other).cash=money("50000.01");
        assertThrows(IllegalArgumentException.class,()->borrow(other));assertNull(market.account(other).debt);
        market.account(other).cash=0;
        assertThrows(IllegalArgumentException.class,()->loans.borrow(other,money("9999")));
        assertThrows(IllegalArgumentException.class,()->loans.borrow(other,money("500001")));
        market.account(other).holdings.put("redtech",new Market.Position(1,money("100000")));
        assertThrows(IllegalArgumentException.class,()->borrow(other));assertNull(market.account(other).debt);
        market.account(other).holdings.clear();
        config.ui().set("loans.enabled",false);
        assertThrows(IllegalArgumentException.class,()->borrow(other));assertEquals(0,market.account(other).cash);
    }

    @Test void interestOnlyAccumulatesBeforeMaturityThenEverythingIsCollectedOnceAndTheShortfallBankruptsImmediately() {
        borrow(player);var account=market.account(player);
        at(59999,Set.of(player));assertEquals(0,account.debt.interest);
        // 만기 전에는 이자가 쌓이기만 하고 현금을 건드리지 않습니다.
        assertTrue(at(60000,Set.of(player)).isEmpty());assertEquals(money("15000"),account.debt.interest);assertEquals(money("300000"),account.cash);
        at(240000,Set.of(player));assertEquals(money("60000"),account.debt.interest);assertEquals(money("300000"),account.cash);
        var maturity=at(300000,Set.of(player));var debt=account.debt;
        // 5회 이자(25%)까지 원리금 375,000원 중 현금 300,000원을 회수하고 남은 75,000원으로 즉시 파산합니다.
        assertEquals(1,count(maturity,Loans.Kind.COLLECTED));assertEquals(1,count(maturity,Loans.Kind.BANKRUPT));
        assertEquals(0,account.cash);assertTrue(debt.bankrupt);assertEquals(300000,debt.bankruptAt);
        assertEquals(money("75000"),debt.principal);assertEquals(0,debt.interest);assertEquals(money("75000"),debt.totalInterest);
        assertTrue(at(300000,Set.of(player)).isEmpty(),"같은 시각의 반복 호출은 아무것도 다시 회수하지 않습니다");
        // 파산 뒤에도 남은 원금에 이자가 계속 붙습니다.
        at(360000,Set.of(player));assertEquals(money("3750"),debt.interest);assertEquals(money("78750"),debt.balance());
    }

    @Test void partialPaymentsSettleInterestFirstAndFutureInterestUsesRemainingPrincipal() {
        borrow(player);at(60000,Set.of(player));
        assertEquals(money("45000"),loans.repay(player,money("45000")));
        var account=market.account(player);var debt=account.debt;
        assertEquals(0,debt.interest);assertEquals(money("270000"),debt.principal);
        at(120000,Set.of(player));assertEquals(money("13500"),debt.interest);
        assertEquals(money("28500"),debt.totalInterest);
        assertEquals(money("255000"),loans.repay(player,-1));
        assertEquals(money("28500"),debt.principal);assertEquals(0,debt.interest);assertEquals(0,account.cash);
        assertEquals(Loans.RepayStatus.INSUFFICIENT_CASH,loans.repayResult(player,money("1")).status());
        assertEquals(money("28500"),debt.principal);assertEquals(0,account.cash);
        account.cash=money("28500");loans.repay(player,-1);
        assertNull(account.debt);assertEquals(0,loans.balance(player));
        assertTrue(loans.advance(Set.of(player)).stream().anyMatch(e->e.kind()==Loans.Kind.REPAID));
    }

    @Test void dueWarningFiresOnceThirtySecondsBeforeMaturityAndSurvivesRestart() throws Exception {
        borrow(player);
        assertEquals(0,count(at(269999,Set.of(player)),Loans.Kind.DUE_SOON));
        var warning=at(270000,Set.of(player));assertEquals(1,count(warning,Loans.Kind.DUE_SOON));
        assertEquals(money("300000")+money("60000"),warning.getFirst().amount());
        assertEquals(0,count(at(280000,Set.of(player)),Loans.Kind.DUE_SOON));
        var store=new StateStore(directory.toFile());store.save(market);var restored=new Market(config);store.load(restored);
        assertTrue(restored.account(player).debt.dueWarned);
        assertEquals(0,count(new Loans(restored).advance(Set.of(player)),Loans.Kind.DUE_SOON));
    }

    @Test void bankruptPlayersCannotBuyBorrowOrSendButAnyIncomeRepaysAndClearsTheBankruptcy() {
        borrow(player);market.account(player).cash=0;at(300000,Set.of(player));var account=market.account(player);
        assertTrue(account.debt.bankrupt);
        market.setPrice("redtech",money("1000"));
        assertThrows(IllegalArgumentException.class,()->market.buy(player,"redtech",1));
        assertThrows(IllegalArgumentException.class,()->borrow(player));
        account.cash=money("1000");
        assertThrows(IllegalArgumentException.class,()->market.transfer(player,other,money("1000")));
        // 판매·입금 수입처럼 현금이 생기면 바로 빚에 들어갑니다.
        loans.reconcile(player);assertEquals(0,account.cash);assertEquals(money("374000"),account.debt.balance());
        account.cash=account.debt.balance();loans.reconcile(player);
        assertNull(account.debt);assertEquals(0,account.cash);
        account.cash=money("1000");assertDoesNotThrow(()->market.buy(player,"redtech",1));
    }

    @Test void lifetimeInterestCapIsNotRestoredByRepaymentAndAccountsAndResetStayIndependent() {
        config.ui().set("loans.interest-percent",100);borrow(player);borrow(other);
        at(60000,Set.of(player));var first=market.account(player).debt;var second=market.account(other).debt;
        assertEquals(money("300000"),first.interest);assertEquals(money("300000"),second.interest);
        loans.repay(player,money("300000"));assertEquals(0,first.interest);assertEquals(money("300000"),first.principal);
        at(120000,Set.of(player));assertEquals(0,first.interest);assertEquals(money("300000"),first.totalInterest);
        assertEquals(money("300000"),second.interest);
        market.account(player).cash=money("300000");loans.repay(player,-1); // REPAID 이벤트도 초기화 때 폐기합니다.
        market.reset();assertTrue(market.accounts.isEmpty());assertEquals(0,loans.balance(player));assertEquals(0,loans.balance(other));
        assertNull(market.account(player).debt);
        assertTrue(loans.advance(Set.of(player,other)).isEmpty());
    }

    @Test void loanStateSurvivesRestartWithoutRewritingDuringLoad() throws Exception {
        borrow(player);at(60000,Set.of(player));loans.repay(player,money("30000"));at(300000,Set.of());
        var before=market.account(player).debt;assertTrue(before.bankrupt);before.collectorRestUntil=420000;
        var store=new StateStore(directory.toFile());store.save(market);
        byte[] bytes=Files.readAllBytes(directory.resolve("state.yml"));
        var restored=new Market(config);store.load(restored);var debt=restored.account(player).debt;
        assertEquals(before.originalPrincipal,debt.originalPrincipal);assertEquals(before.principal,debt.principal);
        assertEquals(before.interest,debt.interest);assertEquals(before.totalInterest,debt.totalInterest);
        assertEquals(before.borrowedAt,debt.borrowedAt);assertEquals(before.dueAt,debt.dueAt);assertEquals(before.nextInterestAt,debt.nextInterestAt);
        assertEquals(before.bankrupt,debt.bankrupt);assertEquals(before.bankruptAt,debt.bankruptAt);assertEquals(420000,debt.collectorRestUntil);
        assertEquals(before.dueWarned,debt.dueWarned);assertEquals(before.contract,debt.contract);
        assertArrayEquals(bytes,Files.readAllBytes(directory.resolve("state.yml")));
    }

    @Test void legacyAccountsHaveNoDebtAndInvalidLoanDataDoesNotOverwriteTheSave() throws Exception {
        var store=new StateStore(directory.toFile());store.save(market);var legacy=new Market(config);store.load(legacy);
        assertNull(legacy.account(player).debt);borrow(player);store.save(market);
        var yaml=YamlConfiguration.loadConfiguration(directory.resolve("state.yml").toFile());
        yaml.set("accounts."+player+".loan.principal",money("300001"));yaml.save(directory.resolve("state.yml").toFile());
        byte[] invalid=Files.readAllBytes(directory.resolve("state.yml"));
        assertThrows(IllegalArgumentException.class,()->store.load(new Market(config)));
        assertArrayEquals(invalid,Files.readAllBytes(directory.resolve("state.yml")));
        yaml.set("accounts."+player+".loan.principal",money("300000"));yaml.set("accounts."+player+".loan.bankrupt",true);
        yaml.save(directory.resolve("state.yml").toFile());
        assertThrows(IllegalArgumentException.class,()->store.load(new Market(config)),"만기 전 파산 저장은 모순이라 거부합니다");
    }
}
