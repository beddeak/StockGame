package kr.hetagram.stock;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoundResourcePolicyTest {
    @Test void firstInstallationAcceptsOnlyItsLegacyBaselineAndTheCurrentStampedRound() {
        long firstInstalledRound=7;
        assertTrue(RoundResources.currentRound(null,7,firstInstalledRound,true));
        assertTrue(RoundResources.currentRound(7L,7,firstInstalledRound,true));
        assertFalse(RoundResources.currentRound(6L,7,firstInstalledRound,true));
        assertFalse(RoundResources.currentRound(8L,7,firstInstalledRound,true));
        assertFalse(RoundResources.obsoleteRound(null,true,7,firstInstalledRound));
    }
    @Test void resetAndEveryLaterRestartKeepTheOriginalBaselineSoStoredOldResourcesNeverBecomeCurrent() {
        // 상자/오프라인 인벤토리도 같은 태그/라운드 입력을 사용하므로 보관 위치로 우회하지 못합니다.
        long persistedLegacyRound=7;
        for(long current: new long[]{8,8,9,9,100}) {
            assertFalse(RoundResources.currentRound(null,current,persistedLegacyRound,true));
            assertFalse(RoundResources.currentRound(7L,current,persistedLegacyRound,true));
            assertTrue(RoundResources.obsoleteRound(null,true,current,persistedLegacyRound));
            assertTrue(RoundResources.obsoleteRound(7L,true,current,persistedLegacyRound));
            assertTrue(RoundResources.currentRound(current,current,persistedLegacyRound,true));
            assertFalse(RoundResources.obsoleteRound(current,true,current,persistedLegacyRound));
        }
    }
    @Test void ordinaryCustomMetaIsPreservedWhileExplicitStrictOptOutAllowsOldResourceSales() {
        assertFalse(RoundResources.obsoleteRound(null,false,8,7),"이름/인챈트 등 일반 커스텀 아이템을 초기화 정리로 삭제하지 않음");
        // 플러그인이 만든 구 라운드 태그 자원은 이후 이름/인챈트를 바꿔도 초기화 대상입니다.
        assertTrue(RoundResources.obsoleteRound(7L,false,8,7));
        assertFalse(RoundResources.obsoleteRound(8L,false,8,7));
        assertTrue(RoundResources.obsoleteRound(7L,true,8,7));
        assertTrue(RoundResources.currentRound(null,8,7,false));
        assertTrue(RoundResources.currentRound(7L,8,7,false));
        assertTrue(RoundResources.currentRound(8L,8,7,false));
    }
}
