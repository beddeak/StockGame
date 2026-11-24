package kr.hetagram.stock;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCLeftClickEvent;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.npc.skin.profile.ProfileFetcher;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.server.PluginDisableEvent;

/** 수동으로 설치한 사채업자 한 명. Citizens/NPC는 enabled 설정을 실제 적용할 때만 사용합니다. */
final class LenderNpc implements Listener {
    private static final String REGISTRY_NAME="stockgame-lender";
    private final StockPlugin plugin;
    private NPCRegistry registry;
    private NPC npc;
    private PluginChunkPins chunkPins;
    private LenderSettings settings=LenderSettings.disabled();
    private String appliedSkin="";
    private long requestGeneration;
    private boolean closed;

    LenderNpc(StockPlugin plugin) {this.plugin=plugin;}

    void apply(LenderSettings value) {
        GameConfig.require(!closed,"종료된 사채업자 관리자는 재사용할 수 없습니다");
        GameConfig.require(value!=null,"사채업자 설정이 없습니다");settings=value;
        if(!value.enabled()){clearNpc();return;}
        var world=Bukkit.getWorld(value.world());
        if(world==null) {
            GameConfig.require(npc==null,"이동할 사채업자 월드가 아직 로드되지 않았습니다. 기존 NPC를 유지합니다: "+value.world());
            clearNpc();plugin.getLogger().warning("사채업자 월드가 아직 로드되지 않아 소환을 보류합니다: "+value.world());return;
        }
        GameConfig.require(Bukkit.getPluginManager().isPluginEnabled("Citizens")&&CitizensAPI.hasImplementation(),"사채업자 설치에는 Paper 1.20.6용 Citizens가 필요합니다");
        Location feet=new Location(world,value.x(),value.y(),value.z(),value.yaw(),value.pitch());
        var candidate=new PluginChunkPins(plugin);boolean committed=false;
        try {
        // 새 위치의 티켓/소환/스킨 처리가 성공한 뒤에만 이전 위치를 해제합니다.
        candidate.pin(feet);
        if(registry==null) {
            var previous=CitizensAPI.getNamedNPCRegistry(REGISTRY_NAME);
            if(previous!=null){previous.deregisterAll();CitizensAPI.removeNamedNPCRegistry(REGISTRY_NAME);}
            registry=CitizensAPI.createInMemoryNPCRegistry(REGISTRY_NAME);
            Bukkit.getPluginManager().registerEvents(this,plugin);
        }
        String title=ChatColor.translateAlternateColorCodes('&',GameConfig.text(plugin.settings().ui(),"loans.npc-name"));
        if(npc==null) {
            npc=registry.createNPC(EntityType.PLAYER,title);
            npc.setProtected(true);npc.setFlyable(true);npc.data().set(NPC.Metadata.NAMEPLATE_VISIBLE,true);
            npc.data().set(NPC.Metadata.COLLIDABLE,false);npc.data().set(NPC.Metadata.REMOVE_FROM_TABLIST,true);
            npc.data().set(NPC.Metadata.REMOVE_FROM_PLAYERLIST,true);npc.data().set(NPC.Metadata.SHOULD_SAVE,false);
            npc.data().set(NPC.Metadata.PICKUP_ITEMS,false);npc.data().set(NPC.Metadata.SILENT,true);
            var skin=npc.getOrAddTrait(SkinTrait.class);skin.setFetchDefaultSkin(false);skin.setShouldUpdateSkins(false);
            if(!value.skinName().isEmpty())skin.setSkinName(value.skinName(),true);
            if(!npc.spawn(feet)){clearNpc();throw new IllegalStateException("사채업자 NPC 소환에 실패했습니다");}
            appliedSkin=value.skinName();if(!appliedSkin.isEmpty())checkSkin(appliedSkin);
        } else {
            npc.setName(title);
            if(npc.isSpawned())npc.teleport(feet,PlayerTeleportEvent.TeleportCause.PLUGIN);
            else GameConfig.require(npc.spawn(feet),"사채업자 NPC 이동 위치에서 소환에 실패했습니다");
            var actual=npc.getStoredLocation();
            GameConfig.require(actual!=null&&actual.getWorld().equals(feet.getWorld())&&actual.distanceSquared(feet)<.01,"사채업자 이동이 취소되었습니다. 기존 위치 티켓을 유지합니다");
            if(!value.skinName().equals(appliedSkin))updateSkin(value.skinName());
        }
        var old=chunkPins;chunkPins=candidate;committed=true;if(old!=null)old.close();
        }finally {if(!committed)candidate.close();}
    }

