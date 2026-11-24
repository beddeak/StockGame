package kr.hetagram.stock;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/** 실제 주민의 이동은 Paper Pathfinder로 처리하고 공격은 지정 채무자에게만 적용합니다. */
final class DebtCollectors implements Listener {
    private static final Set<Material> HAZARDS=EnumSet.of(Material.MAGMA_BLOCK,Material.CACTUS,Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE,Material.FIRE,Material.SOUL_FIRE,Material.SWEET_BERRY_BUSH,Material.POWDER_SNOW);
    private static final class Hunt {
        final Villager villager;
        long nextAttack;
        Hunt(Villager villager,long nextAttack) {this.villager=villager;this.nextAttack=nextAttack;}
    }
    private final StockPlugin plugin;
    private final NamespacedKey owner;
    private final Map<UUID,Hunt> hunts=new HashMap<>();
    private long ticks;
    private boolean active=true;

    DebtCollectors(StockPlugin plugin) {
        this.plugin=plugin;owner=new NamespacedKey(plugin,"debt-collector");
        // 비정상 reload의 잔여 추심원만 정리합니다. 일반 주민이나 다른 NPC는 건드리지 않습니다.
        for(var world:Bukkit.getWorlds())for(Entity entity:world.getEntities())if(owns(entity))entity.remove();
        Bukkit.getPluginManager().registerEvents(this,plugin);
    }
    private Loans.Debt debt(UUID player) {
        var account=plugin.market().accounts.get(player);return account==null?null:account.debt;
    }
    private boolean eligible(Player player) {
        boolean mode=player.getGameMode()==GameMode.SURVIVAL||player.getGameMode()==GameMode.ADVENTURE;
        return CollectorPolicy.eligible(debt(player.getUniqueId()),plugin.settings().ui().getBoolean("loans.collector.enabled"),
                plugin.market().running,player.isOnline(),player.hasMetadata("NPC"),player.isDead(),mode,
                plugin.market().clockMillis,plugin.settings().ui().getLong("loans.collector.grace-seconds",30)*1000L);
    }
    boolean owns(Entity entity) {return entity instanceof Villager&&entity.getPersistentDataContainer().has(owner,PersistentDataType.STRING);}
    boolean allowsDamage(Entity damager,Entity victim) {
        if(!active||!(victim instanceof Player player)||!owns(damager))return false;
        var hunt=hunts.get(player.getUniqueId());
        if(hunt==null||!hunt.villager.isValid()||hunt.villager.isDead()||!hunt.villager.getWorld().equals(player.getWorld()))return false;
        return CollectorPolicy.canDamage(player.getUniqueId(),victim.getUniqueId(),hunt.villager.getUniqueId(),damager.getUniqueId(),eligible(player));
    }
    void tick() {
        if(!active)return;ticks++;
        if(!plugin.market().running||!plugin.settings().ui().getBoolean("loans.collector.enabled")){removeAll();return;}
        // 거래소는 밤으로 고정될 수 있습니다. 추심원만 매 틱 깨우고 침대/직장/모임 기억을 지웁니다.
        for(var hunt:hunts.values())if(hunt.villager.isValid()&&!hunt.villager.isDead())CollectorWakefulness.keepAwake(hunt.villager);
        if(ticks%plugin.settings().ui().getInt("loans.collector.update-ticks")==0)refresh(true);
    }
    /** 대출 상환/초기화 같은 사용자 동작 직후에는 주기 대기 없이 대상과 정리 상태를 반영합니다. */
    void refresh() {refresh(false);}
    private void refresh(boolean allowAttack) {
        if(!active)return;
        for(UUID id:new ArrayList<>(hunts.keySet())) {
            Player player=Bukkit.getPlayer(id);var hunt=hunts.get(id);
            if(player==null||!eligible(player)||!hunt.villager.isValid()||hunt.villager.isDead()||!hunt.villager.getWorld().equals(player.getWorld())) {
                if(player!=null&&player.isOnline()&&debt(id)==null)plugin.tell(player,plugin.settings().msg("collector-ended"));
                remove(id);
            }
        }
        var c=plugin.settings().ui();double maxDistance=c.getDouble("loans.collector.max-distance"),range=c.getDouble("loans.collector.attack-range");
        for(Player player:Bukkit.getOnlinePlayers()) {
            if(!eligible(player))continue;
            UUID target=player.getUniqueId();var hunt=hunts.get(target);
            if(hunt!=null&&hunt.villager.getLocation().distanceSquared(player.getLocation())>maxDistance*maxDistance){remove(target);hunt=null;}
            if(hunt==null) {
                Location location=spawnLocation(player,c.getDouble("loans.collector.spawn-distance"));
                if(location==null)continue; // 안전한 두 칸 공간이 없으면 블록을 바꾸지 않고 다음 갱신에 다시 찾습니다.
                Villager villager=player.getWorld().spawn(location,Villager.class,entity->{
                    entity.setPersistent(false);entity.setRemoveWhenFarAway(false);entity.setAdult();entity.setAgeLock(true);
                    entity.setAI(true);entity.setCollidable(false);entity.setCanPickupItems(false);
                    entity.setProfession(Villager.Profession.valueOf(c.getString("loans.collector.profession")));
                    entity.customName(Text.component(c.getString("loans.collector.name")));entity.setCustomNameVisible(true);
                    entity.getPersistentDataContainer().set(owner,PersistentDataType.STRING,target.toString());
                    CollectorWakefulness.keepAwake(entity);
                });
                if(!villager.isValid()){villager.remove();continue;}
                villager.getPathfinder().setCanOpenDoors(true);villager.getPathfinder().setCanPassDoors(true);villager.getPathfinder().setCanFloat(true);
                hunt=new Hunt(villager,ticks+c.getInt("loans.collector.attack-interval-ticks"));hunts.put(target,hunt);
                plugin.tell(player,plugin.settings().msg("collector-started"));
            }
            var villager=hunt.villager;
            CollectorWakefulness.keepAwake(villager);
            villager.getPathfinder().moveTo(player,c.getDouble("loans.collector.speed"));villager.lookAt(player);
            if(CollectorPolicy.attackDue(allowAttack,ticks,hunt.nextAttack,villager.getLocation().distanceSquared(player.getLocation()),range,villager.hasLineOfSight(player))) {
                // damage 중 PlayerDeathEvent가 동기로 발생할 수 있으므로 콜백 전에 다음 공격 시각을 잡습니다.
                hunt.nextAttack=ticks+c.getInt("loans.collector.attack-interval-ticks");
                // damage(amount, villager)는 평화로움에서 이벤트가 발생하기도 전에 거부됩니다.
                // Paper 1.20.6의 MOB_ATTACK은 causingEntity가 살아 있는 비플레이어일 때 난이도에 비례합니다.
                // 직접 공격자만 지정하면 이 난이도 보정을 피하면서 주민 식별, 방어구/방패, 피해·사망 이벤트를 유지합니다.
                // withCausingEntity(villager)를 추가하면 평화로움에서 다시 피해가 0이 되므로 의도적으로 생략합니다.
                var source=DamageSource.builder(DamageType.MOB_ATTACK).withDirectEntity(villager).build();
                player.damage(c.getDouble("loans.collector.damage"),source);
            }
        }
    }
    private static boolean clear(Block block) {return block.isPassable()&&!block.isLiquid()&&!HAZARDS.contains(block.getType());}
    private static boolean safe(Location location) {
        var world=location.getWorld();int x=location.getBlockX(),y=location.getBlockY(),z=location.getBlockZ();
        if(world==null||y<=world.getMinHeight()||y+1>=world.getMaxHeight()||!world.isChunkLoaded(x>>4,z>>4)||!world.getWorldBorder().isInside(location))return false;
        var below=world.getBlockAt(x,y-1,z);
        return below.getType().isSolid()&&!HAZARDS.contains(below.getType())&&clear(world.getBlockAt(x,y,z))&&clear(world.getBlockAt(x,y+1,z));
    }
    private static Location spawnLocation(Player player,double distance) {
        Location feet=player.getLocation();
        for(double radius:new double[]{distance,Math.max(1,distance/2),1})for(int direction=0;direction<8;direction++) {
            double angle=direction*Math.PI/4;
            for(int dy:new int[]{0,1,-1,2,-2}) {
                Location candidate=new Location(player.getWorld(),Math.floor(feet.getX()+Math.cos(angle)*radius)+.5,
                        feet.getBlockY()+dy,Math.floor(feet.getZ()+Math.sin(angle)*radius)+.5);
                if(safe(candidate))return candidate;
            }
        }
        Location fallback=new Location(player.getWorld(),feet.getBlockX()+.5,feet.getBlockY(),feet.getBlockZ()+.5);
        return safe(fallback)?fallback:null;
    }
    private void remove(UUID player) {var hunt=hunts.remove(player);if(hunt!=null)hunt.villager.remove();}
    private void removeAll() {for(UUID player:new ArrayList<>(hunts.keySet()))remove(player);}
    @EventHandler public void quit(PlayerQuitEvent event) {remove(event.getPlayer().getUniqueId());}
    @EventHandler(priority=EventPriority.MONITOR) public void death(PlayerDeathEvent event) {
        Player player=event.getPlayer();if(player.hasMetadata("NPC"))return;
        var hunt=hunts.get(player.getUniqueId());
        UUID lastDamager=player.getLastDamageCause() instanceof EntityDamageByEntityEvent damage?damage.getDamager().getUniqueId():null;
        int rest=plugin.settings().ui().getInt("loans.collector.rest-seconds",60);
        boolean caught=hunt!=null&&owns(hunt.villager)&&CollectorPolicy.caughtByAssignedCollector(debt(player.getUniqueId()),player.getUniqueId(),
                hunt.villager.getPersistentDataContainer().get(owner,PersistentDataType.STRING),hunt.villager.getUniqueId(),lastDamager,
                plugin.market().clockMillis,rest*1000L);
        remove(player.getUniqueId());
        if(caught) {
            // 잡히면 가방의 이번 라운드 광석·물고기를 판매가로 압류해 빚에 넣습니다. 친구에게 넘겨 회수를 피하기 전에 털립니다.
            plugin.seizeResourcesForDebt(player);
            plugin.tell(player,Text.fill(plugin.settings().msg("collector-death"),java.util.Map.of("rest",Integer.toString(rest),
                    "debt",Text.money(plugin.settings(),debt(player.getUniqueId())==null?0:debt(player.getUniqueId()).balance()))));
            plugin.saveState();
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void interact(PlayerInteractEntityEvent event) {if(owns(event.getRightClicked()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void interactAt(PlayerInteractAtEntityEvent event) {interact(event);}
    void close() {if(!active)return;active=false;removeAll();HandlerList.unregisterAll(this);}
}
