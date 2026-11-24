package kr.hetagram.stock;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class PluginChunkPinsTest {
    private static Object primitive(Class<?> type) {
        if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;
        if(type==double.class)return 0D;if(type==float.class)return 0F;if(type==short.class)return (short)0;if(type==byte.class)return (byte)0;return null;
    }
    private Plugin plugin() {
        return (Plugin)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Plugin.class},(proxy,method,args)->switch(method.getName()) {
            case "getName"->"StockGame test";case "getLogger"->Logger.getLogger("chunk-pins-test");case "isEnabled"->true;
            case "hashCode"->System.identityHashCode(proxy);case "equals"->proxy==args[0];default->primitive(method.getReturnType());
        });
    }
    private static final class Tickets {
        final Set<String> present=new HashSet<>();int adds,removes;boolean fail;
        String key(int x,int z,Plugin plugin) {return x+","+z+","+System.identityHashCode(plugin);}
        final World world=(World)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{World.class},(proxy,method,args)-> {
            switch(method.getName()) {
                case "addPluginChunkTicket":adds++;if(fail)throw new IllegalStateException("simulated load failure");return present.add(key((int)args[0],(int)args[1],(Plugin)args[2]));
                case "removePluginChunkTicket":removes++;return present.remove(key((int)args[0],(int)args[1],(Plugin)args[2]));
                case "hashCode":return System.identityHashCode(proxy);case "equals":return proxy==args[0];
                default:return primitive(method.getReturnType());
            }
        });
    }
    @Test void duplicatePinsAndRepeatedCloseDoNotLeakOrRemoveTwice() {
        var plugin=plugin();var tickets=new Tickets();var pins=new PluginChunkPins(plugin);
        pins.pin(tickets.world,0,0);pins.pin(tickets.world,0,0);
        assertEquals(1,tickets.adds);assertEquals(1,pins.size());pins.close();pins.close();
        assertEquals(1,tickets.removes);assertTrue(tickets.present.isEmpty());
        assertThrows(IllegalArgumentException.class,()->pins.pin(tickets.world,0,0));
    }
    @Test void overlappingMapAndWorkAreaKeepTheChunkUntilBothOwnersClose() {
        var plugin=plugin();var tickets=new Tickets();var map=new PluginChunkPins(plugin);var work=new PluginChunkPins(plugin);
        map.pin(tickets.world,2,-1);work.pin(tickets.world,2,-1);assertEquals(1,tickets.adds);
        map.close();assertEquals(0,tickets.removes);assertEquals(1,tickets.present.size());
        work.close();assertEquals(1,tickets.removes);assertTrue(tickets.present.isEmpty());
    }
    @Test void negativeBlockBoundariesPinEveryIntersectingChunkExactlyOnce() {
        var plugin=plugin();var tickets=new Tickets();var pins=new PluginChunkPins(plugin);
        pins.pinRectangle(tickets.world,-17,-1,16,16);pins.pinRectangle(tickets.world,-16,0,0,0);
        assertEquals(12,pins.size());assertEquals(12,tickets.adds);pins.close();assertEquals(12,tickets.removes);
    }
    @Test void anotherPluginsTicketAndPreexistingSamePluginTicketArePreserved() {
        var one=plugin();var two=plugin();var tickets=new Tickets();
        tickets.present.add(tickets.key(0,0,one));
        var first=new PluginChunkPins(one);var second=new PluginChunkPins(two);
        first.pin(tickets.world,0,0);second.pin(tickets.world,0,0);first.close();
        assertEquals(2,tickets.present.size());assertEquals(0,tickets.removes);
        second.close();assertEquals(Set.of(tickets.key(0,0,one)),tickets.present);assertEquals(1,tickets.removes);
    }
    @Test void partialPinFailureStillReleasesTheChunksAlreadyAcquired() {
        var plugin=plugin();var tickets=new Tickets();var pins=new PluginChunkPins(plugin);
        pins.pin(tickets.world,0,0);tickets.fail=true;
        assertThrows(IllegalStateException.class,()->pins.pin(tickets.world,1,0));pins.close();
        assertTrue(tickets.present.isEmpty());assertEquals(1,tickets.removes);
    }
    @Test void failedLenderMoveRetainsTheOldWorldUntilANewLocationIsSuccessfullyPinned() {
        var plugin=plugin();var oldWorld=new Tickets();var newWorld=new Tickets();var old=new PluginChunkPins(plugin);
        old.pin(oldWorld.world,1,1);newWorld.fail=true;var failed=new PluginChunkPins(plugin);
        assertThrows(IllegalStateException.class,()->failed.pin(newWorld.world,1,1));failed.close();
        assertEquals(1,oldWorld.present.size());assertTrue(newWorld.present.isEmpty());
        newWorld.fail=false;var replacement=new PluginChunkPins(plugin);replacement.pin(newWorld.world,1,1);
        old.close();assertTrue(oldWorld.present.isEmpty());assertEquals(1,newWorld.present.size());
        replacement.close();assertTrue(newWorld.present.isEmpty());
    }
}
