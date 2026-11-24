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

/** 배포 기본값(선이자 10%, 5분 만기, 1분 5%)으로 만기 담보 회수와 파산 정산을 검증합니다. */
class CollateralLoanTest {
    @TempDir Path directory;
    private GameConfig config;
    private Market market;
    private Loans loans;
    private final UUID player=UUID.randomUUID();
    private static long money(String value) {return Market.cents(value);}

    @BeforeEach void setup() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml"))
            try(var input=getClass().getResourceAsStream("/"+name)) {Files.copy(Objects.requireNonNull(input),directory.resolve(name));}
        configureFee(0);
    }
    private void configureFee(double percent) throws Exception {
        var yaml=YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile());
        yaml.set("game.sell-fee-percent",percent);yaml.save(directory.resolve("config.yml").toFile());
        config=GameConfig.load(directory.toFile());market=new Market(config);market.running=true;loans=new Loans(market);
    }
    private List<Loans.Event> at(long clock) {market.clockMillis=clock;return loans.advance(Set.of());}
    private void borrow() {loans.borrow(player,money("300000"));}
    private static long sum(List<Loans.Event> events,Loans.Kind kind) {return events.stream().filter(e->e.kind()==kind).mapToLong(Loans.Event::amount).sum();}

    @Test void shippedRulesAreDangerousEnoughThatHoldingTheMaximumLoanWithoutProfitBankrupts() {
        var rules=config.loanRules();assertEquals(300,rules.termSeconds());assertEquals(60,rules.interestIntervalSeconds());
        assertEquals(5,rules.interestPercent());assertEquals(10,rules.upfrontFeePercent());assertEquals(100,rules.maxInterestPercent());
        assertEquals(money("3000000"),rules.maxPrincipal());
        loans.borrow(player,rules.maxPrincipal());var account=market.account(player);
        // 300만 원을 빌리면 선이자 30만 원을 떼고 270만 원만 받습니다.
        assertEquals(money("3700000"),account.cash);assertEquals(money("3000000"),account.debt.principal);assertEquals(money("300000"),account.debt.totalInterest);
        var events=at(300000);
        // 만기 원리금 375만 원 > 가진 현금 370만 원: 아무 수익 없이 버티면 5만 원이 모자라 파산합니다.
        assertTrue(account.debt.bankrupt);assertEquals(money("50000"),account.debt.balance());assertEquals(0,account.cash);
        assertEquals(money("3700000"),sum(events,Loans.Kind.COLLECTED));assertEquals(1,events.stream().filter(e->e.kind()==Loans.Kind.BANKRUPT).count());
    }

    @Test void noSharesAreSoldBeforeMaturityAndMaturitySellsOnlyTheSharesItNeeds() {
        borrow();market.setPrice("redtech",money("100000"));market.buy(player,"redtech",10);var account=market.account(player);account.cash=0;
        var early=at(240000);
        assertEquals(10,account.holdings.get("redtech").quantity);assertEquals(0,sum(early,Loans.Kind.COLLATERAL_SOLD));assertEquals(money("60000"),account.debt.interest);
        var events=at(300000);
        assertNull(account.debt);assertEquals(6,account.holdings.get("redtech").quantity);
        assertEquals(money("600000"),account.holdings.get("redtech").cost);assertEquals(money("25000"),account.cash);assertEquals(0,account.realized);
        assertEquals(money("400000"),sum(events,Loans.Kind.COLLATERAL_SOLD));
        assertFalse(events.stream().anyMatch(e->e.kind()==Loans.Kind.BANKRUPT));
    }

    @Test void maturityShortfallSellsEverythingWritesOffDelistedSharesAndBankruptsImmediately() throws Exception {
        borrow();market.setPrice("redtech",money("100000"));market.setPrice("cloud",money("1000"));
        market.buy(player,"redtech",1);market.buy(player,"cloud",1);market.setPrice("cloud",money("100"));
        var account=market.account(player);account.cash=0;var events=at(300000);var debt=account.debt;
        assertTrue(debt.bankrupt);assertEquals(300000,debt.bankruptAt);assertTrue(account.holdings.isEmpty());
        assertEquals(money("275000"),debt.balance());assertEquals(money("-1000"),account.realized);
        assertEquals(money("100000"),sum(events,Loans.Kind.COLLATERAL_SOLD));assertEquals(money("1000"),sum(events,Loans.Kind.COLLATERAL_FORFEITED));
        var store=new StateStore(directory.toFile());store.save(market);var restored=new Market(config);store.load(restored);
        assertTrue(restored.account(player).holdings.isEmpty());assertTrue(restored.account(player).debt.bankrupt);
        assertEquals(300000,restored.account(player).debt.bankruptAt);assertEquals(money("275000"),restored.account(player).debt.balance());
        // 파산 뒤에도 이자가 붙지만 선이자 포함 총이자는 빌린 원금의 100%에서 멈춥니다.
        at(100000000);assertEquals(money("300000"),debt.totalInterest);assertEquals(money("275000")+money("195000"),debt.balance());
    }

    @Test void bankruptSettlementOnlySellsWhatItNeedsWhenTheAssetsCoverTheDebt() {
        UUID wealthy=UUID.randomUUID();borrow();loans.borrow(wealthy,money("300000"));market.setPrice("redtech",money("100000"));
        market.buy(player,"redtech",1);market.buy(wealthy,"redtech",10);
        var poor=market.account(player);var rich=market.account(wealthy);poor.cash=rich.cash=0;
        poor.debt.bankrupt=rich.debt.bankrupt=true;poor.debt.bankruptAt=rich.debt.bankruptAt=123;
        loans.advance(Set.of());
        assertTrue(poor.holdings.isEmpty());assertTrue(poor.debt.bankrupt);assertEquals(money("200000"),poor.debt.balance());assertEquals(123,poor.debt.bankruptAt);
        assertNull(rich.debt);assertEquals(7,rich.holdings.get("redtech").quantity);assertEquals(0,rich.cash);
    }

    @Test void collateralSettlementsIgnoreMarketClosureAndRejectMultiStockOverflowWithoutPartialSales() {
        borrow();market.setPrice("redtech",money("1000"));market.setPrice("cloud",money("1000"));var account=market.account(player);
        market.buy(player,"redtech",1);market.buy(player,"cloud",1);account.cash=0;market.running=false;
        assertThrows(IllegalArgumentException.class,()->market.sell(player,"redtech",1));
        assertEquals(money("1000"),market.liquidateCollateral(account,money("500")));assertFalse(account.holdings.containsKey("redtech"));
        account.cash=0;account.holdings.clear();account.holdings.put("redtech",new Market.Position(1,money("1000")));
        account.holdings.put("cloud",new Market.Position(1,0));account.realized=Long.MAX_VALUE;
        assertThrows(ArithmeticException.class,()->market.liquidateCollateral(account,money("2000")));
        assertEquals(0,account.cash);assertEquals(1,account.holdings.get("redtech").quantity);assertEquals(1,account.holdings.get("cloud").quantity);
        assertEquals(Long.MAX_VALUE,account.realized);
        account.realized=Long.MIN_VALUE+money("500");account.holdings.get("cloud").cost=money("1000");
        assertThrows(ArithmeticException.class,()->market.forfeitCollateral(account));assertEquals(2,account.holdings.size());assertEquals(0,account.cash);
        assertEquals(Long.MIN_VALUE+money("500"),account.realized);
    }

    @Test void bankruptIncomeIsRepaidImmediatelyWithoutTouchingActiveLoansEarly() {
        UUID second=UUID.randomUUID();borrow();loans.borrow(second,money("300000"));var account=market.account(player);
        long initial=account.cash;account.cash+=money("10000");loans.reconcile(player);
        assertEquals(initial+money("10000"),account.cash,"만기 전 정상 대출은 들어온 돈을 미리 가져가지 않습니다");
        assertEquals(money("300000"),account.debt.principal);
        account.cash=market.account(second).cash=0;at(300000);assertTrue(account.debt.bankrupt);
        account.cash=money("100000");loans.reconcile(player);assertEquals(0,account.cash);assertEquals(money("275000"),account.debt.balance());
        account.cash=account.debt.balance();loans.reconcile(player);assertNull(account.debt);
        var bankrupt=market.account(second);assertTrue(bankrupt.debt.bankrupt);
        bankrupt.cash=bankrupt.debt.balance();loans.reconcile(second);assertNull(bankrupt.debt);assertEquals(0,bankrupt.cash);
    }

    @Test void minimumSharesUsePerSaleFeeRoundingEvenWhenPricesAreOnlyAFewCents() throws Exception {
        configureFee(20);var stocks=YamlConfiguration.loadConfiguration(directory.resolve("stocks.yml").toFile());
        for(String id:List.of("redtech","cloud")){stocks.set("stocks."+id+".start-price",.03);stocks.set("stocks."+id+".delist-price",.01);}
        stocks.save(directory.resolve("stocks.yml").toFile());market=new Market(GameConfig.load(directory.toFile()));
        var account=market.account(player);account.cash=0;account.holdings.put("redtech",new Market.Position(3,9));
        assertEquals(5,market.liquidateCollateral(account,3));assertEquals(1,account.holdings.get("redtech").quantity);
        assertEquals(3,account.holdings.get("redtech").cost);assertEquals(-1,account.realized);
        var other=market.account(UUID.randomUUID());other.cash=0;
        other.holdings.put("redtech",new Market.Position(1,3));other.holdings.put("cloud",new Market.Position(1,3));
        assertEquals(4,market.liquidateCollateral(other,3));assertTrue(other.holdings.isEmpty());assertEquals(-2,other.realized);
    }
}
