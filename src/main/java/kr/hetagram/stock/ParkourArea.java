package kr.hetagram.stock;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Vector3f;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 기존 월드에 빈 공간만 사용해 점프맵을 건설합니다. 모든 Bukkit/계정 작업은 메인 스레드입니다. */
final class ParkourArea implements Listener,AutoCloseable {
    private static final class Attempt {
        final long generation,started;
        int checkpoint;
        Attempt(long generation,long started){this.generation=generation;this.started=started;}
    }
    private final StockPlugin plugin;
    private final World world;
    private final Path marker;
    private final NamespacedKey owner;
    private final Map<UUID,Attempt> attempts=new HashMap<>();
    private final Set<UUID> internalTeleports=new HashSet<>();
    private final List<TextDisplay> labels=new ArrayList<>();
    private final ArrayDeque<Map.Entry<ParkourLayout.Pos,Material>> building=new ArrayDeque<>();
    private final Map<ParkourLayout.Pos,Material> observed=new HashMap<>();
    private ParkourSettings settings;
    private ParkourLayout.Plan plan;
    private ParkourRuns runs;
    private PluginChunkPins pins;
    private BukkitTask task;
    private long ticks;
    private boolean ready,closed,opened;

    ParkourArea(StockPlugin plugin,World world,ParkourSettings settings) {
        this.plugin=plugin;this.world=world;this.settings=settings;
        marker=world.getWorldFolder().toPath().resolve("stockgame-parkour.yml");owner=new NamespacedKey(plugin,"parkour-label");
    }
    boolean ready(){return ready&&!closed;}
    boolean contains(Block block){return !closed&&plan!=null&&pins!=null&&block.getWorld().equals(world)&&plan.bounds().contains(block.getX(),block.getY(),block.getZ());}

