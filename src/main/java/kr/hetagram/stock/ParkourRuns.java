package kr.hetagram.stock;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Bukkit과 분리된 점프맵 순서 검증기. 메인 서버 스레드에서만 호출합니다.
 * 플랫폼 0은 시작대이고 마지막 플랫폼은 결승대입니다. 모든 중간 발판을 순서대로 실제 착지해야 합니다.
 * 보상/현금/대기 시간은 여기서 바꾸지 않습니다. 서버 서비스가 금액과 저장 상태를 먼저 검사한 다음
 * 같은 메인 스레드에서 보상 기록과 {@link #complete(UUID, long, long)}를 함께 수행합니다.
 */
public final class ParkourRuns {
    public enum Landing { IGNORED, PROGRESSED, READY, TOO_FAST, EXPIRED }

    private record Run(long generation,long startedAt,long lastTick,int nextIndex) {}
    private final int platformCount;
    private final long minimumRunTicks;
    private final Map<UUID,Run> runs=new HashMap<>();

    public ParkourRuns(int platformCount,long minimumRunTicks) {
        require(platformCount>=2,"점프맵은 시작대와 결승대를 포함해 최소 2개 발판이 필요합니다");
        require(minimumRunTicks>=0,"점프맵 최소 완주 시간은 음수가 될 수 없습니다");
        this.platformCount=platformCount;this.minimumRunTicks=minimumRunTicks;
    }

    /** 시작대에 진입할 때 호출합니다. 다시 시작하면 이전 진행과 시각은 새 기록으로 교체됩니다. */
    public void start(UUID player,long generation,long currentTick) {
        inputs(player,generation,currentTick);
        runs.put(player,new Run(generation,currentTick,currentTick,1));
    }

    /**
     * 접지 중인 발판 번호를 전달합니다. 건너뛴 발판/이미 지난 발판은 진행을 바꾸지 않습니다.
     * 너무 빠른 결승 도달은 도전을 끝냅니다. 결승에서 기다렸다 최소 시간을 채우는 우회는 허용하지 않습니다.
     * 라운드 변경이나 서버 틱 역행도 도전을 폐기합니다.
     */
    public Landing land(UUID player,long generation,int platformIndex,long currentTick) {
        inputs(player,generation,currentTick);
        require(platformIndex>=0&&platformIndex<platformCount,"점프맵 발판 번호가 범위를 벗어났습니다");
        Run run=runs.get(player);
        if(run==null)return Landing.IGNORED;
        if(stale(run,generation,currentTick)) {runs.remove(player);return Landing.EXPIRED;}
        if(run.nextIndex==platformCount)return platformIndex==platformCount-1?Landing.READY:Landing.IGNORED;
        if(platformIndex!=run.nextIndex)return Landing.IGNORED;
        boolean finish=platformIndex==platformCount-1;
        if(finish&&currentTick-run.startedAt<minimumRunTicks) {runs.remove(player);return Landing.TOO_FAST;}
        runs.put(player,new Run(generation,run.startedAt,currentTick,run.nextIndex+1));
        return finish?Landing.READY:Landing.PROGRESSED;
    }

    /** 보상 지급 전 재확인. false인 경우 재무 상태/진행 상태를 바꾸지 않습니다(폐기된 라운드 제외). */
    public boolean ready(UUID player,long generation,long currentTick) {
        inputs(player,generation,currentTick);
        Run run=runs.get(player);
        if(run==null)return false;
        if(stale(run,generation,currentTick)) {runs.remove(player);return false;}
        return run.nextIndex==platformCount&&currentTick-run.startedAt>=minimumRunTicks;
    }

    /** 지급 확정 뒤 한 번만 성공하며, 삭제된 도전으로는 중복 보상을 청구할 수 없습니다. */
    public boolean complete(UUID player,long generation,long currentTick) {
        if(!ready(player,generation,currentTick))return false;
        runs.remove(player);return true;
    }

    /** 사망·로그아웃·텔레포트·비행·게임 모드 변경·코스 이탈 때 호출합니다. */
    public boolean cancel(UUID player) {return runs.remove(Objects.requireNonNull(player))!=null;}
    public void clear() {runs.clear();}
    /** 진행 중이 아니면 -1, 완주 검증을 끝냈으면 전체 발판 개수를 반환합니다. */
    public int nextIndex(UUID player) {Run run=runs.get(Objects.requireNonNull(player));return run==null?-1:run.nextIndex;}
    public int platformCount() {return platformCount;}

    private static boolean stale(Run run,long generation,long tick) {return run.generation!=generation||tick<run.lastTick;}
    private static void inputs(UUID player,long generation,long tick) {
        Objects.requireNonNull(player);require(generation>=0,"점프맵 라운드 번호가 잘못되었습니다");require(tick>=0,"점프맵 서버 틱이 잘못되었습니다");
    }
    private static void require(boolean valid,String message) {if(!valid)throw new IllegalArgumentException(message);}
}