    boolean owns(Entity entity) {
        if(closed||registry==null||npc==null)return false;
        NPC found=registry.getNPC(entity);return found!=null&&found.getUniqueId().equals(npc.getUniqueId());
    }

    private boolean owns(NPC clicked) {return !closed&&npc!=null&&clicked.getUniqueId().equals(npc.getUniqueId());}

    private void updateSkin(String nickname) {
        appliedSkin=nickname;++requestGeneration;
        var skin=npc.getOrAddTrait(SkinTrait.class);skin.clearTexture();skin.setFetchDefaultSkin(false);skin.setShouldUpdateSkins(false);
        if(nickname.isEmpty()) {
            Location feet=npc.getStoredLocation().clone();npc.despawn();
            GameConfig.require(npc.spawn(feet),"사채업자 기본 스킨 재생성에 실패했습니다");
        } else {skin.setSkinName(nickname,true);checkSkin(nickname);}
    }

    private void checkSkin(String nickname) {
        long generation=++requestGeneration;NPC expected=npc;
        ProfileFetcher.fetch(nickname,request->{
            if(closed||!plugin.isEnabled()||generation!=requestGeneration||npc!=expected||!nickname.equals(settings.skinName()))return;
            String key=switch(request.getResult()) {
                case SUCCESS->"skin-loaded";case NOT_FOUND->"skin-not-found";case PENDING->null;default->"skin-failed";
            };
            if(key!=null)plugin.getLogger().info(Text.fill(plugin.settings().msg(key),java.util.Map.of("stock",GameConfig.text(plugin.settings().ui(),"loans.npc-name"),"skin",nickname,"reason",request.getResult().name())));
        });
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void leftClick(NPCLeftClickEvent event) {
        // 좌클릭은 Paper의 PrePlayerAttackEntityEvent 경로에서 처리합니다.
        if(!owns(event.getNPC()))return;event.setCancelled(true);
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void rightClick(NPCRightClickEvent event) {
        if(!owns(event.getNPC()))return;event.setCancelled(true);plugin.openLender(event.getClicker());
    }

    @EventHandler
    public void dependencyDisabled(PluginDisableEvent event) {
        if(registry!=null&&event.getPlugin().getName().equals("Citizens")) {
            clearNpc();plugin.getLogger().warning("Citizens가 종료되어 사채업자를 숨겼습니다. lender.yml의 설치 위치는 유지합니다");
        }
    }

    private void clearNpc() {
        ++requestGeneration;HandlerList.unregisterAll(this);
        try {if(registry!=null)registry.deregisterAll();}
        catch(RuntimeException failure){plugin.getLogger().log(java.util.logging.Level.WARNING,"사채업자 NPC 정리 실패",failure);}
        finally {
            try {if(registry!=null&&CitizensAPI.hasImplementation())CitizensAPI.removeNamedNPCRegistry(REGISTRY_NAME);}
            catch(RuntimeException failure){plugin.getLogger().log(java.util.logging.Level.WARNING,"사채업자 레지스트리 정리 실패",failure);}
            registry=null;npc=null;appliedSkin="";if(chunkPins!=null){chunkPins.close();chunkPins=null;}
        }
    }

    void close() {if(closed)return;closed=true;clearNpc();}
}