    void open() {
        if(opened||closed)return;opened=true;if(!settings.enabled())return;
        try {
            var spawn=world.getSpawnLocation();plan=ParkourLayout.plan(settings,spawn.getBlockX(),spawn.getBlockY(),spawn.getBlockZ());
            var bounds=plan.bounds();
            GameConfig.require(bounds.minY()>=world.getMinHeight()&&bounds.maxY()<world.getMaxHeight(),"점프맵이 월드 높이를 벗어납니다");
            for(int x:new int[]{bounds.minX(),bounds.maxX()})for(int z:new int[]{bounds.minZ(),bounds.maxZ()})
                GameConfig.require(world.getWorldBorder().isInside(new Location(world,x+.5,bounds.minY(),z+.5)),"점프맵이 월드 경계를 벗어납니다");
            pins=new PluginChunkPins(plugin);pins.pinRectangle(world,bounds.minX(),bounds.minZ(),bounds.maxX(),bounds.maxZ());pins.pin(location(plan.entryPoint()));
            var saved=new YamlConfiguration();boolean owned=Files.exists(marker);
            if(owned) {
                saved.load(marker.toFile());
                GameConfig.require(saved.getInt("schema")==1&&world.getUID().toString().equals(saved.getString("world")),"점프맵 소유 마커의 월드/스키마가 다릅니다");
                GameConfig.require(plan.layoutKey().equals(saved.getString("layout")),"점프맵 위치/구조가 저장된 마커와 다릅니다. 기존 건축은 보존합니다");
                GameConfig.require(List.of("building","complete").contains(saved.getString("state")),"점프맵 마커 상태가 잘못되었습니다");
            }
            boolean completed=owned&&saved.getString("state").equals("complete");
            // 넓은 구역을 통째로 비우지 않습니다. 기존 블록 하나라도 있으면 시공을 중단합니다.
            for(int x=bounds.minX();x<=bounds.maxX();x++)for(int y=bounds.minY();y<=bounds.maxY();y++)for(int z=bounds.minZ();z<=bounds.maxZ();z++) {
                var pos=new ParkourLayout.Pos(x,y,z);Material actual=world.getBlockAt(x,y,z).getType(),expected=plan.edits().get(pos);
                GameConfig.require(actual.isAir()||owned&&actual==expected,"점프맵 설치 공간에 기존 건축이 있습니다: "+pos+" ("+actual+")");
                if(expected!=null) {
                    GameConfig.require(!completed||actual==expected,"완공된 점프맵 발판이 변경됐습니다. 자동으로 덮어쓰지 않습니다: "+pos);
                    observed.put(pos,actual);
                }
            }
            runs=new ParkourRuns(plan.landings().size(),settings.minimumRunSeconds()*20L);
            for(Entity entity:world.getEntities())if(entity.getPersistentDataContainer().has(owner,PersistentDataType.BYTE))entity.remove();
            if(completed){ready=true;renderLabels();}
            else {saveMarker("building");building.addAll(plan.edits().entrySet());}
            Bukkit.getPluginManager().registerEvents(this,plugin);
            task=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,1,1);
        }catch(Exception failure){failBuild(failure);}
    }
    private void tick() {
        ticks++;
        if(!building.isEmpty()) {
            try {
                for(int n=0;n<settings.buildPerTick()&&!building.isEmpty();n++) {
                    var edit=building.removeFirst();var pos=edit.getKey();var block=world.getBlockAt(pos.x(),pos.y(),pos.z());
                    GameConfig.require(block.getType()==observed.get(pos)||block.getType()==edit.getValue(),"점프맵 건설 도중 새 블록과 충돌했습니다: "+pos);
                    if(block.getType()!=edit.getValue())block.setType(edit.getValue(),false);
                }
                if(building.isEmpty()){world.save();saveMarker("complete");observed.clear();ready=true;renderLabels();}
            }catch(Exception failure){failBuild(failure);}
        }
        if(!ready())return;
        for(var entry:new ArrayList<>(attempts.entrySet())) {
            Player player=Bukkit.getPlayer(entry.getKey());Attempt attempt=entry.getValue();
            if(player==null||!player.isOnline()){cancel(entry.getKey());continue;}
            if(attempt.generation!=plugin.market().resetGeneration){cancel(entry.getKey());continue;}
            if(!eligible(player)){cancel(player,settings.text("invalid-mode"));continue;}
            if(!player.getWorld().equals(world)){cancel(player,settings.text("cancelled"));continue;}
            if(ticks-attempt.started>settings.maximumRunSeconds()*20L){cancel(player,settings.text("timeout"));teleport(player,spawnPoint());continue;}
            var at=player.getLocation();var bounds=plan.bounds();
            if(at.getY()<plan.fallY()||at.getX()<bounds.minX()||at.getX()>=bounds.maxX()+1||at.getZ()<bounds.minZ()||at.getZ()>=bounds.maxZ()+1) {
                if(!teleport(player,plan.landings().get(attempt.checkpoint).safeSpawn()))cancel(player,settings.text("cancelled"));
                else plugin.tell(player,settings.text("fell"));
                continue;
            }
            if(!player.isOnGround())continue;
            for(var landing:plan.landings())if(landing.containsFeet(at.getX(),at.getY(),at.getZ())) {
                // 지도 발판을 실제 밟아야 합니다. 임시 블록/다른 재질의 발판으로 대체할 수 없습니다.
                boolean intact=true;
                for(int x=landing.blocks().minX();x<=landing.blocks().maxX();x++)for(int z=landing.blocks().minZ();z<=landing.blocks().maxZ();z++)
                    if(world.getBlockAt(x,landing.blocks().minY(),z).getType()!=plan.edits().get(new ParkourLayout.Pos(x,landing.blocks().minY(),z)))intact=false;
                if(!intact){cancel(player,settings.text("not-ready"));break;}
                var result=runs.land(player.getUniqueId(),attempt.generation,landing.index(),ticks);
                if(result==ParkourRuns.Landing.TOO_FAST){cancel(player,settings.text("too-fast"));teleport(player,spawnPoint());}
                else if(result==ParkourRuns.Landing.EXPIRED)cancel(player,settings.text("cancelled"));
                else if(result==ParkourRuns.Landing.PROGRESSED&&landing.kind()==ParkourSettings.Kind.CHECKPOINT) {
                    attempt.checkpoint=landing.index();long number=plan.landings().subList(0,landing.index()+1).stream().filter(row->row.kind()==ParkourSettings.Kind.CHECKPOINT).count();
                    plugin.tell(player,Text.fill(settings.text("checkpoint"),Map.of("checkpoint",Long.toString(number))));
                }else if(result==ParkourRuns.Landing.READY)finish(player,attempt);
                break;
            }
        }
    }
    private static boolean eligible(Player player) {
        return !player.hasMetadata("NPC")&&!player.isDead()&&(player.getGameMode()==GameMode.SURVIVAL||player.getGameMode()==GameMode.ADVENTURE)
                &&!player.isFlying()&&!player.isGliding()&&!player.isInsideVehicle()
                &&!player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SPEED)
                &&!player.hasPotionEffect(org.bukkit.potion.PotionEffectType.JUMP_BOOST)
                &&!player.hasPotionEffect(org.bukkit.potion.PotionEffectType.LEVITATION)
                &&!player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING);
    }
    void start(Player player) {
        GameConfig.require(ready(),settings.text("not-ready"));GameConfig.require(eligible(player),settings.text("invalid-mode"));
        plugin.requireFinancialState();
        cancel(player.getUniqueId());GameConfig.require(teleport(player,plan.start().safeSpawn()),settings.text("cancelled"));
        long generation=plugin.market().resetGeneration;attempts.put(player.getUniqueId(),new Attempt(generation,ticks));runs.start(player.getUniqueId(),generation,ticks);
        var values=Map.of("reward",Text.money(plugin.settings(),settings.reward()),"cooldown",Integer.toString(settings.cooldownSeconds()));
        plugin.tell(player,Text.fill(settings.text("started"),values));
        long remaining=plugin.market().account(player.getUniqueId()).parkourRewardReadyAt-System.currentTimeMillis();
        if(remaining>0)plugin.tell(player,Text.fill(settings.text("cooldown"),Map.of("seconds",Long.toString((remaining+999)/1000))));
    }
    void leave(Player player) {cancel(player.getUniqueId());teleport(player,spawnPoint());plugin.tell(player,settings.text("left"));}
    private void finish(Player player,Attempt attempt) {
        UUID id=player.getUniqueId();
        try {
            plugin.requireFinancialState();if(!runs.ready(id,attempt.generation,ticks))return;
            var account=plugin.market().account(id);long now=System.currentTimeMillis();String seconds=Long.toString((ticks-attempt.started)/20);
            if(account.parkourRewardReadyAt>now) {
                if(!runs.complete(id,attempt.generation,ticks))return;attempts.remove(id);
                plugin.tell(player,Text.fill(settings.text("practice-finished"),Map.of("seconds",seconds,"secondsLeft",Long.toString((account.parkourRewardReadyAt-now+999)/1000))));
            } else {
                var receipt=ParkourRewards.quote(plugin.market(),account,settings.reward(),now,settings.cooldownSeconds());
                if(!runs.complete(id,attempt.generation,ticks))return;
                account.cash=receipt.cashAfter();account.parkourRewardReadyAt=receipt.readyAt();attempts.remove(id);
                plugin.saveState();plugin.incomeChanged(player);
                plugin.tell(player,Text.fill(settings.text("finished"),Map.of("amount",Text.money(plugin.settings(),settings.reward()),"seconds",seconds)));
            }
            teleport(player,spawnPoint());
        }catch(Exception failure) {
            cancel(id);plugin.tell(player,plugin.settings().msg("error").replace("{reason}",Objects.toString(failure.getMessage(),failure.getClass().getSimpleName())));
        }
    }
    private Location location(ParkourLayout.Point point){return new Location(world,point.x(),point.y(),point.z(),point.yaw(),point.pitch());}
    private ParkourLayout.Point spawnPoint(){var p=world.getSpawnLocation();return new ParkourLayout.Point(p.getX(),p.getY(),p.getZ(),p.getYaw(),p.getPitch());}
    private boolean teleport(Player player,ParkourLayout.Point point) {
        UUID id=player.getUniqueId();internalTeleports.add(id);
        try {
            var target=location(point);boolean arrived=player.teleport(target)&&player.getWorld().equals(world)&&player.getLocation().distanceSquared(target)<.01;
            if(arrived){player.setFallDistance(0);player.setVelocity(new org.bukkit.util.Vector());}
            return arrived;
        }
        finally {internalTeleports.remove(id);}
    }
    private void cancel(UUID player){attempts.remove(player);if(runs!=null)runs.cancel(player);}
    private void cancel(Player player,String message){cancel(player.getUniqueId());plugin.tell(player,message);}
    void reset() {
        for(UUID id:new ArrayList<>(attempts.keySet())){Player player=Bukkit.getPlayer(id);if(player!=null&&player.isOnline()){teleport(player,spawnPoint());plugin.tell(player,settings.text("cancelled"));}}
        attempts.clear();if(runs!=null)runs.clear();
    }
    void reconfigure(ParkourSettings next) {
        settings.requireSameLayout(next);
        for(UUID id:new ArrayList<>(attempts.keySet())){var player=Bukkit.getPlayer(id);if(player!=null&&player.isOnline()){teleport(player,spawnPoint());plugin.tell(player,settings.text("reloaded"));}}
        attempts.clear();if(runs!=null)runs.clear();settings=next;
        if(plan!=null)runs=new ParkourRuns(plan.landings().size(),settings.minimumRunSeconds()*20L);
        if(ready())renderLabels();
    }
    private void renderLabels() {
        labels.forEach(Entity::remove);labels.clear();
        label(plan.entryPoint(),Text.fill(settings.text("instructions"),Map.of("reward",Text.money(plugin.settings(),settings.reward()),"cooldown",Integer.toString(settings.cooldownSeconds()))),0);
        for(var landing:plan.landings())if(landing.kind()!=ParkourSettings.Kind.NORMAL)label(landing.safeSpawn(),settings.landings().get(landing.index()).label(),settings.yaml().getDouble("display.label-height"));
    }
    private void label(ParkourLayout.Point point,String text,double height) {
        labels.add(world.spawn(location(point).add(0,height,0),TextDisplay.class,label->{
            label.setPersistent(false);label.setInvulnerable(true);label.setBillboard(Display.Billboard.CENTER);label.setShadowed(true);
            label.setLineWidth(settings.yaml().getInt("display.line-width"));label.setViewRange((float)settings.yaml().getDouble("display.view-range"));
            var transform=label.getTransformation();float scale=(float)settings.yaml().getDouble("display.text-scale");transform.getScale().set(new Vector3f(scale));label.setTransformation(transform);
            label.text(Text.component(text));label.getPersistentDataContainer().set(owner,PersistentDataType.BYTE,(byte)1);
        }));
    }
    private void saveMarker(String state) throws Exception {
        var yaml=new YamlConfiguration();yaml.set("schema",1);yaml.set("world",world.getUID().toString());yaml.set("layout",plan.layoutKey());yaml.set("state",state);
        Path temporary=marker.resolveSibling(marker.getFileName()+".tmp");
        try {Files.writeString(temporary,yaml.saveToString(),StandardCharsets.UTF_8);
            try {Files.move(temporary,marker,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,marker,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temporary);}
    }
    private void failBuild(Exception failure) {
        ready=false;building.clear();observed.clear();if(task!=null)task.cancel();labels.forEach(Entity::remove);labels.clear();
        if(pins!=null){pins.close();pins=null;}HandlerList.unregisterAll(this);
        plugin.getLogger().log(java.util.logging.Level.WARNING,"점프맵을 설치/사용하지 않습니다. 기존 건축은 보존합니다: "+failure.getMessage(),failure);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void teleported(PlayerTeleportEvent event) {
        if(!internalTeleports.contains(event.getPlayer().getUniqueId())&&attempts.containsKey(event.getPlayer().getUniqueId()))cancel(event.getPlayer(),settings.text("cancelled"));
    }
    @EventHandler public void quit(PlayerQuitEvent event){cancel(event.getPlayer().getUniqueId());}
    @EventHandler public void died(PlayerDeathEvent event){cancel(event.getEntity().getUniqueId());}
    @Override public void close() {
        if(closed)return;closed=true;if(task!=null)task.cancel();reset();building.clear();observed.clear();
        labels.forEach(Entity::remove);labels.clear();if(pins!=null)pins.close();HandlerList.unregisterAll(this);internalTeleports.clear();
    }
}
