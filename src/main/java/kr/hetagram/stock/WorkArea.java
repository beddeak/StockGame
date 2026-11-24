package kr.hetagram.stock;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 기존 주식 월드에만 자원 구역을 설치합니다. 모든 블록 조회·변경은 서버 메인 스레드입니다. */
public final class WorkArea {
    record Pos(int x,int y,int z) {}
    record Box(int minX,int minY,int minZ,int maxX,int maxY,int maxZ) {
        boolean contains(int x,int y,int z) {return x>=minX&&x<=maxX&&y>=minY&&y<=maxY&&z>=minZ&&z<=maxZ;}
        boolean overlaps(Box other) {return minX<=other.maxX&&maxX>=other.minX&&minY<=other.maxY&&maxY>=other.minY&&minZ<=other.maxZ&&maxZ>=other.minZ;}
    }
    private static final class Area {
        final String id,key;final Box main,path;final int floor;final Location spawn;
        final LinkedHashMap<Pos,Material> edits;final Set<Pos> mine;
        final Map<Pos,Material> observed=new HashMap<>();
        boolean ready;
        Area(String id,String key,Box main,Box path,int floor,Location spawn,LinkedHashMap<Pos,Material> edits,Set<Pos> mine) {
            this.id=id;this.key=key;this.main=main;this.path=path;this.floor=floor;this.spawn=spawn;this.edits=edits;this.mine=mine;
        }
    }
    private record Edit(Area area,Pos position,Material material) {}
    private final StockPlugin plugin;
    private final World world;
    private WorkSettings settings;
    private final NamespacedKey owner;
    private final Random random=new Random();
    private final Map<String,Area> areas=new LinkedHashMap<>();
    private final Map<String,PluginChunkPins> chunkPins=new LinkedHashMap<>();
    private final Deque<Edit> building=new ArrayDeque<>();
    private final Map<Pos,Long> regeneration=new HashMap<>();
    private final List<TextDisplay> labels=new ArrayList<>();
    private final Path marker;
    private YamlConfiguration ownership;
    private BukkitTask task;
    private long ticks;
    private boolean active=true,opened;

    public WorkArea(StockPlugin plugin,World world,WorkSettings settings) {
        this.plugin=Objects.requireNonNull(plugin);this.world=Objects.requireNonNull(world);this.settings=Objects.requireNonNull(settings);
        owner=new NamespacedKey(plugin,"work-area");marker=world.getWorldFolder().toPath().resolve("stockgame-workarea.yml");
    }
    public boolean ready() {return active&&areas.values().stream().anyMatch(area->area.ready);}
    public Location mineSpawn() {return spawn("quarry");}
    public Location pondSpawn() {return spawn("pond");}
    private Location spawn(String id) {var area=areas.get(id);GameConfig.require(active&&area!=null&&area.ready,"해당 작업 구역이 설치되지 않았거나 아직 준비 중입니다");return area.spawn.clone();}
    public boolean contains(Block block) {return block.getWorld().equals(world)&&areas.values().stream().anyMatch(area->area.main.contains(block.getX(),block.getY(),block.getZ())||area.path.contains(block.getX(),block.getY(),block.getZ()));}
    public boolean canMine(Block block) {
        var area=areas.get("quarry");return active&&area!=null&&area.ready&&block.getWorld().equals(world)
                &&area.mine.contains(new Pos(block.getX(),block.getY(),block.getZ()))&&block.getType().name().endsWith("_ORE");
    }
    public void onMined(Block block) {
        if(canMine(block))regeneration.putIfAbsent(new Pos(block.getX(),block.getY(),block.getZ()),ticks+settings.quarry().regenerationTicks());
    }
    public void reconfigure(WorkSettings next) {settings.requireSameLayout(next);settings=next;if(ready())renderLabels();}

