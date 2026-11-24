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

/** 단순화 이전 state.yml(연체·연속 미납·유예·페널티 필드, schema 1 계약)을 새 규칙으로 읽습니다. */
class DebtStateMigrationTest {
    @TempDir Path directory;
    private GameConfig config;private UUID player;private Market savedMarket;private StateStore store;
    @BeforeEach void setup() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml"))
            try(var stream=getClass().getResourceAsStream("/"+name)){Files.copy(Objects.requireNonNull(stream),directory.resolve(name));}
        config=GameConfig.load(directory.toFile());
        player=UUID.randomUUID();savedMarket=new Market(config);savedMarket.running=true;var account=savedMarket.account(player);account.cash=0;
        new Loans(savedMarket).borrow(player,config.loanRules().defaultPrincipal());account.cash=Market.cents("1234.56");account.realized=-12345;
        account.holdings.put("redtech",new Market.Position(2,Market.cents("200000")));store=new StateStore(directory.toFile());
    }
    private YamlConfiguration read() throws Exception {var yaml=new YamlConfiguration();yaml.load(directory.resolve("state.yml").toFile());return yaml;}
    /** 예전 버전이 저장하던 필드와 schema 1 계약으로 바꿔 씁니다. */
    private void legacy(long clock,boolean overdue,boolean bankrupt,boolean resolved) throws Exception {
        savedMarket.clockMillis=clock;store.save(savedMarket);var yaml=read();String key="accounts."+player+".loan",contract=key+".contract";
        yaml.set(contract+".schema",1);yaml.set(contract+".upfront-fee-percent",null);
        yaml.set(contract+".late-fee-percent",10.0);yaml.set(contract+".penalty-mode","collections");yaml.set(contract+".penalty-seconds",60);
        yaml.set(contract+".max-missed-payments",3);yaml.set(contract+".bankruptcy-grace-seconds",120);yaml.set(contract+".auto-liquidate-collateral",true);
        yaml.set(key+".overdue",overdue);yaml.set(key+".liquidated",false);yaml.set(key+".bankrupt",bankrupt);yaml.set(key+".missed-payments",99);
        yaml.set(key+".next-collection-at",60000);yaml.set(key+".collector-resolved",resolved);yaml.set(key+".bankruptcy-eligible-at",420000);
        yaml.set(key+".penalty-remaining-millis",0);yaml.set(key+".last-clock",clock);yaml.set(key+".penalty-mode","collections");
        yaml.set(key+".collector-rest-until",null);yaml.set(key+".due-warned",null);yaml.set(key+".bankrupt-at",bankrupt?clock:null);
        yaml.save(directory.resolve("state.yml").toFile());
    }
    private Market load() throws Exception {var market=new Market(config);store.load(market);return market;}
    private void unchangedMoney(Market loaded) {
        var expected=savedMarket.account(player);var actual=loaded.account(player);assertEquals(expected.cash,actual.cash);assertEquals(expected.realized,actual.realized);
        assertEquals(expected.debt.balance(),actual.debt.balance());assertEquals(2,actual.holdings.get("redtech").quantity);assertEquals(Market.cents("200000"),actual.holdings.get("redtech").cost);
    }
    @Test void legacyActiveLoanLoadsWithItsOriginalTermsAndNoRetroactiveUpfrontFee() throws Exception {
        legacy(120000,false,false,false);byte[] before=Files.readAllBytes(directory.resolve("state.yml"));var loaded=load();var debt=loaded.account(player).debt;
        assertFalse(debt.bankrupt);assertEquals(0,debt.collectorRestUntil);assertFalse(debt.dueWarned);
        assertEquals(0,debt.contract.rules().upfrontFeePercent(),"이미 원금을 다 받은 예전 계약에 선이자를 소급하지 않습니다");
        assertEquals(5,debt.contract.rules().interestPercent());assertEquals(300,debt.contract.rules().termSeconds());
        unchangedMoney(loaded);assertArrayEquals(before,Files.readAllBytes(directory.resolve("state.yml")));
    }
    @Test void legacyOverdueLoanIsSettledByTheNewRulesAtTheNextCollectionInsteadOfWaitingForMisses() throws Exception {
        legacy(400000,true,false,false);var loaded=load();var debt=loaded.account(player).debt;assertFalse(debt.bankrupt);
        var events=new Loans(loaded).advance(Set.of());var account=loaded.account(player);
        // 예전 연체(미납 99회 기록)는 무시하고, 새 규칙대로 현금과 주식을 모두 회수한 뒤 모자라면 즉시 파산합니다.
        assertTrue(account.debt.bankrupt);assertEquals(400000,account.debt.bankruptAt);assertTrue(account.holdings.isEmpty());assertEquals(0,account.cash);
        assertTrue(events.stream().anyMatch(e->e.kind()==Loans.Kind.BANKRUPT));
    }
    @Test void legacyBankruptcyStaysBankruptAndAnOldResolvedCollectorWillVisitAgain() throws Exception {
        legacy(500000,true,true,true);byte[] before=Files.readAllBytes(directory.resolve("state.yml"));var loaded=load();var debt=loaded.account(player).debt;
        assertTrue(debt.bankrupt);assertEquals(500000,debt.bankruptAt);assertEquals(0,debt.collectorRestUntil);
        assertTrue(CollectorPolicy.eligible(debt,true,true,true,false,false,true,500000+30000,30000),"빚이 남았으면 예전 추심 종료 기록과 관계없이 다시 찾아옵니다");
        unchangedMoney(loaded);assertArrayEquals(before,Files.readAllBytes(directory.resolve("state.yml")));
    }
    @Test void newFieldsRoundTripAndInvalidValuesFailWithoutRepairingOrOverwritingTheSavedAssets() throws Exception {
        var debt=savedMarket.account(player).debt;savedMarket.clockMillis=debt.dueAt+300000;debt.bankrupt=true;debt.bankruptAt=debt.dueAt;
        debt.collectorRestUntil=savedMarket.clockMillis+60000;debt.dueWarned=true;store.save(savedMarket);
        var restored=load().account(player).debt;assertTrue(restored.bankrupt);assertEquals(debt.bankruptAt,restored.bankruptAt);
        assertEquals(debt.collectorRestUntil,restored.collectorRestUntil);assertTrue(restored.dueWarned);
        String valid=Files.readString(directory.resolve("state.yml")),key="accounts."+player+".loan";
        for(var damage:List.<Object[]>of(new Object[]{"bankrupt-at",savedMarket.clockMillis+1},new Object[]{"bankrupt-at",1.5},new Object[]{"collector-rest-until",-1},
                new Object[]{"collector-rest-until","123"},new Object[]{"due-warned","yes"},new Object[]{"bankrupt","true"})) {
            var yaml=new YamlConfiguration();yaml.loadFromString(valid);yaml.set(key+"."+damage[0],damage[1]);yaml.save(directory.resolve("state.yml").toFile());
            byte[] damaged=Files.readAllBytes(directory.resolve("state.yml"));assertThrows(Exception.class,this::load,damage[0]+"="+damage[1]);
            assertArrayEquals(damaged,Files.readAllBytes(directory.resolve("state.yml")));
        }
    }
}
