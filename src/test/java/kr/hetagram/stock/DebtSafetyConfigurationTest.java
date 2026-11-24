package kr.hetagram.stock;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DebtSafetyConfigurationTest {
    @TempDir Path directory;
    @BeforeEach void setup() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml"))
            try(var stream=getClass().getResourceAsStream("/"+name)){Files.copy(Objects.requireNonNull(stream),directory.resolve(name));}
    }
    private YamlConfiguration read(String name) throws Exception {var yaml=new YamlConfiguration();yaml.load(directory.resolve(name).toFile());return yaml;}
    private void save(String name,YamlConfiguration yaml) throws Exception {yaml.save(directory.resolve(name).toFile());}
    private YamlConfiguration safety() {
        var yaml=new YamlConfiguration();yaml.set("schema",1);yaml.set("term-seconds",300);yaml.set("interest-interval-seconds",60);
        yaml.set("interest-percent",5);yaml.set("late-fee-percent",10);yaml.set("max-missed-payments",3);
        yaml.set("bankruptcy-grace-seconds",120);yaml.set("auto-liquidate-collateral",true);
        yaml.set("collector.grace-seconds",30);yaml.set("collector.damage",4);yaml.set("collector.attack-interval-ticks",40);
        yaml.set("collector.speed",1.0);yaml.set("collector.spawn-distance",10);return yaml;
    }
    @Test void packagedDefaultsUseUpfrontFeeLumpSumMaturityAndARepeatingCollector() throws Exception {
        var config=GameConfig.load(directory.toFile());var loans=config.loanRules();
        assertEquals(300,loans.termSeconds());assertEquals(60,loans.interestIntervalSeconds());assertEquals(5,loans.interestPercent());
        assertEquals(10,loans.upfrontFeePercent());assertEquals(100,loans.maxInterestPercent());
        assertEquals(30,config.ui().getInt("loans.due-warning-seconds"));assertEquals(60,config.ui().getInt("loans.collector.rest-seconds"));
        for(String retired:GameConfig.RETIRED_LOAN_KEYS)assertFalse(config.ui().isSet(retired),"배포 설정에 예전 규칙 키가 남으면 안 됩니다: "+retired);
        assertEquals(30,config.ui().getInt("loans.collector.grace-seconds"));assertEquals(4,config.ui().getDouble("loans.collector.damage"));
        assertEquals(40,config.ui().getInt("loans.collector.attack-interval-ticks"));assertEquals(1,config.ui().getDouble("loans.collector.speed"));assertEquals(10,config.ui().getDouble("loans.collector.spawn-distance"));
    }
    @Test void newPendingSafetyWinsOverOldBalanceWithoutConsumingFilesOrTouchingCustomData() throws Exception {
        var yaml=read("config.yml");yaml.set("game.tick-seconds",1.25);yaml.set("game.initial-cash",1234567);
        yaml.set("loans.max-principal",7654321);yaml.set("messages.loan-status","my status {debt}");yaml.set("ui.lender.loan-lore",List.of("my loan notice"));save("config.yml",yaml);
        var news=read("news.yml");String first=news.getConfigurationSection("news").getKeys(false).iterator().next();news.set("news."+first+".headline","직접 쓴 뉴스 {stock}");save("news.yml",news);
        Files.writeString(directory.resolve("state.yml"),"accounts: keep-exact-assets\n");
        var original=GameConfig.load(directory.toFile());var old=new YamlConfiguration();old.set("schema",1);old.set("term-seconds",120);
        old.set("interest-interval-seconds",30);old.set("interest-percent",10);old.set("late-fee-percent",25);old.set("max-missed-payments",2);old.set("allow-building",false);
        save("pending-balance-upgrade.yml",old);save("pending-debt-safety-upgrade.yml",safety());
        var before=new LinkedHashMap<String,byte[]>();for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml","state.yml","pending-balance-upgrade.yml","pending-debt-safety-upgrade.yml"))before.put(name,Files.readAllBytes(directory.resolve(name)));
        var updated=GameConfig.load(directory.toFile());var rules=updated.loanRules();
        assertEquals(300,rules.termSeconds());assertEquals(60,rules.interestIntervalSeconds());assertEquals(5,rules.interestPercent());
        assertEquals(30,updated.ui().getInt("loans.collector.grace-seconds"));
        assertEquals(original.rules(),updated.rules());assertEquals(original.stocks(),updated.stocks());assertEquals(original.news(),updated.news());assertEquals(original.loanRules().maxPrincipal(),rules.maxPrincipal());
        assertEquals("my status {debt}",updated.msg("loan-status"));assertEquals(List.of("my loan notice"),updated.ui().getStringList("ui.lender.loan-lore"));assertFalse(updated.map().getBoolean("protection.allow-building"));
        for(var entry:before.entrySet())assertArrayEquals(entry.getValue(),Files.readAllBytes(directory.resolve(entry.getKey())),entry.getKey());
    }
    @Test void malformedPendingSafetyIsRejectedWithoutRewritingOrDeletingItsInputs() throws Exception {
        for(var invalid:Map.of("schema",2,"auto-liquidate-collateral","true","collector.grace-seconds",-1,"collector.damage",0,"bankruptcy-grace-seconds",1.5).entrySet()) {
            var yaml=safety();yaml.set(invalid.getKey(),invalid.getValue());save("pending-debt-safety-upgrade.yml",yaml);
            byte[] pending=Files.readAllBytes(directory.resolve("pending-debt-safety-upgrade.yml")),config=Files.readAllBytes(directory.resolve("config.yml"));
            assertThrows(IllegalArgumentException.class,()->GameConfig.load(directory.toFile()),invalid.getKey());
            assertArrayEquals(pending,Files.readAllBytes(directory.resolve("pending-debt-safety-upgrade.yml")));assertArrayEquals(config,Files.readAllBytes(directory.resolve("config.yml")));
        }
    }
    @Test void newAdministratorKeysAcceptValidatedTypesAndLeaveOriginalSettingsUntouched() throws Exception {
        var original=GameConfig.load(directory.toFile());String before=original.ui().saveToString();
        var updated=original.withLoanSetting("loans.upfront-fee-percent","0").withLoanSetting("due-warning-seconds","0").withLoanSetting("collector.rest-seconds","3600")
                .withLoanSetting("collector.grace-seconds","86400").withLoanSetting("collector.damage","0.1").withLoanSetting("collector.attack-interval-ticks","1200");
        assertEquals(0,updated.loanRules().upfrontFeePercent());assertEquals(0,updated.ui().getInt("loans.due-warning-seconds"));assertEquals(3600,updated.ui().getInt("loans.collector.rest-seconds"));
        assertEquals(86400,updated.ui().getInt("loans.collector.grace-seconds"));assertEquals(.1,updated.ui().getDouble("loans.collector.damage"));assertEquals(1200,updated.ui().getInt("loans.collector.attack-interval-ticks"));
        assertEquals(before,original.ui().saveToString());assertEquals(original.rules(),updated.rules());assertEquals(original.stocks(),updated.stocks());assertEquals(original.news(),updated.news());
        assertEquals(90,original.withLoanSetting("max-interest-percent","100").withLoanSetting("upfront-fee-percent","90").loanRules().upfrontFeePercent());
    }
    @Test void administratorSafetyValuesRejectWrongTypesRangesAndUnapprovedPathsWithoutMutation() throws Exception {
        var original=GameConfig.load(directory.toFile());String before=original.ui().saveToString();
        var invalid=Map.of("upfront-fee-percent",List.of("-1","91","x"),"due-warning-seconds",List.of("-1","3601","1.5"),"collector.rest-seconds",List.of("-1","3601","0.5"),
                "collector.grace-seconds",List.of("-1","86401","0.5"),"collector.damage",List.of("0","0.09","10001","NaN","Infinity"),"collector.attack-interval-ticks",List.of("0","1201","1.5"));
        // 단순화 이전 규칙의 키는 관리자 창에서 더 이상 바꿀 수 없습니다.
        for(String retired:List.of("bankruptcy-grace-seconds","auto-liquidate-collateral","late-fee-percent","max-missed-payments","penalty-mode","penalty-seconds"))
            assertThrows(IllegalArgumentException.class,()->original.withLoanSetting(retired,"1"),retired);
        for(var entry:invalid.entrySet())for(String value:entry.getValue())assertThrows(IllegalArgumentException.class,()->original.withLoanSetting(entry.getKey(),value),entry.getKey()+"="+value);
        assertThrows(IllegalArgumentException.class,()->original.withLoanSetting("collector.profession","NONE"));assertEquals(before,original.ui().saveToString());
    }
}
