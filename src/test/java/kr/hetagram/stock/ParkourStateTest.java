package kr.hetagram.stock;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ParkourStateTest {
    @TempDir Path directory;
    private GameConfig config;
    private StateStore store;
    private final UUID player=UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach void setup() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml"))
            try(var in=getClass().getResourceAsStream("/"+name)) {Files.copy(Objects.requireNonNull(in),directory.resolve(name));}
        config=GameConfig.load(directory.toFile());store=new StateStore(directory.toFile());
    }

    @Test void rewardCooldownRoundTripPreservesExistingMoneyHoldingsAndNames() throws Exception {
        var market=new Market(config);var account=market.account(player);account.cash=123456789;account.realized=-9876;account.name="점프 참가자";
        account.holdings.put("redtech",new Market.Position(2,10000000));account.parkourRewardReadyAt=1792200000123L;
        store.save(market);byte[] before=Files.readAllBytes(directory.resolve("state.yml"));
        var restored=new Market(config);store.load(restored);var loaded=restored.account(player);
        assertEquals(account.parkourRewardReadyAt,loaded.parkourRewardReadyAt);assertEquals(account.cash,loaded.cash);
        assertEquals(account.realized,loaded.realized);assertEquals(account.name,loaded.name);
        assertEquals(2,loaded.holdings.get("redtech").quantity);assertEquals(10000000,loaded.holdings.get("redtech").cost);
        assertArrayEquals(before,Files.readAllBytes(directory.resolve("state.yml")));
    }

    @Test void oldStateWithoutTheOptionalKeyLoadsAsNoCooldownAndIsNeverRewritten() throws Exception {
        var market=new Market(config);market.account(player).cash=333;store.save(market);
        var yaml=YamlConfiguration.loadConfiguration(directory.resolve("state.yml").toFile());
        yaml.set("accounts."+player+".parkour-reward-ready-at",null);yaml.save(directory.resolve("state.yml").toFile());
        byte[] before=Files.readAllBytes(directory.resolve("state.yml"));var restored=new Market(config);store.load(restored);
        assertEquals(0,restored.account(player).parkourRewardReadyAt);assertEquals(333,restored.account(player).cash);
        assertArrayEquals(before,Files.readAllBytes(directory.resolve("state.yml")));
    }

    @Test void malformedCooldownIsRejectedWithoutRepairingTheSavedFile() throws Exception {
        var market=new Market(config);market.account(player);store.save(market);String valid=Files.readString(directory.resolve("state.yml"));
        for(Object malformed:List.of(-1,1.5,"123",true,List.of(12),"not-a-time")) {
            var yaml=new YamlConfiguration();yaml.loadFromString(valid);yaml.set("accounts."+player+".parkour-reward-ready-at",malformed);
            yaml.save(directory.resolve("state.yml").toFile());byte[] before=Files.readAllBytes(directory.resolve("state.yml"));
            assertThrows(Exception.class,()->store.load(new Market(config)),malformed.toString());
            assertArrayEquals(before,Files.readAllBytes(directory.resolve("state.yml")));
        }
        market.account(player).parkourRewardReadyAt=-1;
        assertThrows(IllegalArgumentException.class,()->store.capture(market));
    }

    @Test void resetAccountsClearsCooldownButReloadPreservesIt() throws Exception {
        var market=new Market(config);var account=market.account(player);account.parkourRewardReadyAt=Long.MAX_VALUE;
        market.reconfigure(GameConfig.load(directory.toFile()));assertSame(account,market.account(player));
        assertEquals(Long.MAX_VALUE,market.account(player).parkourRewardReadyAt);
        market.reset();assertEquals(0,market.account(player).parkourRewardReadyAt);
        store.save(market);var restored=new Market(config);store.load(restored);
        assertEquals(0,restored.account(player).parkourRewardReadyAt);
    }
}
