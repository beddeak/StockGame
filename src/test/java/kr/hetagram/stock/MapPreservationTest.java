package kr.hetagram.stock;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MapPreservationTest {
    @Test void completedMapSurvivesConfigHashAndTraderModeChangesWhenPreservingBuilds() {
        assertTrue(ExchangeMap.reuseCompletedMap("complete\nold-hash","complete\nnew-hash\ntraders:player",true));
        assertTrue(ExchangeMap.reuseCompletedMap("complete\nold-hash\ntraders:block","complete\nnew-hash\ntraders:player",true));
    }

    @Test void explicitRebuildModeRequiresTheWholePreviousSignatureToMatch() {
        String signature="complete\nnew-hash\ntraders:player";
        assertTrue(ExchangeMap.reuseCompletedMap(signature,signature,false));
        assertFalse(ExchangeMap.reuseCompletedMap("complete\nold-hash\ntraders:player",signature,false));
        assertFalse(ExchangeMap.reuseCompletedMap("complete\nnew-hash\ntraders:block",signature,false));
        assertFalse(ExchangeMap.reuseCompletedMap("complete\nnew-hash",signature,false));
    }

    @Test void missingOrUnfinishedMarkersAlwaysResumeConstruction() {
        for(boolean preserve:List.of(true,false))for(String marker:List.of("","building","building\nold-hash","complete","incomplete\nhash"))
            assertFalse(ExchangeMap.reuseCompletedMap(marker,"complete\ncurrent-hash",preserve),marker);
    }

    @Test void untouchedLegacyPedestalMayBeRemovedOnlyWhenEveryBlockMatchesItsExpectedMaterial() {
        assertTrue(ExchangeMap.isLegacyPedestal(List.of(Material.RED_CONCRETE,Material.RED_CONCRETE,Material.RED_CONCRETE,Material.RED_CONCRETE),Material.RED_CONCRETE));
        assertFalse(ExchangeMap.isLegacyPedestal(List.of(),Material.RED_CONCRETE));
        assertFalse(ExchangeMap.isLegacyPedestal(List.of(Material.RED_CONCRETE,Material.RED_CONCRETE,Material.RED_CONCRETE,Material.OAK_PLANKS),Material.RED_CONCRETE));
        assertFalse(ExchangeMap.isLegacyPedestal(List.of(Material.RED_CONCRETE,Material.RED_CONCRETE,Material.RED_CONCRETE,Material.AIR),Material.RED_CONCRETE));
    }

    @Test void otherCompanyAndPlayerBuiltMaterialsArePreserved() {
        assertFalse(ExchangeMap.isLegacyPedestal(List.of(Material.CYAN_CONCRETE,Material.CYAN_CONCRETE),Material.RED_CONCRETE));
        assertFalse(ExchangeMap.isLegacyPedestal(List.of(Material.STONE,Material.STONE,Material.STONE,Material.STONE),Material.RED_CONCRETE));
    }
}
