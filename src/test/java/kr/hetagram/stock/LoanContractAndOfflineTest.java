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

class LoanContractAndOfflineTest {
    @TempDir Path directory;
    private GameConfig config;
    private Market market;
    private Loans loans;
    private final UUID player=UUID.randomUUID();
    private static long money(String value) {return Market.cents(value);}
    @BeforeEach void setup() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml"))
            try(var input=getClass().getResourceAsStream("/"+name)){Files.copy(Objects.requireNonNull(input),directory.resolve(name));}
        config=GameConfig.load(directory.toFile());market=new Market(config);market.running=true;loans=new Loans(market);
    }
    private List<Loans.Event> at(long clock,Set<UUID> online){market.clockMillis=clock;return loans.advance(online);}
    private GameConfig changedRules() throws Exception {
        return config.withLoanSetting("term-seconds","30").withLoanSetting("interest-interval-seconds","10")
                .withLoanSetting("interest-percent","50").withLoanSetting("upfront-fee-percent","20").withLoanSetting("max-interest-percent","60")
                .withLoanSetting("allow-top-up","false").withLoanSetting("default-principal","10000").withLoanSetting("max-principal","100000");
    }

    @Test void reloadAndTopUpKeepTheOriginalFeeRateIntervalAndLimitsWhileNewBorrowersUseTheNewTerms() throws Exception {
        loans.borrow(player,money("300000"));var debt=market.account(player).debt;var original=debt.contract.rules();
        market.reconfigure(changedRules());market.clockMillis=60000;var topUp=loans.borrow(player,money("300000"));
        assertSame(debt,market.account(player).debt);assertEquals(original,loans.rulesFor(player));
        assertEquals(money("30000"),topUp.fee(),"추가 차입도 원래 계약의 선이자 10%를 뗍니다");
        assertEquals(money("600000"),debt.principal);assertEquals(money("30000")+money("15000")+money("30000"),debt.totalInterest);
        assertEquals(300000,debt.dueAt);assertEquals(120000,debt.nextInterestAt);
        at(120000,Set.of(player));assertEquals(money("105000"),debt.totalInterest);assertEquals(180000,debt.nextInterestAt);
        UUID newcomer=UUID.randomUUID();assertThrows(IllegalArgumentException.class,()->loans.borrow(newcomer,money("300000")));
        var fresh=loans.borrow(newcomer,money("10000"));var next=market.account(newcomer).debt;
        assertEquals(money("2000"),fresh.fee());assertEquals(money("8000"),fresh.received());
        assertEquals(150000,next.dueAt);assertEquals(130000,next.nextInterestAt);assertEquals(50,next.contract.rules().interestPercent());
    }

    @Test void savedContractsSurviveDifferentGlobalSettingsAndLegacyContractsSnapshotOnlyOnce() throws Exception {
        loans.borrow(player,money("300000"));var original=market.account(player).debt.contract;var store=new StateStore(directory.toFile());store.save(market);
        var changed=changedRules();var restored=new Market(changed);store.load(restored);assertEquals(original,restored.account(player).debt.contract);
        restored.clockMillis=60000;new Loans(restored).advance(Set.of(player));assertEquals(money("45000"),restored.account(player).debt.totalInterest);
        var yaml=YamlConfiguration.loadConfiguration(directory.resolve("state.yml").toFile());yaml.set("accounts."+player+".loan.contract",null);yaml.save(directory.resolve("state.yml").toFile());
        byte[] legacy=Files.readAllBytes(directory.resolve("state.yml"));var migrated=new Market(changed);store.load(migrated);
        assertEquals(changed.loanRules(),migrated.account(player).debt.contract.rules());assertArrayEquals(legacy,Files.readAllBytes(directory.resolve("state.yml")));
        store.save(migrated);var again=new Market(config);store.load(again);assertEquals(changed.loanRules(),again.account(player).debt.contract.rules());
        yaml=YamlConfiguration.loadConfiguration(directory.resolve("state.yml").toFile());yaml.set("accounts."+player+".loan.contract.interest-percent","bad");yaml.save(directory.resolve("state.yml").toFile());
        byte[] bad=Files.readAllBytes(directory.resolve("state.yml"));assertThrows(IllegalArgumentException.class,()->store.load(new Market(config)));
        assertArrayEquals(bad,Files.readAllBytes(directory.resolve("state.yml")));
    }

    @Test void offlineMaturityIsSummarisedOnceAndDrainedExactlyOnce() throws Exception {
        loans.borrow(player,money("300000"));at(270000,Set.of());at(300000,Set.of());
        assertNull(market.account(player).debt);var summary=market.account(player).offlineLoanSummary;
        assertEquals(1,summary.get(Loans.Kind.DUE_SOON).count());
        assertEquals(money("375000"),summary.get(Loans.Kind.COLLECTED).amount());assertEquals(1,summary.get(Loans.Kind.REPAID).count());
        assertEquals(300000,summary.get(Loans.Kind.COLLECTED).lastAt());
        assertTrue(at(300000,Set.of()).isEmpty());var store=new StateStore(directory.toFile());store.save(market);
        var restored=new Market(config);store.load(restored);var resumed=new Loans(restored);var drained=resumed.drainOfflineSummary(player);
        assertEquals(summary,drained);assertNull(restored.account(player).debt);assertTrue(resumed.drainOfflineSummary(player).isEmpty());
        store.save(restored);var afterDrain=new Market(config);store.load(afterDrain);assertTrue(afterDrain.account(player).offlineLoanSummary.isEmpty());
        market.reset();assertTrue(loans.drainOfflineSummary(player).isEmpty());
    }

    @Test void offlineBankruptcyKeepsCollateralTotalsAndOldSummaryKindsAreSkippedOnLoad() throws Exception {
        loans.borrow(player,money("300000"));market.setPrice("redtech",money("100000"));market.buy(player,"redtech",1);market.account(player).cash=0;
        at(300000,Set.of());var summary=market.account(player).offlineLoanSummary;
        assertEquals(money("100000"),summary.get(Loans.Kind.COLLATERAL_SOLD).amount());
        assertEquals(money("100000"),summary.get(Loans.Kind.COLLECTED).amount());
        assertEquals(1,summary.get(Loans.Kind.BANKRUPT).count());assertEquals(money("275000"),summary.get(Loans.Kind.BANKRUPT).amount());
        var before=java.util.Map.copyOf(summary);assertTrue(at(300000,Set.of()).isEmpty());assertEquals(before,summary);
        var store=new StateStore(directory.toFile());store.save(market);
        var yaml=YamlConfiguration.loadConfiguration(directory.resolve("state.yml").toFile());
        for(String old:List.of("missed","overdue","penalty-ended","interest-unpaid")){String key="accounts."+player+".offline-loans."+old;yaml.set(key+".count",2);yaml.set(key+".amount",5);yaml.set(key+".last-at",1);yaml.set(key+".misses",3);}
        yaml.save(directory.resolve("state.yml").toFile());
        var restored=new Market(config);store.load(restored);assertEquals(before,restored.account(player).offlineLoanSummary);
    }

    @Test void repaymentReturnsClearStatusesForAutomaticRepaymentNoDebtAndInsufficientCash() {
        assertEquals(Loans.RepayStatus.NO_DEBT,loans.repayResult(player,-1).status());assertFalse(market.accounts.containsKey(player));
        loans.borrow(player,money("300000"));market.account(player).cash=0;
        var insufficient=loans.repayResult(player,-1);assertEquals(Loans.RepayStatus.INSUFFICIENT_CASH,insufficient.status());assertEquals(0,insufficient.amount());
        assertEquals(money("300000"),insufficient.remaining());assertThrows(IllegalArgumentException.class,()->loans.repayResult(player,0));
        market.account(player).cash=money("1000");var tooMuch=loans.repayResult(player,money("2000"));
        assertEquals(Loans.RepayStatus.INSUFFICIENT_CASH,tooMuch.status());assertEquals(money("1000"),market.account(player).cash);
        market.account(player).cash=money("1000000");market.clockMillis=300000;
        var auto=loans.repayResult(player,-1);assertEquals(Loans.RepayStatus.CLEARED,auto.status());assertEquals(0,auto.amount());assertEquals(0,auto.remaining());
        assertEquals(Loans.RepayStatus.NO_DEBT,loans.repayResult(player,-1).status());
    }
}
