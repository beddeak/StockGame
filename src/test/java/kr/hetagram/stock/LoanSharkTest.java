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

/** 선이자·추가 차입·관리자 입력 하한처럼 "공짜로 빌려 굴리기"와 실수 설정을 막는 사채 규칙입니다. */
class LoanSharkTest {
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
    private List<Loans.Event> at(long clock){market.clockMillis=clock;return loans.advance(Set.of(player));}

    @Test void upfrontFeeIsNeverRefundedSoBorrowingAndRepayingImmediatelyStillCostsMoney() {
        var account=market.account(player);long before=account.cash;
        var borrowing=loans.borrow(player,money("3000000"));
        assertEquals(money("300000"),borrowing.fee());assertEquals(money("2700000"),borrowing.received());
        assertEquals(before+money("2700000"),account.cash);
        assertEquals(Loans.RepayStatus.CLEARED,loans.repayResult(player,-1).status());
        assertNull(account.debt);assertEquals(before-money("300000"),account.cash,"바로 갚아도 선이자 30만 원은 돌아오지 않습니다");
    }

    @Test void earlyRepaymentOnlyPaysTheInterestAccumulatedSoFar() {
        var account=market.account(player);long before=account.cash;loans.borrow(player,money("300000"));
        at(120000);assertEquals(money("30000"),account.debt.interest);
        loans.repay(player,-1);assertNull(account.debt);
        assertEquals(before-money("30000")-money("30000"),account.cash,"2분 만에 갚으면 선이자 10% + 이자 2회(10%)");
    }

    @Test void topUpChargesAnotherUpfrontFeeKeepsTheDueDateAndRaisesTheInterestCapWithTheNewPrincipal() {
        loans.borrow(player,money("300000"));var debt=market.account(player).debt;
        market.clockMillis=60000;var topUp=loans.borrow(player,money("300000"));
        assertEquals(money("30000"),topUp.fee());assertEquals(300000,debt.dueAt);
        assertEquals(money("600000"),debt.originalPrincipal);assertEquals(money("75000"),debt.totalInterest);
        market.account(player).cash=0;at(100000000);assertTrue(debt.bankrupt);
        assertEquals(money("600000"),debt.totalInterest,"상한은 누적 차입 원금 60만 원의 100%입니다");
    }

    @Test void interestCapNeverLetsAnUpfrontFeeExceedIt() throws Exception {
        assertThrows(IllegalArgumentException.class,()->config.withLoanSetting("upfront-fee-percent","101"));
        assertThrows(IllegalArgumentException.class,()->config.withLoanSetting("max-interest-percent","5"),"선이자 10%보다 낮은 총이자 상한은 모순입니다");
        var yaml=YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile());
        yaml.set("loans.upfront-fee-percent",20);yaml.set("loans.max-interest-percent",10);yaml.save(directory.resolve("config.yml").toFile());
        assertThrows(IllegalArgumentException.class,()->GameConfig.load(directory.toFile()));
    }

    @Test void adminInputRejectsSecondScaleTimingButTestValuesInTheFileStillLoadWithWarnings() throws Exception {
        assertTrue(config.loanTimingWarnings().isEmpty());String before=config.ui().saveToString();
        for(var invalid:List.of(List.of("interest-interval-seconds","1"),List.of("interest-interval-seconds","9"),List.of("term-seconds","2"),List.of("term-seconds","29")))
            assertThrows(IllegalArgumentException.class,()->config.withLoanSetting(invalid.get(0),invalid.get(1)),String.join("=",invalid));
        assertEquals(before,config.ui().saveToString());
        var minimum=config.withLoanSetting("interest-interval-seconds","10").withLoanSetting("term-seconds","30");
        assertEquals(10,minimum.loanRules().interestIntervalSeconds());assertEquals(30,minimum.loanRules().termSeconds());
        assertTrue(minimum.loanTimingWarnings().isEmpty());
        var yaml=YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile());
        yaml.set("loans.term-seconds",2);yaml.set("loans.interest-interval-seconds",1);yaml.save(directory.resolve("config.yml").toFile());
        var leftover=GameConfig.load(directory.toFile());assertEquals(2,leftover.loanRules().termSeconds());assertEquals(2,leftover.loanTimingWarnings().size());
    }
}
