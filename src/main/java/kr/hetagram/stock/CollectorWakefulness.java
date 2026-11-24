package kr.hetagram.stock;

import org.bukkit.entity.Villager;
import org.bukkit.entity.memory.MemoryKey;

/** Villager의 경로 탐색은 유지하고 수면·직장·모임 목적지만 지웁니다. 일반 주민은 호출하지 않습니다. */
final class CollectorWakefulness {
    static void keepAwake(Villager villager) {
        if(villager.isSleeping())villager.wakeup();
        // setAware(false)/setAI(false)는 Paper Pathfinder의 이동도 멈추므로 사용하지 않습니다.
        villager.setMemory(MemoryKey.HOME,null);
        villager.setMemory(MemoryKey.JOB_SITE,null);
        villager.setMemory(MemoryKey.POTENTIAL_JOB_SITE,null);
        villager.setMemory(MemoryKey.MEETING_POINT,null);
    }
    private CollectorWakefulness() {}
}
