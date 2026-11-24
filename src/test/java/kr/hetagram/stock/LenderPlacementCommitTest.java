package kr.hetagram.stock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class LenderPlacementCommitTest {
    @TempDir Path directory;
    private final LenderSettings previous=new LenderSettings(true,"world",1,65,2,0,0,"OldSkin");
    private final LenderSettings next=new LenderSettings(true,"world",30,66,40,90,0,"NewSkin");

    @Test void successfulPlacementIsAppliedBeforeItIsPersisted() throws Exception {
        LenderSettings.save(directory.toFile(),previous);
        var active=new AtomicReference<>(previous);
        LenderSettings.applyAndSave(directory.toFile(),previous,next,value->{
            try {assertEquals(previous,LenderSettings.load(directory.toFile()));}
            catch(Exception failure){throw new RuntimeException(failure);}
            active.set(value);
        });
        assertEquals(next,active.get());assertEquals(next,LenderSettings.load(directory.toFile()));
    }

    @Test void failedNpcMoveRestoresRuntimeAndLeavesSavedLocationUnchanged() throws Exception {
        LenderSettings.save(directory.toFile(),previous);byte[] original=Files.readAllBytes(directory.resolve("lender.yml"));
        var active=new AtomicReference<>(previous);var cancelled=new IllegalStateException("teleport cancelled");
        assertSame(cancelled,assertThrows(IllegalStateException.class,()->
                LenderSettings.applyAndSave(directory.toFile(),previous,next,value->{
                    active.set(value);if(value==next)throw cancelled;
                })));
        assertEquals(previous,active.get());assertArrayEquals(original,Files.readAllBytes(directory.resolve("lender.yml")));
    }

    @Test void diskFailureRestoresNpcAndOriginalSavedLocation() throws Exception {
        LenderSettings.save(directory.toFile(),previous);byte[] original=Files.readAllBytes(directory.resolve("lender.yml"));
        Path lockedBackup=Files.createDirectory(directory.resolve("lender.yml.bak"));Files.writeString(lockedBackup.resolve("keep.txt"),"keep");
        var active=new AtomicReference<>(previous);
        assertThrows(java.io.IOException.class,()->LenderSettings.applyAndSave(directory.toFile(),previous,next,active::set));
        assertEquals(previous,active.get());assertArrayEquals(original,Files.readAllBytes(directory.resolve("lender.yml")));
        assertFalse(Files.exists(directory.resolve("lender.yml.tmp")));
    }

    @Test void rollbackFailureDoesNotHideTheOriginalMoveFailure() throws Exception {
        LenderSettings.save(directory.toFile(),previous);byte[] original=Files.readAllBytes(directory.resolve("lender.yml"));
        var moveFailure=new IllegalStateException("move failed");var rollbackFailure=new IllegalArgumentException("restore failed");
        var thrown=assertThrows(IllegalStateException.class,()->LenderSettings.applyAndSave(directory.toFile(),previous,next,value->{
            if(value==next)throw moveFailure;throw rollbackFailure;
        }));
        assertSame(moveFailure,thrown);assertArrayEquals(new Throwable[]{rollbackFailure},thrown.getSuppressed());
        assertArrayEquals(original,Files.readAllBytes(directory.resolve("lender.yml")));
    }
}
