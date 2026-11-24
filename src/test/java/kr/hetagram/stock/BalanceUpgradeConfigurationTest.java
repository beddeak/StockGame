package kr.hetagram.stock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.*;
import java.util.List;
import java.util.Objects;
import static org.junit.jupiter.api.Assertions.*;

class BalanceUpgradeConfigurationTest {
    @TempDir Path directory;
    private void setup() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml"))try(var in=getClass().getResourceAsStream("/"+name)){Files.copy(Objects.requireNonNull(in),directory.resolve(name));}
    }
    @Test void stagedProfileChangesOnlyRequestedRulesAndLoadDoesNotConsumeOrWriteIt() throws Exception {
        setup();var config=YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile());
        config.set("game.tick-seconds",.15);config.set("game.initial-cash",7654321);config.set("loans.term-seconds",300);config.save(directory.resolve("config.yml").toFile());
        var map=YamlConfiguration.loadConfiguration(directory.resolve("map.yml").toFile());map.set("protection.allow-building",true);map.save(directory.resolve("map.yml").toFile());
        byte[] originalConfig=Files.readAllBytes(directory.resolve("config.yml")),originalMap=Files.readAllBytes(directory.resolve("map.yml"));
        var upgrade=directory.resolve("pending-balance-upgrade.yml");Files.writeString(upgrade,"schema: 1\nterm-seconds: 120\ninterest-interval-seconds: 30\ninterest-percent: 10\nlate-fee-percent: 25\nmax-missed-payments: 2\nallow-building: false\n");
        var next=GameConfig.load(directory.toFile());assertEquals(120,next.loanRules().termSeconds());assertEquals(30,next.loanRules().interestIntervalSeconds());
        assertEquals(10,next.loanRules().interestPercent());assertFalse(next.map().getBoolean("protection.allow-building"));
        assertEquals(.15,next.rules().tickSeconds());assertEquals(Market.cents("7654321"),next.rules().initialCash());assertEquals(5,next.stocks().size());assertEquals(50,next.news().size());
        assertTrue(Files.exists(upgrade));assertArrayEquals(originalConfig,Files.readAllBytes(directory.resolve("config.yml")));assertArrayEquals(originalMap,Files.readAllBytes(directory.resolve("map.yml")));
    }
    @Test void malformedStagedProfileIsRejectedWithoutChangingAnyLiveConfiguration() throws Exception {
        setup();byte[] original=Files.readAllBytes(directory.resolve("config.yml"));
        Files.writeString(directory.resolve("pending-balance-upgrade.yml"),"schema: 1\nterm-seconds: 0\ninterest-interval-seconds: 30\ninterest-percent: 10\nlate-fee-percent: 25\nmax-missed-payments: 2\nallow-building: false\n");
        assertThrows(IllegalArgumentException.class,()->GameConfig.load(directory.toFile()));assertArrayEquals(original,Files.readAllBytes(directory.resolve("config.yml")));
    }
}
