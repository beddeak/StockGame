package kr.hetagram.stock;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class LoanSettingsTest {
    @TempDir Path directory;

    @BeforeEach void setup() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml"))restore(name);
    }

    private void restore(String name) throws Exception {
        try(var input=getClass().getResourceAsStream("/"+name)) {
            Files.copy(Objects.requireNonNull(input),directory.resolve(name),StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private GameConfig edit(Consumer<YamlConfiguration> change) throws Exception {
        var yaml=new YamlConfiguration();yaml.load(directory.resolve("config.yml").toFile());
        change.accept(yaml);yaml.save(directory.resolve("config.yml").toFile());return GameConfig.load(directory.toFile());
    }

    private void pendingUpgrade(Consumer<YamlConfiguration> change) throws Exception {
        var yaml=new YamlConfiguration();yaml.set("schema",1);yaml.set("max-principal",3000000);
        yaml.set("require-low-assets",false);yaml.set("allow-top-up",true);
        change.accept(yaml);yaml.save(directory.resolve("pending-loan-upgrade.yml").toFile());
    }

    @Test void shippedLoanRulesAllowTopUpsWithoutAssetRestrictionUpToThreeMillionWon() throws Exception {
        var rules=GameConfig.load(directory.toFile()).loanRules();
        assertTrue(rules.enabled());assertFalse(rules.requireLowAssets());assertTrue(rules.allowTopUp());
        assertEquals(Market.cents("10000"),rules.minPrincipal());
        assertEquals(Market.cents("300000"),rules.defaultPrincipal());
        assertEquals(Market.cents("3000000"),rules.maxPrincipal());
    }

    @Test void missingNewFlagsUseNewDefaultsWithoutChangingLegacyAmountsOrWritingTheFile() throws Exception {
        edit(yaml->{
            yaml.set("loans.require-low-assets",null);yaml.set("loans.allow-top-up",null);
            yaml.set("loans.max-principal",750000);yaml.set("loans.term-seconds",421);yaml.set("loans.interest-percent",4);
        });
        byte[] before=Files.readAllBytes(directory.resolve("config.yml"));
        var loaded=GameConfig.load(directory.toFile()).loanRules();
        assertFalse(loaded.requireLowAssets());assertTrue(loaded.allowTopUp());
        assertEquals(Market.cents("750000"),loaded.maxPrincipal());
        assertEquals(421,loaded.termSeconds());assertEquals(4,loaded.interestPercent());
        assertArrayEquals(before,Files.readAllBytes(directory.resolve("config.yml")));
    }

    @Test void theTwoLoanBehaviorFlagsAreStrictBooleans() throws Exception {
        for(String key:List.of("loans.require-low-assets","loans.allow-top-up"))
            for(Object invalid:List.of("false","true",0,1,List.of(true))) {
                restore("config.yml");
                assertThrows(IllegalArgumentException.class,()->edit(yaml->yaml.set(key,invalid)),key+"="+invalid);
            }
    }

    @Test void explicitLegacyRestrictionsRemainSelectableAndSurviveOtherSettingsCopies() throws Exception {
        var restricted=edit(yaml->{yaml.set("loans.require-low-assets",true);yaml.set("loans.allow-top-up",false);yaml.set("loans.max-principal",500000);});
        var rules=restricted.loanRules();assertTrue(rules.requireLowAssets());assertFalse(rules.allowTopUp());
        var copied=restricted.withInterval(0.5).withInitialCash(Market.cents("123456.78")).withSkin("blueenergy","Player_42");
        assertEquals(rules,copied.loanRules());
        copied.ui().save(directory.resolve("config.yml").toFile());
        assertEquals(rules,GameConfig.load(directory.toFile()).loanRules());
    }

    @Test void pendingLoanUpgradeOverridesOnlyRequestedRulesInMemoryAndNeverRewritesSourceFiles() throws Exception {
        var legacy=edit(yaml->{
            yaml.set("loans.require-low-assets",true);yaml.set("loans.allow-top-up",false);
            yaml.set("loans.max-principal",500000);yaml.set("loans.term-seconds",421);
            yaml.set("loans.interest-percent",4);yaml.set("game.initial-cash",1234567);
        });
        pendingUpgrade(yaml->{});
        var originalFiles=new java.util.LinkedHashMap<String,byte[]>();
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml","pending-loan-upgrade.yml"))
            originalFiles.put(name,Files.readAllBytes(directory.resolve(name)));
        var upgraded=GameConfig.load(directory.toFile());var rules=upgraded.loanRules();
        assertEquals(Market.cents("3000000"),rules.maxPrincipal());
        assertFalse(rules.requireLowAssets());assertTrue(rules.allowTopUp());
        assertEquals(legacy.loanRules().minPrincipal(),rules.minPrincipal());
        assertEquals(legacy.loanRules().defaultPrincipal(),rules.defaultPrincipal());
        assertEquals(421,rules.termSeconds());assertEquals(4,rules.interestPercent());
        assertEquals(legacy.rules().initialCash(),upgraded.rules().initialCash());
        assertEquals(legacy.stocks(),upgraded.stocks());assertEquals(legacy.news(),upgraded.news());
        for(var entry:originalFiles.entrySet())assertArrayEquals(entry.getValue(),Files.readAllBytes(directory.resolve(entry.getKey())),entry.getKey());
    }

    @Test void pendingLoanUpgradeRejectsWrongSchemaWithoutChangingTheActiveConfig() throws Exception {
        byte[] before=Files.readAllBytes(directory.resolve("config.yml"));
        for(Object schema:List.of(0,2,1.5,"1",List.of(1))) {
            pendingUpgrade(yaml->yaml.set("schema",schema));
            assertThrows(IllegalArgumentException.class,()->GameConfig.load(directory.toFile()),schema.toString());
            assertArrayEquals(before,Files.readAllBytes(directory.resolve("config.yml")));
        }
        pendingUpgrade(yaml->yaml.set("schema",null));
        assertThrows(IllegalArgumentException.class,()->GameConfig.load(directory.toFile()));
        assertArrayEquals(before,Files.readAllBytes(directory.resolve("config.yml")));
    }

    @Test void pendingLoanUpgradeRequiresBothStrictBooleanFlagsBeforeItCanBeApplied() throws Exception {
        byte[] before=Files.readAllBytes(directory.resolve("config.yml"));
        for(String key:List.of("require-low-assets","allow-top-up")) {
            for(Object invalid:List.of("false","true",0,1,List.of(true))) {
                pendingUpgrade(yaml->yaml.set(key,invalid));
                assertThrows(IllegalArgumentException.class,()->GameConfig.load(directory.toFile()),key+"="+invalid);
                assertArrayEquals(before,Files.readAllBytes(directory.resolve("config.yml")));
            }
            pendingUpgrade(yaml->yaml.set(key,null));
            assertThrows(IllegalArgumentException.class,()->GameConfig.load(directory.toFile()),key+" missing");
        }
    }
}
