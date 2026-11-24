package kr.hetagram.stock;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCLeftClickEvent;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.npc.skin.profile.ProfileFetcher;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Citizens 의존 코드는 이 클래스 안에만 둡니다. block 모드는 Citizens 없이도 로드할 수 있습니다.
 * NPC는 플러그인 소유의 메모리 레지스트리에만 존재하며 저장되는 값은 stocks.yml의 skin-name입니다.
 * 스킨 변경 시 Citizens가 엔티티를 다시 만들므로 Bukkit 엔티티 UUID 대신 NPC UUID로 거래를 연결합니다.
 */
final class NpcTraders implements Listener {
    private static final String REGISTRY_NAME="stockgame-traders";
    private final StockPlugin plugin;
    private final NPCRegistry registry;
    private final Map<String,NPC> traders=new LinkedHashMap<>();
    private final Map<UUID,String> stocksByNpc=new HashMap<>();
    private final Map<String,String> skins=new HashMap<>();
    private final Map<String,Long> requests=new HashMap<>();
    private long requestSequence;
    private boolean active=true;

    NpcTraders(StockPlugin plugin) {
        this.plugin=plugin;
        GameConfig.require(CitizensAPI.hasImplementation(),"Citizens가 준비되지 않았습니다. Paper 1.20.6용 Citizens를 설치하고 서버를 재시작하세요");
        // 비정상적인 이전 플러그인 reload의 잔여물도 이 플러그인의 이름 아래에서만 정리합니다.
        var previous=CitizensAPI.getNamedNPCRegistry(REGISTRY_NAME);
        if(previous!=null){previous.deregisterAll();CitizensAPI.removeNamedNPCRegistry(REGISTRY_NAME);}
        registry=CitizensAPI.createInMemoryNPCRegistry(REGISTRY_NAME);
        Bukkit.getPluginManager().registerEvents(this,plugin);
    }

    void spawn(String stock,Location feet,String skinName) {
        GameConfig.require(active&&!traders.containsKey(stock),"거래원 NPC 중복 생성: "+stock);
        NPC npc=registry.createNPC(EntityType.PLAYER,"StockTrader");
        npc.setProtected(true);npc.setFlyable(true);
        npc.data().set(NPC.Metadata.NAMEPLATE_VISIBLE,false);
        npc.data().set(NPC.Metadata.COLLIDABLE,false);
        npc.data().set(NPC.Metadata.REMOVE_FROM_TABLIST,true);
        npc.data().set(NPC.Metadata.REMOVE_FROM_PLAYERLIST,true);
        npc.data().set(NPC.Metadata.SHOULD_SAVE,false);
        npc.data().set(NPC.Metadata.PICKUP_ITEMS,false);
        npc.data().set(NPC.Metadata.SILENT,true);
        var skin=npc.getOrAddTrait(SkinTrait.class);
        skin.setFetchDefaultSkin(false);skin.setShouldUpdateSkins(false);
        if(!skinName.isBlank())skin.setSkinName(skinName,true);
        traders.put(stock,npc);stocksByNpc.put(npc.getUniqueId(),stock);skins.put(stock,skinName);
        if(!npc.spawn(feet)) {
            traders.remove(stock);stocksByNpc.remove(npc.getUniqueId());skins.remove(stock);npc.destroy();
            throw new IllegalStateException("거래원 NPC 생성 실패: "+plugin.settings().stocks().get(stock).name());
        }
        if(!skinName.isBlank())checkSkin(stock,skinName,message->plugin.getLogger().info(message));
    }

    String stock(Entity entity) {
        if(!active)return null;
        NPC npc=registry.getNPC(entity);
        return npc==null?null:stocksByNpc.get(npc.getUniqueId());
    }

    private String stock(NPC npc) {return active?stocksByNpc.get(npc.getUniqueId()):null;}

