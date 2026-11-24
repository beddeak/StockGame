package kr.hetagram.stock;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TickerRowsTest {
    @Test void restoredHistoryShowsOnlyNewestRowsAndKeepsNewestAtBottom() {
        var ticker=new TickerRows<Object>(3);var owner=new Object();
        var history=List.of(new Object(),new Object(),new Object(),new Object());
        var frame=ticker.update(owner,history);
        assertTrue(frame.changed());assertTrue(frame.snap());assertEquals(5,ticker.poolSize());
        assertSame(history.get(1),frame.rows().get(0).value());assertEquals(2,frame.rows().get(0).toLevel());
        assertSame(history.get(3),frame.rows().get(2).value());assertEquals(0,frame.rows().get(2).toLevel());
    }

    @Test void appendedPriceKeepsExistingSlotsAndPushesThemUpOneLevel() {
        var ticker=new TickerRows<Object>(3);var owner=new Object();var start=new Object();var next=new Object();
        var initial=ticker.update(owner,List.of(start));int startSlot=initial.rows().getFirst().slot();
        var frame=ticker.update(owner,List.of(start,next));
        assertTrue(frame.changed());assertFalse(frame.snap());assertTrue(frame.exits().isEmpty());
        var old=frame.rows().getFirst();assertEquals(startSlot,old.slot());assertFalse(old.entering());
        assertEquals(0,old.fromLevel());assertEquals(1,old.toLevel());
        var entering=frame.rows().getLast();assertTrue(entering.entering());assertEquals(-1,entering.fromLevel());assertEquals(0,entering.toLevel());
    }

    @Test void equalPriceCandleObjectsStillProduceANewRowInAFullHistory() {
        var ticker=new TickerRows<Market.Candle>(2);var owner=new Object();
        var first=new Market.Candle(100,100,100,100);var second=new Market.Candle(100,100,100,100);var third=new Market.Candle(100,100,100,100);
        assertEquals(first,second);assertNotSame(first,second);
        var initial=ticker.update(owner,List.of(first,second));int oldSecondSlot=initial.rows().getLast().slot();
        var frame=ticker.update(owner,List.of(second,third));
        assertTrue(frame.changed());assertFalse(frame.snap());assertEquals(oldSecondSlot,frame.rows().getFirst().slot());
        assertSame(third,frame.rows().getLast().value());assertTrue(frame.rows().getLast().entering());
        assertEquals(1,frame.exits().size());assertNotEquals(frame.exits().getFirst().slot(),frame.rows().getLast().slot());
    }

    @Test void repeatedRefreshDoesNotRestartAnAnimationOrLoseTheCurrentSlots() {
        var ticker=new TickerRows<Object>(3);var owner=new Object();var history=List.of(new Object(),new Object());
        var first=ticker.update(owner,history);var again=ticker.update(owner,new ArrayList<>(history));
        assertFalse(again.changed());assertFalse(again.snap());assertTrue(again.exits().isEmpty());
        assertEquals(first.rows().stream().map(TickerRows.Row::slot).toList(),again.rows().stream().map(TickerRows.Row::slot).toList());
        assertTrue(again.rows().stream().noneMatch(TickerRows.Row::entering));
    }

    @Test void resetOwnerSnapsInsteadOfAnimatingOldRoundPricesIntoTheNewRound() {
        var ticker=new TickerRows<Object>(3);var oldOwner=new Object();var newOwner=new Object();
        ticker.update(oldOwner,List.of(new Object(),new Object(),new Object()));
        var value=new Object();var frame=ticker.update(newOwner,List.of(value));
        assertTrue(frame.snap());assertTrue(frame.changed());assertEquals(1,frame.rows().size());
        assertSame(value,frame.rows().getFirst().value());assertEquals(0,frame.rows().getFirst().toLevel());
        assertTrue(frame.exits().isEmpty());
    }

    @Test void fastRingBufferScrollingIsBoundedAndLeavesLastExitingSlotToPark() {
        var ticker=new TickerRows<Object>(4);var owner=new Object();var history=new ArrayList<Object>();
        for(int i=0;i<4;i++)history.add(new Object());ticker.update(owner,history);
        Set<Integer> lastExits=Set.of();var usedEver=new HashSet<Integer>();
        for(int i=0;i<200;i++) {
            history.removeFirst();history.add(new Object());var frame=ticker.update(owner,history);
            assertFalse(frame.snap());assertEquals(4,frame.rows().size());assertEquals(1,frame.exits().size());
            var slots=new HashSet<Integer>();
            for(var row:frame.rows()){assertTrue(slots.add(row.slot()));assertTrue(row.slot()>=0&&row.slot()<ticker.poolSize());usedEver.add(row.slot());}
            int entering=frame.rows().getLast().slot();assertFalse(lastExits.contains(entering),"퇴장 직후의 칸은 숨겨 주차할 틱을 남겨야 합니다");
            for(var exit:frame.exits())assertFalse(slots.contains(exit.slot()));
            lastExits=Set.of(frame.exits().getFirst().slot());
        }
        assertTrue(usedEver.size()<=6);
    }

    @Test void multipleCandlesInOneTickUseDistinctSlotsAndRetireOnlyUnusedCells() {
        var ticker=new TickerRows<Object>(4);var owner=new Object();var a=new Object();var b=new Object();var c=new Object();var d=new Object();
        ticker.update(owner,List.of(a,b,c,d));var frame=ticker.update(owner,List.of(c,d,new Object(),new Object()));
        assertFalse(frame.snap());assertEquals(2,frame.rows().stream().filter(TickerRows.Row::entering).count());
        var slots=new HashSet<Integer>();for(var row:frame.rows())assertTrue(slots.add(row.slot()));
        for(var exit:frame.exits())assertFalse(slots.contains(exit.slot()));assertEquals(2,frame.exits().size());
    }

    @Test void CompletelyReplacedHistorySnapsToCurrentValuesWithoutGrowingThePool() {
        var ticker=new TickerRows<Object>(2);var owner=new Object();ticker.update(owner,List.of(new Object(),new Object()));
        var newest=List.of(new Object(),new Object());var frame=ticker.update(owner,newest);
        assertTrue(frame.snap());assertEquals(2,frame.rows().size());assertSame(newest.getLast(),frame.rows().getLast().value());
        assertEquals(4,ticker.poolSize());
    }
}
