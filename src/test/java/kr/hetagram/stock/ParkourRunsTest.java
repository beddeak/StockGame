package kr.hetagram.stock;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ParkourRunsTest {
    private final UUID player=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID other=UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final ParkourRuns runs=new ParkourRuns(5,600);

    private void approachFinish(UUID id,long generation,long start) {
        runs.start(id,generation,start);
        assertEquals(ParkourRuns.Landing.PROGRESSED,runs.land(id,generation,1,start+100));
        assertEquals(ParkourRuns.Landing.PROGRESSED,runs.land(id,generation,2,start+200));
        assertEquals(ParkourRuns.Landing.PROGRESSED,runs.land(id,generation,3,start+300));
    }

    @Test void orderedLandingsAllowExactlyOneClaimAtTheMinimumTime() {
        approachFinish(player,4,1000);
        assertFalse(runs.ready(player,4,1599));
        assertEquals(ParkourRuns.Landing.READY,runs.land(player,4,4,1600));
        assertEquals(5,runs.nextIndex(player));assertTrue(runs.ready(player,4,1600));
        assertTrue(runs.complete(player,4,1600));assertEquals(-1,runs.nextIndex(player));
        assertFalse(runs.complete(player,4,1600));assertFalse(runs.ready(player,4,2000));
        assertEquals(ParkourRuns.Landing.IGNORED,runs.land(player,4,4,2000));
    }

    @Test void skippingDirectlyToTheFinishCannotAdvanceOrClaim() {
        runs.start(player,0,0);
        assertEquals(ParkourRuns.Landing.IGNORED,runs.land(player,0,4,600));
        assertEquals(1,runs.nextIndex(player));assertFalse(runs.complete(player,0,600));
        assertEquals(ParkourRuns.Landing.PROGRESSED,runs.land(player,0,1,610));
        assertEquals(ParkourRuns.Landing.IGNORED,runs.land(player,0,3,620));
        assertEquals(2,runs.nextIndex(player));assertFalse(runs.ready(player,0,2000));
    }

    @Test void RevisitedPlatformsAndCheckpointReturnKeepOnlyTheNextRequiredIndex() {
        approachFinish(player,0,0);
        for(int index:new int[]{3,2,1,0,1,2,3}) {
            assertEquals(ParkourRuns.Landing.IGNORED,runs.land(player,0,index,400));
            assertEquals(4,runs.nextIndex(player));
        }
        assertEquals(ParkourRuns.Landing.READY,runs.land(player,0,4,600));
    }

    @Test void playersCannotAdvanceOrConsumeEachOthersRun() {
        approachFinish(player,1,0);runs.start(other,1,0);
        assertEquals(ParkourRuns.Landing.READY,runs.land(player,1,4,600));
        assertEquals(ParkourRuns.Landing.IGNORED,runs.land(other,1,4,600));
        assertTrue(runs.complete(player,1,600));assertFalse(runs.complete(other,1,600));
        assertEquals(1,runs.nextIndex(other));
    }

    @Test void aNewRoundDiscardsTheOldSessionBeforeLandingOrClaim() {
        approachFinish(player,1,0);
        assertEquals(ParkourRuns.Landing.EXPIRED,runs.land(player,2,4,600));
        assertEquals(-1,runs.nextIndex(player));
        approachFinish(other,1,0);assertEquals(ParkourRuns.Landing.READY,runs.land(other,1,4,600));
        assertFalse(runs.complete(other,2,600));assertEquals(-1,runs.nextIndex(other));
    }

    @Test void tooFastFinishDiscardsTheRunAndWaitingOnTheFinishCannotRepairIt() {
        approachFinish(player,0,0);
        assertEquals(ParkourRuns.Landing.TOO_FAST,runs.land(player,0,4,599));
        assertEquals(-1,runs.nextIndex(player));assertFalse(runs.ready(player,0,600));
        assertEquals(ParkourRuns.Landing.IGNORED,runs.land(player,0,4,10000));
        assertFalse(runs.complete(player,0,10000));
    }

    @Test void checkingOrCompletingAnUnfinishedRunHasNoProgressSideEffect() {
        runs.start(player,0,100);
        assertFalse(runs.ready(player,0,1000));assertFalse(runs.complete(player,0,1000));
        assertEquals(1,runs.nextIndex(player));
        // A failed financial validation can leave a ready run available for the same player.
        approachFinish(player,0,100);
        assertEquals(ParkourRuns.Landing.READY,runs.land(player,0,4,700));
        assertTrue(runs.ready(player,0,700));assertTrue(runs.ready(player,0,701));
        assertEquals(ParkourRuns.Landing.READY,runs.land(player,0,4,701));
        assertTrue(runs.complete(player,0,701));
    }

    @Test void cancelForLogoutDeathTeleportOrFlightMakesTheOldRunUnclaimable() {
        approachFinish(player,0,0);assertTrue(runs.cancel(player));assertFalse(runs.cancel(player));
        assertEquals(-1,runs.nextIndex(player));assertFalse(runs.complete(player,0,600));
        approachFinish(player,0,1000);assertEquals(ParkourRuns.Landing.READY,runs.land(player,0,4,1600));
        runs.clear();assertFalse(runs.complete(player,0,1600));
    }

    @Test void restartingAtTheStartUsesANewStartTime() {
        approachFinish(player,0,0);runs.start(player,0,500);
        assertEquals(1,runs.nextIndex(player));
        runs.land(player,0,1,600);runs.land(player,0,2,700);runs.land(player,0,3,800);
        assertEquals(ParkourRuns.Landing.TOO_FAST,runs.land(player,0,4,1000));
        assertFalse(runs.ready(player,0,1100));
    }

    @Test void backwardsTicksCannotCompleteAStaleServerSession() {
        approachFinish(player,0,1000);
        assertEquals(ParkourRuns.Landing.EXPIRED,runs.land(player,0,4,1299));
        assertEquals(-1,runs.nextIndex(player));
        approachFinish(player,0,2000);assertEquals(ParkourRuns.Landing.READY,runs.land(player,0,4,2600));
        assertFalse(runs.complete(player,0,2599));assertEquals(-1,runs.nextIndex(player));
    }

    @Test void invalidInputsDoNotReplaceOrAdvanceAValidRun() {
        assertThrows(IllegalArgumentException.class,()->new ParkourRuns(1,600));
        assertThrows(IllegalArgumentException.class,()->new ParkourRuns(5,-1));
        runs.start(player,0,100);
        assertThrows(IllegalArgumentException.class,()->runs.start(player,-1,100));
        assertThrows(IllegalArgumentException.class,()->runs.start(player,0,-1));
        assertThrows(IllegalArgumentException.class,()->runs.land(player,0,5,200));
        assertThrows(IllegalArgumentException.class,()->runs.land(player,0,-1,200));
        assertEquals(1,runs.nextIndex(player));assertEquals(5,runs.platformCount());
    }
}
