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

class LenderMenuConfigurationTest {
    @TempDir Path directory;

    @BeforeEach void setup() throws Exception {
        for(String file:List.of("config.yml","stocks.yml","news.yml","map.yml"))restore(file);
    }

    private void restore(String file) throws Exception {
        try(var input=getClass().getResourceAsStream("/"+file)) {
            Files.copy(Objects.requireNonNull(input),directory.resolve(file),StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private GameConfig edit(String file,Consumer<YamlConfiguration> change) throws Exception {
        var yaml=new YamlConfiguration();yaml.load(directory.resolve(file).toFile());
        change.accept(yaml);yaml.save(directory.resolve(file).toFile());return GameConfig.load(directory.toFile());
    }

    @Test void oldMapsDefaultToProtectionAndPreservationWithoutBeingRewritten() throws Exception {
        edit("map.yml",yaml->{yaml.set("protection.allow-building",null);yaml.set("layout.preserve-builds",null);});
        byte[] before=Files.readAllBytes(directory.resolve("map.yml"));
        var loaded=GameConfig.load(directory.toFile());
        assertFalse(loaded.map().getBoolean("protection.allow-building"));
        assertTrue(loaded.map().getBoolean("layout.preserve-builds"));
        assertArrayEquals(before,Files.readAllBytes(directory.resolve("map.yml")));
        var locked=edit("map.yml",yaml->{yaml.set("protection.allow-building",false);yaml.set("layout.preserve-builds",false);});
        assertFalse(locked.map().getBoolean("protection.allow-building"));assertFalse(locked.map().getBoolean("layout.preserve-builds"));
    }

    @Test void buildingAndPreservationFlagsRejectStringsNumbersAndLists() throws Exception {
        for(String key:List.of("protection.allow-building","layout.preserve-builds"))
            for(Object invalid:List.of("true",1,List.of(true))) {
                restore("map.yml");
                assertThrows(IllegalArgumentException.class,()->edit("map.yml",yaml->yaml.set(key,invalid)),key+"="+invalid);
            }
    }

    @Test void duplicateBorrowAndControlSlotsCannotOverwriteEachOthersActions() throws Exception {
        var edits=List.<Consumer<YamlConfiguration>>of(
                yaml->yaml.set("ui.lender.borrow-slots",List.of(10,10,12)),
                yaml->{yaml.set("ui.lender.borrow-slots",List.of(1,2,3));yaml.set("ui.lender.repay-slot",1);},
                yaml->{yaml.set("ui.lender.repay-slot",20);yaml.set("ui.lender.status-slot",20);},
                yaml->{yaml.set("ui.lender.status-slot",21);yaml.set("ui.lender.close-slot",21);});
        for(var edit:edits) {
            restore("config.yml");assertThrows(IllegalArgumentException.class,()->edit("config.yml",edit));
        }
    }

    @Test void slotsMustBeThreeDistinctIntegersInsideTheTwentySevenSlotMenu() throws Exception {
        for(var invalid:List.of(List.of(1,2),List.of(1,2,3,4),List.of(-1,2,3),List.of(1,2,27),List.of(1,2,2.5),List.of("1",2,3))) {
            restore("config.yml");
            assertThrows(IllegalArgumentException.class,()->edit("config.yml",yaml->yaml.set("ui.lender.borrow-slots",invalid)),invalid.toString());
        }
        for(String key:List.of("repay-slot","status-slot","close-slot"))for(int invalid:List.of(-1,27)) {
            restore("config.yml");
            assertThrows(IllegalArgumentException.class,()->edit("config.yml",yaml->yaml.set("ui.lender."+key,invalid)),key+"="+invalid);
        }
    }

    @Test void displayedAmountsUseConfiguredMinimumDefaultAndMaximumAndDeduplicateEqualValues() throws Exception {
        var varied=edit("config.yml",yaml->{yaml.set("loans.min-principal",100);yaml.set("loans.default-principal",200);yaml.set("loans.max-principal",300);});
        assertEquals(List.of(Market.cents("100"),Market.cents("200"),Market.cents("300")),TradingUi.loanChoices(varied));
        var equal=edit("config.yml",yaml->{yaml.set("loans.min-principal",100);yaml.set("loans.default-principal",100);yaml.set("loans.max-principal",100);});
        assertEquals(List.of(Market.cents("100")),TradingUi.loanChoices(equal));
        var defaultAtMax=edit("config.yml",yaml->{yaml.set("loans.default-principal",300);yaml.set("loans.max-principal",300);});
        assertEquals(List.of(Market.cents("100"),Market.cents("300")),TradingUi.loanChoices(defaultAtMax));
    }

    @Test void selectableAmountsCannotPlaceTheDefaultOutsideTheBorrowingLimits() throws Exception {
        for(var values:List.of(List.of(100,99,300),List.of(100,301,300),List.of(300,200,100),List.of(0,100,300))) {
            restore("config.yml");
            assertThrows(IllegalArgumentException.class,()->edit("config.yml",yaml->{
                yaml.set("loans.min-principal",values.get(0));yaml.set("loans.default-principal",values.get(1));yaml.set("loans.max-principal",values.get(2));
            }),values.toString());
        }
    }

    @Test void activeLoanChoicesClipToTheRemainingPrincipalLimitWithoutDuplicateButtons() throws Exception {
        var config=edit("config.yml",yaml->{
            yaml.set("loans.min-principal",10000);yaml.set("loans.default-principal",300000);
            yaml.set("loans.max-principal",3000000);yaml.set("loans.allow-top-up",true);
        });
        assertEquals(List.of(Market.cents("10000"),Market.cents("300000"),Market.cents("3000000")),TradingUi.loanChoices(config,0));
        assertEquals(List.of(Market.cents("10000"),Market.cents("300000"),Market.cents("500000")),TradingUi.loanChoices(config,Market.cents("2500000")));
        assertEquals(List.of(Market.cents("10000"),Market.cents("100000")),TradingUi.loanChoices(config,Market.cents("2900000")));
        assertEquals(List.of(Market.cents("10000")),TradingUi.loanChoices(config,Market.cents("2990000")));
    }

    @Test void lessThanTheMinimumRemainingLimitCannotOfferAnImpossibleLoan() throws Exception {
        var config=edit("config.yml",yaml->{
            yaml.set("loans.min-principal",10000);yaml.set("loans.default-principal",300000);
            yaml.set("loans.max-principal",3000000);yaml.set("loans.allow-top-up",true);
        });
        for(String outstanding:List.of("2990000.01","2995000","3000000","3000001"))
            assertTrue(TradingUi.loanChoices(config,Market.cents(outstanding)).isEmpty(),outstanding);
    }

    @Test void disablingTopUpsHidesNewBorrowingChoicesWhileTheOriginalPrincipalIsOutstanding() throws Exception {
        var config=edit("config.yml",yaml->yaml.set("loans.allow-top-up",false));
        assertFalse(TradingUi.loanChoices(config,0).isEmpty());
        assertTrue(TradingUi.loanChoices(config,1).isEmpty());
    }
}
