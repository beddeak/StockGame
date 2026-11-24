package kr.hetagram.stock;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UpgradeConfigurationTest {
    @TempDir Path directory;
    private void files() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml"))
            try(var in=getClass().getResourceAsStream("/"+name)){Files.copy(Objects.requireNonNull(in),directory.resolve(name));}
    }
    @Test void pendingUpgradePreservesLatestAdminIntervalAndExistingAccountsWithoutWritingFiles() throws Exception {
        files();var file=directory.resolve("config.yml");var yaml=YamlConfiguration.loadConfiguration(file.toFile());
        yaml.set("game.max-price",1000000000);yaml.set("game.tick-seconds",1.3);yaml.set("loans",null);
        yaml.set("ui.money-decimals",null);yaml.save(file.toFile());
        Files.writeString(directory.resolve("pending-upgrade.yml"),"schema: 1\nmax-price: 1000000000000\n");
        byte[] before=Files.readAllBytes(file);var config=GameConfig.load(directory.toFile());
        assertEquals(Market.cents("1000000000000"),config.rules().maxPrice());assertEquals(1.3,config.rules().tickSeconds());
        assertEquals(0,config.ui().getInt("ui.money-decimals"));
        var market=new Market(config);UUID player=UUID.randomUUID();market.account(player).cash=Market.cents("12345.67");
        market.setPrice("redtech",Market.cents("5000000000"));new StateStore(directory.toFile()).save(market);
        var restored=new Market(GameConfig.load(directory.toFile()));new StateStore(directory.toFile()).load(restored);
        assertEquals(market.account(player).cash,restored.account(player).cash);assertEquals(market.quote("redtech").price,restored.quote("redtech").price);
        assertArrayEquals(before,Files.readAllBytes(file));assertTrue(Files.exists(directory.resolve("pending-upgrade.yml")));
    }
    @Test void malformedUpgradeFailsWithoutChangingConfigurationOrSavedAssets() throws Exception {
        files();var file=directory.resolve("config.yml");byte[] before=Files.readAllBytes(file);
        Files.writeString(directory.resolve("pending-upgrade.yml"),"schema: 2\nmax-price: -1\n");
        assertThrows(IllegalArgumentException.class,()->GameConfig.load(directory.toFile()));assertArrayEquals(before,Files.readAllBytes(file));
    }
    @Test void moneyCommandRejectsInvalidAndOverflowingInputBeforeAnyTransaction() {
        assertEquals(Market.cents("10000"),StockPlugin.wholeMoney("10000"));
        for(String input:List.of("0","-1","1.5","NaN","Infinity","1e6","1,000","","999999999999999999999"))
            assertThrows(RuntimeException.class,()->StockPlugin.wholeMoney(input),input);
    }
}
