package kr.hetagram.stock;

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

class LoanAdminSettingsTest {
    @TempDir Path directory;
    private GameConfig config;
    private static long money(String value) {return Market.cents(value);}

    @BeforeEach void setup() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml"))
            try(var input=getClass().getResourceAsStream("/"+name)) {
                Files.copy(Objects.requireNonNull(input),directory.resolve(name));
            }
        config=GameConfig.load(directory.toFile());
    }

    @Test void everyAllowedSettingAcceptsItsOwnTypeAndMoneyKeepsExactCents() throws Exception {
        String original=config.ui().saveToString();
        var updated=config.withLoanSetting("loans.enabled","FALSE")
                .withLoanSetting("require-low-assets","TRUE").withLoanSetting("allow-top-up","false")
                .withLoanSetting("due-warning-seconds","90").withLoanSetting("collector.rest-seconds","45")
                .withLoanSetting("collector.grace-seconds","20").withLoanSetting("collector.damage","1.5")
                .withLoanSetting("collector.attack-interval-ticks","80")
                .withLoanSetting("min-principal","10000.25").withLoanSetting("max-principal","2500000.50")
                .withLoanSetting("default-principal","345678.90").withLoanSetting("term-seconds"," 45 ")
                .withLoanSetting("interest-interval-seconds","15").withLoanSetting("upfront-fee-percent","33.75")
                .withLoanSetting("interest-percent","12.5").withLoanSetting("max-interest-percent","180.125");
        var rules=updated.loanRules();
        assertFalse(rules.enabled());assertTrue(rules.requireLowAssets());assertFalse(rules.allowTopUp());
        assertEquals(money("10000.25"),rules.minPrincipal());assertEquals(money("2500000.50"),rules.maxPrincipal());
        assertEquals(money("345678.90"),rules.defaultPrincipal());assertEquals(45,rules.termSeconds());
        assertEquals(15,rules.interestIntervalSeconds());assertEquals(33.75,rules.upfrontFeePercent());
        assertEquals(90,updated.ui().getInt("loans.due-warning-seconds"));assertEquals(45,updated.ui().getInt("loans.collector.rest-seconds"));
        assertEquals(20,updated.ui().getInt("loans.collector.grace-seconds"));assertEquals(1.5,updated.ui().getDouble("loans.collector.damage"));
        assertEquals(80,updated.ui().getInt("loans.collector.attack-interval-ticks"));
        assertEquals(12.5,rules.interestPercent());assertEquals(180.125,rules.maxInterestPercent());
        assertEquals(original,config.ui().saveToString());assertNotSame(config.ui(),updated.ui());
        updated.ui().save(directory.resolve("config.yml").toFile());
        assertEquals(rules,GameConfig.load(directory.toFile()).loanRules());
    }

    @Test void theWhitelistCannotBeUsedToChangeOtherGameProtectionOrCollectorSettings() throws Exception {
        String original=config.ui().saveToString();byte[] disk=Files.readAllBytes(directory.resolve("config.yml"));
        for(String key:List.of("game.initial-cash","game.max-price","loans.penalty-mode","penalty-mode",
                "loans.collector.enabled","bankruptcy-threshold","term-seconds.extra","LOANS.term-seconds",""))
            assertThrows(IllegalArgumentException.class,()->config.withLoanSetting(key,"1"),key);
        assertEquals(original,config.ui().saveToString());assertArrayEquals(disk,Files.readAllBytes(directory.resolve("config.yml")));
    }

    @Test void invalidBooleanNumericPrecisionAndRangeInputsNeverModifyTheOriginal() throws Exception {
        String original=config.ui().saveToString();
        for(String key:List.of("enabled","require-low-assets","allow-top-up","auto-liquidate-collateral"))
            for(String value:List.of("1","0","yes"," true ","False!"))
                assertThrows(IllegalArgumentException.class,()->config.withLoanSetting(key,value),key+"="+value);
        for(String value:new String[]{null,"","  "})
            assertThrows(IllegalArgumentException.class,()->config.withLoanSetting("term-seconds",value));
        var invalid=Map.of(
                "term-seconds",List.of("0","-1","1","2","29","1.5","31536001","2147483648"),
                "interest-interval-seconds",List.of("0","1","9","30.5","31536001"),
                "max-missed-payments",List.of("0","2.5","1001"),
                "interest-percent",List.of("-0.01","100.01","NaN","Infinity","1e999","nope"),
                "late-fee-percent",List.of("-1","101"),
                "max-interest-percent",List.of("-1","1000.01"),
                "min-principal",List.of("0","-1","10000.001","92233720368547758.08"));
        for(var entry:invalid.entrySet())for(String value:entry.getValue())
            assertThrows(RuntimeException.class,()->config.withLoanSetting(entry.getKey(),value),entry.getKey()+"="+value);
        for(String key:List.of("bankruptcy-grace-seconds","collector.grace-seconds","collector.attack-interval-ticks"))
            for(String value:List.of("-1","1.5","31536001"))
                assertThrows(IllegalArgumentException.class,()->config.withLoanSetting(key,value),key+"="+value);
        for(String value:List.of("0","-1","10001","NaN"))
            assertThrows(IllegalArgumentException.class,()->config.withLoanSetting("collector.damage",value),value);
        assertEquals(original,config.ui().saveToString());
    }

    @Test void minimumMaximumAndDefaultMustRemainConsistentAtEveryChange() throws Exception {
        String original=config.ui().saveToString();
        for(var entry:Map.of("min-principal","300001","max-principal","299999").entrySet())
            assertThrows(IllegalArgumentException.class,()->config.withLoanSetting(entry.getKey(),entry.getValue()));
        for(String value:List.of("9999","3000001"))
            assertThrows(IllegalArgumentException.class,()->config.withLoanSetting("default-principal",value));
        assertEquals(original,config.ui().saveToString());
        var updated=config.withLoanSetting("default-principal","1000000")
                .withLoanSetting("min-principal","500000").withLoanSetting("max-principal","2000000");
        assertEquals(money("500000"),updated.loanRules().minPrincipal());
        assertEquals(money("1000000"),updated.loanRules().defaultPrincipal());assertEquals(money("2000000"),updated.loanRules().maxPrincipal());
        String valid=updated.ui().saveToString();
        assertThrows(IllegalArgumentException.class,()->updated.withLoanSetting("min-principal","2000001"));
        assertEquals(valid,updated.ui().saveToString());
    }

    @Test void applyingLoanSettingsPreservesMarketAccountsPricesNewsAndStoredLoanTimes() throws Exception {
        var original=config.withInterval(.25).withInitialCash(money("2222222"));var market=new Market(original);market.running=true;
        UUID player=UUID.randomUUID();market.setPrice("redtech",money("123456.78"));market.buy(player,"redtech",3);
        new Loans(market).borrow(player,money("300000"));var account=market.account(player);var debt=account.debt;
        market.clockMillis=45000;market.nextNewsMillis=90000;market.latestHeadline="settings-change-check";
        market.quote("redtech").effect=new Market.Effect(.7,1.4,75000,market.quote("redtech").price,30);
        var store=new StateStore(directory.toFile());store.save(market);byte[] before=Files.readAllBytes(directory.resolve("state.yml"));
        long due=debt.dueAt,nextInterest=debt.nextInterestAt;
        var updated=original.withLoanSetting("term-seconds","180").withLoanSetting("interest-percent","15");
        assertEquals(original.rules(),updated.rules());assertEquals(original.stocks(),updated.stocks());assertEquals(original.news(),updated.news());
        assertEquals(original.map().saveToString(),updated.map().saveToString());
        assertEquals(300,original.loanRules().termSeconds());assertEquals(5,original.loanRules().interestPercent());
        market.reconfigure(updated);store.save(market);
        assertSame(account,market.account(player));assertSame(debt,account.debt);
        assertEquals(due,debt.dueAt);assertEquals(nextInterest,debt.nextInterestAt);
        assertTrue(market.running);assertEquals(45000,market.clockMillis);assertEquals(90000,market.nextNewsMillis);
        assertEquals(.25,market.config.rules().tickSeconds());assertEquals(money("2222222"),market.config.rules().initialCash());
        assertArrayEquals(before,Files.readAllBytes(directory.resolve("state.yml")));
    }
}