    /** 충돌한 구역만 건너뜁니다. 다른 구역과 주식 시장은 계속 사용할 수 있습니다. */
    public void open() {
        if(opened||!active)return;opened=true;if(!settings.enabled())return;
        try {
            ownership=new YamlConfiguration();
            if(Files.exists(marker)) {
                ownership.load(marker.toFile());
                GameConfig.require(ownership.getInt("schema")==1&&world.getUID().toString().equals(ownership.getString("world")),"자원 구역 소유 마커의 월드/스키마가 다릅니다");
            } else {ownership.set("schema",1);ownership.set("world",world.getUID().toString());}
            var plans=new ArrayList<Area>();
            if(settings.quarry().enabled())plans.add(plan(true));if(settings.pond().enabled())plans.add(plan(false));
            if(plans.size()==2) {
                var a=plans.getFirst();var b=plans.getLast();
                GameConfig.require(!a.main.overlaps(b.main)&&!a.main.overlaps(b.path)&&!a.path.overlaps(b.main)&&!a.path.overlaps(b.path),"채굴장·낚시터·접근 길 영역이 겹칩니다");
            }
            for(var area:plans)try {preparePinned(area);}catch(Exception failure){skip(area,"소유 마커 준비 실패: "+failure.getMessage());}
            for(Entity entity:world.getEntities())if(entity.getPersistentDataContainer().has(owner,PersistentDataType.STRING))entity.remove();
            if(!areas.isEmpty())task=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,1,1);
            if(ready())renderLabels();
        } catch(Exception failure) {plugin.getLogger().warning("채굴/낚시 구역을 설치하지 않았습니다: "+failure.getMessage());}
    }
    private void preparePinned(Area area) throws Exception {
        var pins=new PluginChunkPins(plugin);boolean retained=false;
        try {
            // 완료 전 준비/관찰/건설에서도 영역을 로드합니다. 인접 영역과 겹친 티켓은 공유 참조합니다.
            pins.pinRectangle(world,area.main.minX,area.main.minZ,area.main.maxX,area.main.maxZ);
            pins.pinRectangle(world,area.path.minX,area.path.minZ,area.path.maxX,area.path.maxZ);
            prepare(area);
            if(areas.get(area.id)==area){chunkPins.put(area.id,pins);retained=true;}
        }catch(Exception failure){areas.remove(area.id,area);throw failure;}
        finally {if(!retained)pins.close();}
    }
    private Area plan(boolean mine) {
        var spawn=world.getSpawnLocation();int floor=spawn.getBlockY()-1;
        int size=mine?settings.quarry().size():settings.pond().size(),depth=mine?settings.quarry().depth():settings.pond().depth();
        int x=spawn.getBlockX()+(mine?settings.quarry().offsetX():settings.pond().offsetX());
        int z=spawn.getBlockZ()+(mine?settings.quarry().offsetZ():settings.pond().offsetZ()),half=size/2+1;
        var main=new Box(x-half,floor-depth-1,z-half,x+half,floor+3,z+half);
        var path=new Box(spawn.getBlockX()+settings.pathStartOffsetX(),floor-2,z-settings.pathWidth()/2,x-half-1,floor+2,z+settings.pathWidth()/2);
        for(var box:List.of(main,path))GameConfig.require(box.minY>=world.getMinHeight()&&box.maxY<world.getMaxHeight()&&Math.abs((long)box.minX)<=29999984&&Math.abs((long)box.maxX)<=29999984&&Math.abs((long)box.minZ)<=29999984&&Math.abs((long)box.maxZ)<=29999984,"작업 구역이 월드 좌표 범위를 벗어납니다");
        var edits=new LinkedHashMap<Pos,Material>();var ores=new HashSet<Pos>();var p=settings.palette();
        for(int bx=main.minX;bx<=main.maxX;bx++)for(int bz=main.minZ;bz<=main.maxZ;bz++)for(int by=main.minY;by<=main.maxY;by++) {
            boolean edge=bx==main.minX||bx==main.maxX||bz==main.minZ||bz==main.maxZ;
            Material material=by>floor?Material.AIR:by==main.minY?p.bottom():edge?(by==floor?p.border():p.wall()):mine?(by<floor-depth+1?p.bottom():settings.quarry().choose(random)):(by==floor?Material.AIR:Material.WATER);
            Pos position=new Pos(bx,by,bz);edits.put(position,material);if(mine&&material.name().endsWith("_ORE"))ores.add(position);
        }
        // 출구는 고정 블록 계단과 두 칸 이상의 머리 공간으로 보존하고 광석 재생 대상에서 제외합니다.
        if(mine)for(int step=1;step<=depth;step++) {
            int bx=main.minX+step,height=floor-step;
            for(int by=main.minY;by<=floor+2;by++) {Pos position=new Pos(bx,by,z);edits.put(position,by<=height?p.stairs():Material.AIR);ores.remove(position);}
        }
        for(int bx=path.minX;bx<=path.maxX;bx++)for(int bz=path.minZ;bz<=path.maxZ;bz++)for(int by=path.minY;by<=path.maxY;by++)edits.put(new Pos(bx,by,bz),by>floor?Material.AIR:by==floor?p.walkway():p.bottom());
        // 광원은 테두리 안에만 놓아 낚시 물 위의 열린 공간을 유지합니다.
        for(int bx:new int[]{main.minX,main.maxX})for(int bz:new int[]{main.minZ,main.maxZ})edits.put(new Pos(bx,floor,bz),p.light());
        String id=mine?"quarry":"pond";
        String key=main+":"+path+":"+settings.palette();
        return new Area(id,key,main,path,floor,new Location(world,main.minX+.5,floor+1,z+.5,270,0),edits,Set.copyOf(ores));
    }
    private void prepare(Area area) throws Exception {
        String node="areas."+area.id;boolean owned=ownership.isConfigurationSection(node);
        if(owned&&!area.key.equals(ownership.getString(node+".layout"))) {skip(area,"저장된 위치·구조와 설정이 달라 재시공하지 않습니다");return;}
        String state=ownership.getString(node+".state","");
        if(owned&&state.equals("complete")) {
            area.ready=true;areas.put(area.id,area);resumeHoles(area);return;
        }
        if(owned&&!state.equals("building")) {skip(area,"소유 마커 상태를 확인하세요");return;}
        for(var entry:area.edits.entrySet()) {
            Pos pos=entry.getKey();Material old=world.getBlockAt(pos.x,pos.y,pos.z).getType();
            if(!baseline(old,pos.y-area.floor)&&!(owned&&(old==entry.getValue()||area.mine.contains(pos)&&old.name().endsWith("_ORE")))) {skip(area,"기존 블록 "+old+" 충돌 ("+pos.x+", "+pos.y+", "+pos.z+")");return;}
            area.observed.put(pos,old);
        }
        ownership.set(node+".layout",area.key);ownership.set(node+".state","building");saveMarker();
        areas.put(area.id,area);
        for(var entry:area.edits.entrySet())building.add(new Edit(area,entry.getKey(),entry.getValue()));
    }
    private void skip(Area area,String reason) {plugin.getLogger().warning(area.id+" 구역 설치 건너뜀: "+reason+". 기존 건축을 변경하지 않았습니다");}
    /** 원래 풀밭의 세 층과 빈 공간만 신규 설치에 사용합니다. 위쪽 잔디·아래 임의 구조는 거부합니다. */
    static boolean baseline(Material material,int relativeY) {
        if(material==Material.AIR||material==Material.CAVE_AIR||material==Material.VOID_AIR)return true;
        return relativeY==0&&material==Material.GRASS_BLOCK||relativeY==-1&&material==Material.DIRT||relativeY==-2&&material==Material.BEDROCK;
    }
    private void resumeHoles(Area area) {for(Pos pos:area.mine)if(world.getBlockAt(pos.x,pos.y,pos.z).getType()==Material.AIR)regeneration.put(pos,ticks+settings.quarry().regenerationTicks());}
    private void tick() {
        if(!active)return;ticks++;
        for(int count=0;count<settings.buildPerTick()&&!building.isEmpty();count++) {
            Edit edit=building.removeFirst();Pos pos=edit.position;Block block=world.getBlockAt(pos.x,pos.y,pos.z);Material old=block.getType();
            if(old!=edit.area.observed.get(pos)) {
                building.removeIf(next->next.area==edit.area);areas.remove(edit.area.id);
                var pins=chunkPins.remove(edit.area.id);if(pins!=null)pins.close();
                skip(edit.area,"건설 중 새 블록 충돌: "+old);continue;
            }
            block.setType(edit.material,false);
            if(building.isEmpty()||building.peekFirst().area!=edit.area) {
                try {ownership.set("areas."+edit.area.id+".state","complete");saveMarker();edit.area.ready=true;renderLabels();}
                catch(Exception failure) {plugin.getLogger().warning("자원 구역 완료 마커 저장 실패: "+failure.getMessage());}
            }
        }
        for(var entry:new ArrayList<>(regeneration.entrySet())) {
            if(entry.getValue()>ticks)continue;Pos pos=entry.getKey();if(!world.isChunkLoaded(pos.x>>4,pos.z>>4))continue;
            Block block=world.getBlockAt(pos.x,pos.y,pos.z);
            var box=new BoundingBox(pos.x,pos.y,pos.z,pos.x+1,pos.y+1,pos.z+1);
            boolean occupied=world.getNearbyEntities(box,entity->(entity instanceof Player||entity instanceof Villager)&&entity.isValid()&&!entity.isDead())
                    .stream().anyMatch(entity->intersectsBody(pos.x,pos.y,pos.z,entity.getBoundingBox()));
            if(occupied){regeneration.put(pos,ticks+settings.quarry().occupiedRetryTicks());continue;}
            // OP가 다른 블록을 놓거나 채굴 이벤트가 취소됐다면 덮어쓰지 않습니다.
            if(block.getType()==Material.AIR)block.setType(settings.quarry().choose(random),false);
            regeneration.remove(pos);
        }
    }
    static boolean intersectsBody(int x,int y,int z,BoundingBox body) {return new BoundingBox(x,y,z,x+1,y+1,z+1).overlaps(body);}
    private void renderLabels() {
        for(var label:labels)label.remove();labels.clear();
        for(var area:areas.values())if(area.ready) {
            Location position=area.spawn.clone().add(-.5,2,0);
            chunkPins.get(area.id).pin(position);
            labels.add(world.spawn(position,TextDisplay.class,label->{
                label.setPersistent(false);label.setInvulnerable(true);label.setBillboard(Display.Billboard.CENTER);label.setShadowed(true);label.setLineWidth(320);
                label.text(Text.component(settings.yaml().getString("text."+area.id)));label.getPersistentDataContainer().set(owner,PersistentDataType.STRING,area.id);
            }));
        }
    }
    private void saveMarker() throws Exception {
        Path temporary=marker.resolveSibling(marker.getFileName()+".tmp");
        try {
            Files.writeString(temporary,ownership.saveToString(),StandardCharsets.UTF_8);
            try {Files.move(temporary,marker,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException unsupported) {Files.move(temporary,marker,StandardCopyOption.REPLACE_EXISTING);}
        } finally {Files.deleteIfExists(temporary);}
    }
    public void close() {
        if(!active)return;active=false;if(task!=null)task.cancel();building.clear();regeneration.clear();
        for(var label:labels)label.remove();labels.clear();areas.clear();
        chunkPins.values().forEach(PluginChunkPins::close);chunkPins.clear();
    }
}
