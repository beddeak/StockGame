package kr.hetagram.stock;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 한 번의 클릭에서 주 손/보조 손/엔티티/블록 이벤트가 겹쳐도 같은 틱에는 한 번만 거래합니다. */
public final class TerminalClickGate {
    private final Map<UUID,Integer> lastTick=new HashMap<>();
    public boolean allow(UUID player,int serverTick) {
        Integer previous=lastTick.put(player,serverTick);
        return previous==null||previous!=serverTick;
    }
    public void forget(UUID player) { lastTick.remove(player); }
    public void clear() { lastTick.clear(); }
}
