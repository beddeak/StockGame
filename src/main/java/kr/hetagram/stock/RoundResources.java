package kr.hetagram.stock;

import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.persistence.PersistentDataType;
import java.nio.file.*;
import java.util.*;

/** 채굴·낚시 드롭에 라운드를 기록합니다. 상자/오프라인 인벤토리에 보관해도 구 라운드 자원은 팔 수 없습니다. */
final class RoundResources implements Listener {
    private final StockPlugin plugin;
    private final NamespacedKey roundKey;
    private final long legacyGeneration;
    RoundResources(StockPlugin plugin) throws Exception {
        this.plugin=plugin;roundKey=new NamespacedKey(plugin,"resource-round");
        Path file=plugin.getDataFolder().toPath().resolve("resource-round.yml");var yaml=new YamlConfiguration();
        if(Files.exists(file)) {
            yaml.load(file.toFile());GameConfig.integer(yaml,"schema",1,1);
            Object raw=yaml.get("legacy-generation");GameConfig.require(raw instanceof Number,"자원 라운드 마커가 잘못되었습니다");
            legacyGeneration=new java.math.BigDecimal(raw.toString()).longValueExact();GameConfig.require(legacyGeneration>=0&&legacyGeneration<=plugin.market().resetGeneration,"자원 라운드 마커 범위를 확인하세요");
        }else {
            legacyGeneration=plugin.market().resetGeneration;yaml.set("schema",1);yaml.set("legacy-generation",legacyGeneration);
            ConfigEditsAtomic.write(file,yaml.saveToString());
        }
    }
    private boolean strict(){return plugin.workSettings().yaml().getBoolean("round-resources-only",true);}
    private boolean catalog(ItemStack item){return item!=null&&plugin.workSettings().catalog().containsKey(item.getType());}
    private Long generation(ItemStack item){return item!=null&&item.hasItemMeta()?item.getItemMeta().getPersistentDataContainer().get(roundKey,PersistentDataType.LONG):null;}
    private boolean onlyRoundMeta(ItemStack item) {
        if(!item.hasItemMeta())return true;
        var clean=item.getItemMeta();clean.getPersistentDataContainer().remove(roundKey);
        return clean.equals(new ItemStack(item.getType()).getItemMeta());
    }
    boolean sellable(ItemStack item,Material only) {
        if(!catalog(item)||item.getAmount()<=0||only!=null&&item.getType()!=only||!onlyRoundMeta(item))return false;
        Long generation=generation(item);
        return currentRound(generation,plugin.market().resetGeneration,legacyGeneration,strict());
    }
    private boolean obsolete(ItemStack item) {
        if(!strict()||!catalog(item))return false;Long generation=generation(item);
        return obsoleteRound(generation,onlyRoundMeta(item),plugin.market().resetGeneration,legacyGeneration);
    }
    static boolean currentRound(Long itemGeneration,long current,long legacy,boolean strict){return !strict||itemGeneration!=null&&itemGeneration==current||itemGeneration==null&&current==legacy;}
    static boolean obsoleteRound(Long itemGeneration,boolean plain,long current,long legacy){return itemGeneration!=null?itemGeneration!=current:plain&&current!=legacy;}
    private void stamp(ItemStack stack) {
        if(!catalog(stack)||stack.hasItemMeta())return;
        var meta=stack.getItemMeta();meta.getPersistentDataContainer().set(roundKey,PersistentDataType.LONG,plugin.market().resetGeneration);stack.setItemMeta(meta);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void mined(BlockDropItemEvent event){for(var item:event.getItems()){var stack=item.getItemStack();stamp(stack);item.setItemStack(stack);}}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void fished(PlayerFishEvent event){if(event.getState()==PlayerFishEvent.State.CAUGHT_FISH&&event.getCaught() instanceof Item item){var stack=item.getItemStack();stamp(stack);item.setItemStack(stack);}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void pickup(EntityPickupItemEvent event){if(event.getEntity() instanceof Player&&obsolete(event.getItem().getItemStack())){event.setCancelled(true);event.getItem().remove();}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void opened(InventoryOpenEvent event){if(event.getInventory().getLocation()!=null||event.getInventory().getType()==org.bukkit.event.inventory.InventoryType.ENDER_CHEST)purge(event.getInventory(),0);}
    void purge(Player player){purge(player.getInventory(),0);purge(player.getEnderChest(),0);}
    private void purge(Inventory inventory,int depth) {
        var contents=inventory.getContents();boolean changed=false;
        for(int i=0;i<contents.length;i++) {
            var item=contents[i];if(item==null)continue;
            if(obsolete(item)){contents[i]=null;changed=true;continue;}
            if(depth<4&&item.getItemMeta() instanceof BlockStateMeta meta&&meta.getBlockState() instanceof org.bukkit.block.ShulkerBox box) {
                purge(box.getInventory(),depth+1);meta.setBlockState(box);item.setItemMeta(meta);changed=true;
            }
        }
        if(changed)inventory.setContents(contents);
    }
    void afterReset(){for(Player player:Bukkit.getOnlinePlayers())if(!player.hasMetadata("NPC"))purge(player);}
    /** 원자 저장은 startup marker 같은 작고 드문 파일에만 동기로 사용합니다. */
    private static final class ConfigEditsAtomic {
        static void write(Path file,String text) throws Exception {
            Files.createDirectories(file.getParent());Path temp=file.resolveSibling(file.getFileName()+".tmp");
            Files.writeString(temp,text,java.nio.charset.StandardCharsets.UTF_8);
            try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}
        }
    }
}
