package kr.hetagram.stock;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class StatePersistenceTest {
    @TempDir Path directory;
    private Market market;private StateStore store;
    private final Queue<Runnable> callbacks=new ConcurrentLinkedQueue<>();
    private final List<Exception> fatal=new CopyOnWriteArrayList<>(),statusErrors=new CopyOnWriteArrayList<>();
    private final List<Long> delays=new CopyOnWriteArrayList<>();
    private final AtomicLong wall=new AtomicLong();
    private static final class Disk implements StatePersistence.Storage {
        final List<String> states=new CopyOnWriteArrayList<>(),statuses=new CopyOnWriteArrayList<>(),threads=new CopyOnWriteArrayList<>();
        final Semaphore completed=new Semaphore(0),statusCompleted=new Semaphore(0);
        final CountDownLatch firstEntered=new CountDownLatch(1),releaseFirst=new CountDownLatch(1);
        final AtomicInteger failures=new AtomicInteger(),stateAttempts=new AtomicInteger(),statusAttempts=new AtomicInteger(),backups=new AtomicInteger();
        volatile boolean blockFirst,failState,failStatus,failBackup;
        @Override public void writeSnapshot(String text) throws Exception {
            int attempt=stateAttempts.incrementAndGet();threads.add(Thread.currentThread().getName());
            if(blockFirst&&attempt==1){firstEntered.countDown();if(!releaseFirst.await(5,TimeUnit.SECONDS))throw new IOException("test blocker timeout");}
            if(failState||failures.getAndUpdate(value->Math.max(0,value-1))>0)throw new IOException("Windows file temporarily locked");
            states.add(text);completed.release();
        }
        @Override public void writeStatus(String text) throws Exception {statusAttempts.incrementAndGet();if(failStatus)throw new IOException("status locked");statuses.add(text);statusCompleted.release();}
        @Override public Path backupCurrentState(String purpose,int retention) throws Exception {backups.incrementAndGet();if(failBackup)throw new IOException("backup locked");return Path.of(purpose+"-complete.yml");}
    }
    @BeforeEach void setup() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml"))
            try(var stream=getClass().getResourceAsStream("/"+name)){Files.copy(Objects.requireNonNull(stream),directory.resolve(name));}
        market=new Market(GameConfig.load(directory.toFile()));store=new StateStore(directory.toFile());
    }
    private StatePersistence coordinator(Disk disk,int backupSeconds) {
        return new StatePersistence(store,disk,new StatePersistence.Options(20,4,75,backupSeconds,12),callbacks::add,fatal::add,statusErrors::add,wall::get,delays::add);
    }
    private void ticks(StatePersistence persistence,int count) {for(int i=0;i<count;i++)persistence.tick(market);}
    private void callbacks() {Runnable callback;while((callback=callbacks.poll())!=null)callback.run();}
    private void close(StatePersistence persistence,Disk disk) {disk.releaseFirst.countDown();try{persistence.close(market);}catch(Exception ignored){/* failure tests explicitly assert the critical error */}}
    private static YamlConfiguration yaml(String text) throws Exception {var yaml=new YamlConfiguration();yaml.loadFromString(text);return yaml;}
    private static void waiting(Thread thread) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(thread.isAlive()&&thread.getState()!=Thread.State.WAITING&&System.nanoTime()<until)Thread.sleep(1);
        assertEquals(Thread.State.WAITING,thread.getState(),"critical caller must have queued its write before releasing the old writer");
    }

    @Test void ordinaryTicksCoalesceImmutableStateAndAdminStatusAtMostOncePerSecond() throws Exception {
        var disk=new Disk();var persistence=coordinator(disk,0);disk.blockFirst=true;
        try {
            for(int i=0;i<19;i++){persistence.markDirty();persistence.status("status-"+i);ticks(persistence,1);}
            assertEquals(0,disk.stateAttempts.get());ticks(persistence,1);assertTrue(disk.firstEntered.await(5,TimeUnit.SECONDS));
            for(int i=1;i<=100;i++){market.clockMillis=i;persistence.markDirty();persistence.status("latest-"+i);ticks(persistence,20);}
            market.clockMillis=9999;disk.releaseFirst.countDown();assertTrue(disk.completed.tryAcquire(2,5,TimeUnit.SECONDS));assertTrue(disk.statusCompleted.tryAcquire(2,5,TimeUnit.SECONDS));
            assertEquals(2,disk.states.size());assertEquals(0,yaml(disk.states.getFirst()).getLong("clock"));assertEquals(100,yaml(disk.states.getLast()).getLong("clock"));
            assertEquals("latest-100",disk.statuses.getLast());assertTrue(disk.threads.stream().allMatch("StockGame-state-writer"::equals));
        } finally {close(persistence,disk);}
    }
    @Test void transientWindowsFailuresRetryOnTheWorkerWithoutPausingTheMarket() throws Exception {
        var disk=new Disk();disk.failures.set(2);var persistence=coordinator(disk,0);
        try {ticks(persistence,20);assertTrue(disk.completed.tryAcquire(5,TimeUnit.SECONDS));assertEquals(3,disk.stateAttempts.get());assertEquals(List.of(75L,150L),delays);assertFalse(persistence.hasFatalFailure());callbacks();assertTrue(fatal.isEmpty());}
        finally {close(persistence,disk);}
    }
    @Test void exhaustedStateFailureBlocksFinancialGuardAndCallsMainDispatcherOnlyOnce() throws Exception {
        var disk=new Disk();disk.failState=true;var persistence=coordinator(disk,0);
        try {
            assertThrows(IOException.class,()->persistence.flushNow(market));assertEquals(4,disk.stateAttempts.get());assertTrue(persistence.hasFatalFailure());assertTrue(fatal.isEmpty());
            var settlements=new AtomicInteger();assertThrows(IllegalArgumentException.class,()->{persistence.requireHealthy();settlements.incrementAndGet();});assertEquals(0,settlements.get());
            callbacks();assertEquals(1,fatal.size());assertTrue(statusErrors.isEmpty());
            disk.failState=false;persistence.flushNow(market);assertFalse(persistence.hasFatalFailure());assertDoesNotThrow(persistence::requireHealthy);
        } finally {close(persistence,disk);}
    }
    @Test void statusFailureDoesNotCloseTheMarketOrRejectPayments() throws Exception {
        var disk=new Disk();disk.failStatus=true;var persistence=coordinator(disk,0);
        try {
            persistence.status("admin snapshot");persistence.flushNow(market);assertEquals(4,disk.statusAttempts.get());assertFalse(persistence.hasFatalFailure());assertDoesNotThrow(persistence::requireHealthy);
            callbacks();assertEquals(1,statusErrors.size());assertTrue(fatal.isEmpty());
        } finally {close(persistence,disk);}
    }
    @Test void criticalPauseSnapshotCannotBeOverwrittenByOlderCoalescedRunningState() throws Exception {
        var disk=new Disk();disk.blockFirst=true;var persistence=coordinator(disk,0);var problem=new AtomicReference<Throwable>();
        try {
            market.running=true;ticks(persistence,20);assertTrue(disk.firstEntered.await(5,TimeUnit.SECONDS));
            market.clockMillis=100;persistence.markDirty();ticks(persistence,20);market.running=false;
            var critical=new Thread(()->{try{persistence.flushNow(market);}catch(Throwable failure){problem.set(failure);}});critical.start();waiting(critical);
            disk.releaseFirst.countDown();critical.join(5000);assertFalse(critical.isAlive());assertNull(problem.get());persistence.close(market);
            assertFalse(yaml(disk.states.getLast()).getBoolean("running"));assertEquals(100,yaml(disk.states.getLast()).getLong("clock"));
            assertTrue(disk.states.subList(1,disk.states.size()).stream().allMatch(text->{try{return !yaml(text).getBoolean("running");}catch(Exception failure){return false;}}));
        } finally {close(persistence,disk);}
    }
    @Test void resetGenerationAndCriticalBackupAreNewestAndTheBackupCompletesBeforeReturn() throws Exception {
        var disk=new Disk();var persistence=coordinator(disk,0);
        try {
            UUID player=UUID.randomUUID();market.account(player).cash=12345;
            assertEquals(Path.of("before-reset-complete.yml"),persistence.backupBeforeReset(market));assertEquals(1,disk.backups.get());assertTrue(yaml(disk.states.getLast()).contains("accounts."+player));
            market.reset();persistence.flushNow(market);assertEquals(1,yaml(disk.states.getLast()).getLong("reset-generation"));assertFalse(yaml(disk.states.getLast()).isConfigurationSection("accounts."+player));
        } finally {close(persistence,disk);}
    }
    @Test void failedCriticalBackupDoesNotAuthorizeResetAndKeepsFinancialGuardClosed() throws Exception {
        var disk=new Disk();disk.failBackup=true;var persistence=coordinator(disk,0);long generation=market.resetGeneration;
        try {assertThrows(IOException.class,()->persistence.backupBeforeReset(market));assertEquals(4,disk.backups.get());assertEquals(generation,market.resetGeneration);assertTrue(persistence.hasFatalFailure());assertThrows(IllegalArgumentException.class,persistence::requireHealthy);callbacks();assertEquals(1,fatal.size());}
        finally {close(persistence,disk);}
    }
    @Test void periodicBackupsUseElapsedWallTimeAndDoNotReplaceThePreviousStateBackup() throws Exception {
        var disk=new Disk();var persistence=coordinator(disk,300);
        try {
            persistence.flushNow(market);assertEquals(0,disk.backups.get());wall.set(299999);persistence.flushNow(market);assertEquals(0,disk.backups.get());
            wall.set(300000);persistence.flushNow(market);assertEquals(1,disk.backups.get());persistence.flushNow(market);assertEquals(1,disk.backups.get());
        } finally {close(persistence,disk);}
        store.save(market);String before=Files.readString(directory.resolve("state.yml"));market.clockMillis=50;store.save(market);
        assertEquals(before,Files.readString(directory.resolve("state.yml.bak")));Path reset=store.backupBeforeReset(market);String previousBackup=Files.readString(directory.resolve("state.yml.bak"));
        for(int i=0;i<4;i++)store.backupCurrentState("periodic",2);
        try(var files=Files.list(directory.resolve("backups/periodic"))) {var backups=files.toList();assertEquals(2,backups.size());for(Path backup:backups){assertTrue(backup.getFileName().toString().endsWith(".yml"));assertEquals(Files.readString(directory.resolve("state.yml")),Files.readString(backup));}}
        assertTrue(Files.exists(reset));assertEquals(previousBackup,Files.readString(directory.resolve("state.yml.bak")));
        assertFalse(Files.exists(directory.resolve("state.yml.tmp")));assertFalse(Files.exists(directory.resolve("state.yml.bak.tmp")));
        for(int i=0;i<4;i++){Path latest=store.backupCurrentState("periodic",1);assertTrue(Files.exists(latest),"the just-created backup must survive even when timestamp/mtime ties");}
    }
    @Test void periodicBackupFailureOnlyWarnsBecauseTheFinancialStateWasAlreadyStored() throws Exception {
        var disk=new Disk();disk.failBackup=true;var persistence=coordinator(disk,300);
        try {
            wall.set(300000);assertDoesNotThrow(()->persistence.flushNow(market));assertEquals(1,disk.states.size());assertEquals(4,disk.backups.get());
            assertFalse(persistence.hasFatalFailure());assertDoesNotThrow(persistence::requireHealthy);callbacks();assertTrue(fatal.isEmpty());assertEquals(1,statusErrors.size());
        }finally {close(persistence,disk);}
    }
    @Test void captureFailureKeepsDirtyAndLocksPaymentsUntilANewerValidSnapshotSucceeds() throws Exception {
        var disk=new Disk();var persistence=coordinator(disk,0);UUID damaged=UUID.randomUUID();market.accounts.put(damaged,null);
        try {
            assertDoesNotThrow(()->ticks(persistence,20));assertTrue(persistence.hasFatalFailure());assertEquals(0,disk.stateAttempts.get());callbacks();assertEquals(1,fatal.size());
            assertThrows(IllegalArgumentException.class,persistence::requireHealthy);market.accounts.remove(damaged);
            ticks(persistence,20);assertTrue(disk.completed.tryAcquire(5,TimeUnit.SECONDS));persistence.flushNow(market);assertFalse(persistence.hasFatalFailure());
        }finally {market.accounts.remove(damaged);close(persistence,disk);}
    }
    @Test void oldWriteCompletingAfterCaptureFailureCannotClearTheFinancialLock() throws Exception {
        var disk=new Disk();disk.blockFirst=true;var persistence=coordinator(disk,0);UUID damaged=UUID.randomUUID();
        try {
            ticks(persistence,20);assertTrue(disk.firstEntered.await(5,TimeUnit.SECONDS));market.accounts.put(damaged,null);persistence.markDirty();persistence.status("barrier");
            ticks(persistence,20);assertTrue(persistence.hasFatalFailure());disk.releaseFirst.countDown();assertTrue(disk.completed.tryAcquire(5,TimeUnit.SECONDS));assertTrue(disk.statusCompleted.tryAcquire(5,TimeUnit.SECONDS));
            callbacks();assertEquals(1,fatal.size());assertThrows(IllegalArgumentException.class,persistence::requireHealthy);
            market.accounts.remove(damaged);persistence.flushNow(market);assertFalse(persistence.hasFatalFailure());
        }finally {market.accounts.remove(damaged);close(persistence,disk);}
    }
}
