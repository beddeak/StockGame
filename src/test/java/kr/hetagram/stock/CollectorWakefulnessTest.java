package kr.hetagram.stock;

import org.bukkit.entity.Villager;
import org.bukkit.entity.memory.MemoryKey;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CollectorWakefulnessTest {
    @Test void ownedSleepingCollectorsAreWokenAndForgetBedsJobsAndMeetingPointsWithoutDisablingMovement() {
        var memories=new HashMap<MemoryKey<?>,Object>();int[] wakeups={0};boolean[] sleeping={true};
        Villager villager=(Villager)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Villager.class},(proxy,method,args)-> {
            return switch(method.getName()) {
                case "isSleeping"->sleeping[0];
                case "wakeup"->{wakeups[0]++;sleeping[0]=false;yield null;}
                case "setMemory"->{memories.put((MemoryKey<?>)args[0],args[1]);yield null;}
                case "setAI","setAware"->throw new AssertionError("Navigation must stay enabled");
                default->throw new AssertionError("Unexpected API: "+method.getName());
            };
        });
        CollectorWakefulness.keepAwake(villager);CollectorWakefulness.keepAwake(villager);
        assertEquals(1,wakeups[0]);assertFalse(sleeping[0]);
        assertEquals(4,memories.size());
        for(var key:List.of(MemoryKey.HOME,MemoryKey.JOB_SITE,MemoryKey.POTENTIAL_JOB_SITE,MemoryKey.MEETING_POINT))assertTrue(memories.containsKey(key)&&memories.get(key)==null);
        sleeping[0]=true;CollectorWakefulness.keepAwake(villager);assertEquals(2,wakeups[0]);
    }
}
