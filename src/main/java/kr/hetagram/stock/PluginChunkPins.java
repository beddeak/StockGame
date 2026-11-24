package kr.hetagram.stock;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 서버 스레드 전용. 같은 플러그인의 여러 기능이 겹친 청크를 쓰더라도 마지막 소유자만 해제합니다. */
final class PluginChunkPins implements AutoCloseable {
    private record Key(World world,int x,int z) {}
    private static final class Shared {
        int owners=1;
        final boolean added;
        Shared(boolean added) {this.added=added;}
    }
    private static final Map<Plugin,Map<Key,Shared>> SHARED=new IdentityHashMap<>();
    private final Plugin plugin;
    private final Set<Key> owned=new LinkedHashSet<>();
    private boolean closed;
    PluginChunkPins(Plugin plugin) {this.plugin=Objects.requireNonNull(plugin);}

    void pin(Location location) {pin(location.getWorld(),location.getBlockX()>>4,location.getBlockZ()>>4);}
    void pin(World world,int chunkX,int chunkZ) {
        GameConfig.require(!closed,"이미 종료한 청크 소유자입니다");Objects.requireNonNull(world);
        var key=new Key(world,chunkX,chunkZ);if(owned.contains(key))return;
        var shared=SHARED.computeIfAbsent(plugin,ignored->new HashMap<>());var existing=shared.get(key);
        if(existing==null) {
            // false이면 다른 코드가 이미 가진 같은 플러그인 티켓이므로 여기서 제거하지 않습니다.
            boolean added=world.addPluginChunkTicket(chunkX,chunkZ,plugin);shared.put(key,new Shared(added));
        }else existing.owners++;
        owned.add(key);
    }
    void pinRectangle(World world,int minX,int minZ,int maxX,int maxZ) {
        GameConfig.require(minX<=maxX&&minZ<=maxZ,"청크 영역의 최소/최대 좌표가 잘못되었습니다");
        for(int x=minX>>4;x<=maxX>>4;x++)for(int z=minZ>>4;z<=maxZ>>4;z++)pin(world,x,z);
    }
    int size() {return owned.size();}
    @Override public void close() {
        if(closed)return;closed=true;var shared=SHARED.get(plugin);
        for(var key:owned) {
            if(shared==null)continue;var state=shared.get(key);if(state==null)continue;
            if(--state.owners==0) {
                shared.remove(key);
                if(state.added)try {key.world().removePluginChunkTicket(key.x(),key.z(),plugin);}
                catch(RuntimeException failure){plugin.getLogger().warning("청크 티켓 해제 실패: "+key.x()+","+key.z()+": "+failure.getMessage());}
            }
        }
        owned.clear();if(shared!=null&&shared.isEmpty())SHARED.remove(plugin);
    }
}
