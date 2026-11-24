package kr.hetagram.stock;

import org.bukkit.configuration.ConfigurationSection;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** main에서 불변 문자열을 만들고 단일 worker에서만 디스크에 씁니다. 일상 저장은 결제를 기다리지 않습니다. */
public final class StatePersistence {
    public record Options(int flushIntervalTicks,int retryAttempts,int retryDelayMillis,int backupIntervalSeconds,int backupRetention) {
        public Options {
            GameConfig.require(flushIntervalTicks>=20&&flushIntervalTicks<=1200,"저장 간격은 20~1200틱입니다");
            GameConfig.require(retryAttempts>=1&&retryAttempts<=10,"저장 재시도는 1~10회입니다");
            GameConfig.require(retryDelayMillis>=0&&retryDelayMillis<=5000,"저장 재시도 대기는 0~5000ms입니다");
            GameConfig.require(backupIntervalSeconds>=0&&backupIntervalSeconds<=86400,"주기 백업은 0~86400초입니다");
            GameConfig.require(backupRetention>=1&&backupRetention<=1000,"주기 백업 보관은 1~1000개입니다");
        }
        public static Options from(ConfigurationSection yaml) {
            return new Options(value(yaml,"flush-interval-ticks",20,20,1200),value(yaml,"retry-attempts",4,1,10),
                    value(yaml,"retry-delay-millis",75,0,5000),value(yaml,"backup-interval-seconds",300,0,86400),value(yaml,"backup-retention",12,1,1000));
        }
        private static int value(ConfigurationSection yaml,String key,int fallback,int min,int max) {
            String path="persistence."+key;return yaml.isSet(path)?GameConfig.integer(yaml,path,min,max):fallback;
        }
    }
    public interface Storage {
        void writeSnapshot(String snapshot) throws Exception;
        void writeStatus(String snapshot) throws Exception;
        Path backupCurrentState(String purpose,int retention) throws Exception;
    }
    @FunctionalInterface interface Sleeper {void sleep(long millis) throws InterruptedException;}
    private record Snapshot(long sequence,long generation,String text) {}
    private record Status(long sequence,String text) {}
    private final StateStore store;
    private final Storage storage;
    private final Consumer<Runnable> dispatchMain;
    private final Consumer<Exception> stateFailure,statusFailure;
    private final LongSupplier wallClock;
    private final Sleeper sleeper;
    private final ExecutorService worker;
    private final Object lock=new Object();
    private volatile Options options;
    private volatile Exception fatalFailure;
    private boolean dirty=true,closed,normalScheduled,statusFailed;
    private long sequence,ticks,latestGeneration,lastStateSequence,lastStatusSequence,lastPeriodicBackup,failureSequence;
    private Snapshot pendingState;
    private Status pendingStatus;
    private String mainStatus;