    /** reload로 수정한 스킨도 적용합니다. 가격 갱신 때 같은 스킨의 조회를 반복하지 않습니다. */
    void syncSkins() {
        if(!active)return;
        for(var entry:traders.entrySet()) {
            var spec=plugin.settings().stocks().get(entry.getKey());
            if(spec!=null&&!spec.skinName().equals(skins.get(entry.getKey())))
                updateSkin(entry.getKey(),spec.skinName(),message->plugin.getLogger().info(message));
        }
    }

    void updateSkin(String stock,String skinName,Consumer<String> feedback) {
        var npc=traders.get(stock);
        GameConfig.require(active&&npc!=null,"거래원 NPC가 아직 준비되지 않았습니다");
        skins.put(stock,skinName);
        var skin=npc.getOrAddTrait(SkinTrait.class);
        // 이전 닉네임의 텍스처를 새 이름의 캐시로 오인하지 않도록 캐시를 비웁니다.
        skin.clearTexture();skin.setFetchDefaultSkin(false);skin.setShouldUpdateSkins(false);
        if(skinName.isBlank()) {
            // 닉네임을 비운 reload에서는 이전 GameProfile의 텍스처도 없애 기본 스킨으로 돌아갑니다.
            Location feet=npc.getStoredLocation().clone();npc.despawn();
            GameConfig.require(npc.spawn(feet),"기본 스킨 거래원 재생성 실패: "+plugin.settings().stocks().get(stock).name());
        } else {skin.setSkinName(skinName,true);checkSkin(stock,skinName,feedback);}
    }

    private void checkSkin(String stock,String skinName,Consumer<String> feedback) {
        long generation=++requestSequence;requests.put(stock,generation);
        // Citizens가 네트워크 조회를 비동기로 처리하고 이 콜백은 메인 서버 스레드로 돌려줍니다.
        ProfileFetcher.fetch(skinName,request->{
            if(!active||!plugin.isEnabled()||!Long.valueOf(generation).equals(requests.get(stock))||!skinName.equals(skins.get(stock)))return;
            String key=switch(request.getResult()) {
                case SUCCESS->"skin-loaded";
                case NOT_FOUND->"skin-not-found";
                case PENDING->null;
                default->"skin-failed";
            };
            if(key!=null)feedback.accept(Text.fill(plugin.settings().msg(key),Map.of("stock",plugin.settings().stocks().get(stock).name(),"skin",skinName,"reason",request.getResult().name())));
        });
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void leftClick(NPCLeftClickEvent event) {
        String stock=stock(event.getNPC());if(stock==null)return;
        // 매수는 PrePlayerAttackEntityEvent 한 곳에서만 처리하여 피해 이벤트와 중복하지 않습니다.
        event.setCancelled(true);
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void rightClick(NPCRightClickEvent event) {
        String stock=stock(event.getNPC());if(stock==null)return;
        event.setCancelled(true);plugin.terminalTrade(event.getClicker(),stock,false);
    }

    @EventHandler
    public void dependencyDisabled(PluginDisableEvent event) {
        if(active&&event.getPlugin().getName().equals("Citizens")) {
            plugin.getLogger().severe("Citizens가 종료되어 플레이어 거래원 모드를 중단합니다. 기존 자산은 저장합니다");
            Bukkit.getPluginManager().disablePlugin(plugin);
        }
    }

    void close() {
        if(!active)return;active=false;requests.clear();
        HandlerList.unregisterAll(this);
        try {
            registry.deregisterAll();
        } catch(RuntimeException failure) {
            // 의존 플러그인이 먼저 종료되더라도 StockPlugin의 자산 저장까지 계속 진행하게 합니다.
            plugin.getLogger().log(java.util.logging.Level.WARNING,"거래원 NPC 종료 중 오류",failure);
        } finally {
            try {
                if(CitizensAPI.hasImplementation())CitizensAPI.removeNamedNPCRegistry(REGISTRY_NAME);
            } catch(RuntimeException failure) {plugin.getLogger().log(java.util.logging.Level.WARNING,"거래원 NPC 레지스트리 정리 실패",failure);}
            traders.clear();stocksByNpc.clear();skins.clear();
        }
    }
}