    public StatePersistence(StateStore store,Options options,Consumer<Runnable> dispatchMain,Consumer<Exception> stateFailure,Consumer<Exception> statusFailure) {
        this(store,store,options,dispatchMain,stateFailure,statusFailure,System::currentTimeMillis,Thread::sleep);
    }
    StatePersistence(StateStore store,Storage storage,Options options,Consumer<Runnable> dispatchMain,Consumer<Exception> stateFailure,
                     Consumer<Exception> statusFailure,LongSupplier wallClock,Sleeper sleeper) {
        this.store=Objects.requireNonNull(store);this.storage=Objects.requireNonNull(storage);this.options=Objects.requireNonNull(options);
        this.dispatchMain=Objects.requireNonNull(dispatchMain);this.stateFailure=Objects.requireNonNull(stateFailure);this.statusFailure=Objects.requireNonNull(statusFailure);
        this.wallClock=wallClock;this.sleeper=sleeper;lastPeriodicBackup=wallClock.getAsLong();
        worker=Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"StockGame-state-writer");thread.setDaemon(true);return thread;});
    }
    public void reconfigure(Options options) {this.options=Objects.requireNonNull(options);}
    public boolean hasFatalFailure() {return fatalFailure!=null;}
    public void requireHealthy() {GameConfig.require(!hasFatalFailure(),"상태 저장에 실패해 결제가 중지되었습니다. 관리자에게 알려주세요");}
    public void markDirty() {synchronized(lock){if(!closed)dirty=true;}}
    /** JSON 생성도 main에서 끝내고 이 메서드에는 문자열만 전달합니다. */
    public void status(String json) {synchronized(lock){if(!closed)mainStatus=Objects.requireNonNull(json);}}
    /** 매 서버 틱 main에서 호출합니다. 정상 상태/관리자 상태 모두 설정 주기보다 자주 쓰지 않습니다. */
    public void tick(Market market) {
        boolean capture;String status;
        synchronized(lock) {
            if(closed||++ticks%options.flushIntervalTicks()!=0)return;
            capture=dirty;dirty=false;status=mainStatus;mainStatus=null;
        }
        Snapshot snapshot=null;
        if(capture)try {snapshot=snapshot(market);}catch(RuntimeException failure) {synchronized(lock){dirty=true;}failState(failure);}
        synchronized(lock) {
            if(closed)return;if(snapshot!=null)pendingState=snapshot;
            if(status!=null)pendingStatus=new Status(nextSequence(),status);
            if(!normalScheduled&&(pendingState!=null||pendingStatus!=null)){normalScheduled=true;worker.execute(this::drain);}
        }
    }
    private Snapshot snapshot(Market market) {
        String immutable=store.capture(market);
        synchronized(lock){latestGeneration=Math.max(latestGeneration,market.resetGeneration);return new Snapshot(nextSequence(),market.resetGeneration,immutable);}
    }
    private long nextSequence() {sequence=Math.addExact(sequence,1);return sequence;}
    private void drain() {
        Snapshot state;Status status;
        synchronized(lock){state=pendingState;status=pendingStatus;pendingState=null;pendingStatus=null;}
        try {if(state!=null)try{writeState(state,false);}catch(Exception failure){failState(failure);}if(status!=null)writeAdminStatus(status);}
        finally {
            synchronized(lock) {
                if(!closed&&(pendingState!=null||pendingStatus!=null))worker.execute(this::drain);
                else normalScheduled=false;
            }
        }
    }
    private void writeState(Snapshot state,boolean critical) throws Exception {
        synchronized(lock){if(state.sequence<=lastStateSequence||!critical&&state.generation<latestGeneration)return;}
        retry(()->{storage.writeSnapshot(state.text);return null;});
        // critical 뒤에 대기하던 옛 스냅샷이 실행돼도 이 시퀀스보다 오래된 저장은 건너뜁니다.
        synchronized(lock){lastStateSequence=state.sequence;}
        Options current=options;long now=wallClock.getAsLong();
        if(now<lastPeriodicBackup)lastPeriodicBackup=now;
        if(current.backupIntervalSeconds()>0&&now-lastPeriodicBackup>=current.backupIntervalSeconds()*1000L) {
            try {retry(()->storage.backupCurrentState("periodic",current.backupRetention()));}
            catch(Exception warning) {dispatch(()->statusFailure.accept(warning),warning);}
            finally {lastPeriodicBackup=now;}
        }
        // 실패 전에 캡처한 옛 저장이 뒤늦게 성공해도 결제를 다시 열지 않습니다.
        synchronized(lock){if(fatalFailure==null||state.sequence>failureSequence)fatalFailure=null;}
    }
    private void writeAdminStatus(Status status) {
        if(status.sequence<=lastStatusSequence)return;
        try {retry(()->{storage.writeStatus(status.text);return null;});lastStatusSequence=status.sequence;statusFailed=false;}
        catch(Exception failure) {if(!statusFailed){statusFailed=true;dispatch(()->statusFailure.accept(failure),failure);}}
    }
    private void failState(Exception failure) {
        synchronized(lock) {dirty=true;failureSequence=sequence;if(fatalFailure!=null)return;fatalFailure=failure;}
        dispatch(()->{if(fatalFailure==failure)stateFailure.accept(failure);},failure);
    }
    private void dispatch(Runnable callback,Exception failure) {
        try {dispatchMain.accept(callback);}catch(RuntimeException dispatcherFailure){failure.addSuppressed(dispatcherFailure);}
    }
    private <T>T retry(Callable<T> operation) throws Exception {
        Options current=options;
        for(int attempt=0;;attempt++) {
            try {return operation.call();}
            catch(Exception failure) {
                if(!(failure instanceof IOException)||attempt+1>=current.retryAttempts())throw failure;
                long delay=Math.min(5000L,current.retryDelayMillis()*(1L<<attempt));
                try {sleeper.sleep(delay);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw interrupted;}
            }
        }
    }
    /** 정상 휴장·종료·리셋만 사용합니다. 여기에서만 main이 디스크 완료를 기다립니다. */
    public void flushNow(Market market) throws Exception {critical(market,false);}
    /** 현재 라운드 최신 상태가 기록되고 초기화 전 백업이 완성된 뒤에만 반환합니다. */
    public Path backupBeforeReset(Market market) throws Exception {return critical(market,true);}
    private Path critical(Market market,boolean backup) throws Exception {
        synchronized(lock){GameConfig.require(!closed,"저장 작업이 이미 종료되었습니다");}
        Snapshot state;
        try {state=snapshot(market);}catch(RuntimeException failure){synchronized(lock){dirty=true;}failState(failure);throw failure;}
        Status status;
        synchronized(lock){GameConfig.require(!closed,"저장 작업이 이미 종료되었습니다");dirty=false;status=mainStatus==null?null:new Status(nextSequence(),mainStatus);mainStatus=null;}
        Future<Path> future=worker.submit(()->{
            try {
                writeState(state,true);Path result=backup?retry(()->storage.backupCurrentState("before-reset",0)):null;
                if(status!=null)writeAdminStatus(status);return result;
            }catch(Exception failure){failState(failure);throw failure;}
        });
        return await(future);
    }
    private static <T>T await(Future<T> future) throws Exception {
        try {return future.get();}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw interrupted;}
        catch(ExecutionException failure){if(failure.getCause() instanceof Exception cause)throw cause;throw new IOException("저장 worker 실패",failure.getCause());}
    }
    public void close(Market market) throws Exception {
        synchronized(lock){if(closed)return;}
        Exception problem=null;
        try {flushNow(market);}catch(Exception failure){problem=failure;}
        synchronized(lock){closed=true;pendingState=null;pendingStatus=null;mainStatus=null;}
        if(problem==null)worker.shutdown();else worker.shutdownNow();
        if(!worker.awaitTermination(30,TimeUnit.SECONDS)){worker.shutdownNow();throw new IOException("저장 worker 종료 대기 시간 초과");}
        if(problem!=null)throw problem;
    }
}
