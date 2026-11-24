package kr.hetagram.stock;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import java.util.*;

/** 채굴·낚시 아이템 상점. 일반 드롭만 명시적 클릭/명령으로 판매합니다. Bukkit 작업은 메인 스레드입니다. */
final class WorkShop implements Listener {
    private final StockPlugin plugin;
    private WorkSettings settings;
    private static final class Menu implements InventoryHolder {
        final UUID player;final Map<Integer,Material> materials=new HashMap<>();Inventory inventory;
        Menu(UUID player){this.player=player;}
        public Inventory getInventory(){return inventory;}
    }
    WorkShop(StockPlugin plugin,WorkSettings settings){this.plugin=plugin;this.settings=settings;}
    void reconfigure(WorkSettings updated){close();settings=updated;}
    private String text(String key){return GameConfig.text(settings.yaml(),"shop."+key);}
    private int slot(String key){return GameConfig.integer(settings.yaml(),"shop."+key+"-slot",45,53);}
    private ItemStack item(Material material,String name,List<String> lore) {
        var stack=new ItemStack(material);var meta=stack.getItemMeta();meta.displayName(Text.component(name));meta.lore(lore.stream().map(Text::component).toList());stack.setItemMeta(meta);return stack;
    }
    void open(Player player) {
        GameConfig.require(settings.enabled(),"채굴·낚시 수입 기능이 꺼져 있습니다");
        var menu=new Menu(player.getUniqueId());menu.inventory=Bukkit.createInventory(menu,54,Text.component(text("title")));
        int index=0;
        for(var entry:settings.catalog().entrySet()) {
            var values=Map.of("item",entry.getKey().name(),"price",Text.money(plugin.settings(),entry.getValue()));
            var display=item(entry.getKey(),Text.fill(text("item-name"),values),settings.yaml().getStringList("shop.item-lore").stream().map(line->Text.fill(line,values)).toList());
            // 별도 이름을 설정하지 않으면 클라이언트의 한국어 아이템 이름을 그대로 사용합니다.
            if(text("item-name").equals("{item}")){var meta=display.getItemMeta();meta.displayName(null);display.setItemMeta(meta);}
            menu.inventory.setItem(index,display);menu.materials.put(index++,entry.getKey());
        }
        for(String key:List.of("sell","mine","fish","kit","parkour","close"))menu.inventory.setItem(slot(key),item(GameConfig.material(settings.yaml(),"shop."+key+"-icon",false),text(key+"-name"),List.of()));
        player.openInventory(menu.inventory);
    }
    /** 이름·인챈트·내구도·PDC 등이 달린 특수 아이템은 실수로 판매하지 않습니다. */
    static boolean sellable(ItemStack item,Map<Material,Long> catalog,Material only) {
        return item!=null&&item.getAmount()>0&&catalog.containsKey(item.getType())&&(only==null||only==item.getType())&&!item.hasItemMeta();
    }
    void sell(Player player,Material only) {
        plugin.requireFinancialState();
        GameConfig.require(settings.enabled(),"채굴·낚시 수입 기능이 꺼져 있습니다");
        GameConfig.require(player.getGameMode()==GameMode.SURVIVAL||player.getGameMode()==GameMode.ADVENTURE,"생존·모험 모드에서만 아이템을 판매할 수 있습니다");
        var contents=player.getInventory().getStorageContents();var amounts=new EnumMap<Material,Integer>(Material.class);
        for(var item:contents)if(plugin.resources().sellable(item,only))amounts.merge(item.getType(),item.getAmount(),Math::addExact);
        var quote=ResourceSales.quote(amounts,settings.catalog());var account=plugin.market().account(player.getUniqueId());
        // 일부 아이템만 사라지거나 범위 초과 현금이 들어가는 일을 막기 위해 먼저 전액을 검증합니다.
        long next=ResourceSales.cashAfter(plugin.market(),account,quote.amount());
        for(int i=0;i<contents.length;i++)if(plugin.resources().sellable(contents[i],only))contents[i]=null;
        player.getInventory().setStorageContents(contents);account.cash=next;plugin.incomeChanged(player);
        plugin.tell(player,Text.fill(text("receipt"),Map.of("count",Long.toString(quote.items()),"amount",Text.money(plugin.settings(),quote.amount()))));
    }
    /** 추심원에게 잡힌 파산자의 이번 라운드 판매 재료를 판매가로 압류해 빚에 넣습니다. 게임 모드와 무관하게 적용합니다. */
    long seizeForDebt(Player player) {
        if(!settings.enabled())return 0;
        var contents=player.getInventory().getStorageContents();var amounts=new EnumMap<Material,Integer>(Material.class);
        for(var item:contents)if(plugin.resources().sellable(item,null))amounts.merge(item.getType(),item.getAmount(),Math::addExact);
        if(amounts.isEmpty())return 0;
        var quote=ResourceSales.quote(amounts,settings.catalog());var account=plugin.market().account(player.getUniqueId());
        long next=ResourceSales.cashAfter(plugin.market(),account,quote.amount());
        for(int i=0;i<contents.length;i++)if(plugin.resources().sellable(contents[i],null))contents[i]=null;
        player.getInventory().setStorageContents(contents);account.cash=next;plugin.incomeChanged(player);
        plugin.tell(player,Text.fill(plugin.settings().msg("collector-seized"),Map.of("count",Long.toString(quote.items()),"amount",Text.money(plugin.settings(),quote.amount()))));
        return quote.amount();
    }
    void kit(Player player) {
        GameConfig.require(settings.enabled(),"채굴·낚시 수입 기능이 꺼져 있습니다");
        var contents=player.getInventory().getStorageContents();var missing=new ArrayList<ItemStack>();
        for(var entry:settings.kit().entrySet()) {
            boolean has=Arrays.stream(contents).filter(Objects::nonNull).anyMatch(item->item.getType()==entry.getKey());
            if(!has){var stack=new ItemStack(entry.getKey(),entry.getValue());if(settings.yaml().getBoolean("kit-unbreakable",true)){var meta=stack.getItemMeta();meta.setUnbreakable(true);stack.setItemMeta(meta);}missing.add(stack);}
        }
        long spaces=Arrays.stream(contents).filter(item->item==null||item.getType().isAir()).count();
        GameConfig.require(spaces>=missing.size(),text("inventory-full"));
        if(!missing.isEmpty()){player.getInventory().addItem(missing.toArray(ItemStack[]::new));plugin.tell(player,text("kit-message"));}
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent event) {
        if(!(event.getView().getTopInventory().getHolder() instanceof Menu menu))return;
        event.setCancelled(true);if(!(event.getWhoClicked() instanceof Player player)||!menu.player.equals(player.getUniqueId()))return;
        if(event.getClick()!=ClickType.LEFT&&event.getClick()!=ClickType.RIGHT)return;
        int index=event.getRawSlot();if(index<0||index>=menu.inventory.getSize())return;
        Bukkit.getScheduler().runTask(plugin,()->choose(player,menu,index));
    }
    private void choose(Player player,Menu menu,int index) {
        if(!player.isOnline()||player.getOpenInventory().getTopInventory().getHolder()!=menu)return;
        try {
            if(menu.materials.containsKey(index)){sell(player,menu.materials.get(index));return;}
            if(index==slot("sell"))sell(player,null);
            else if(index==slot("mine")){player.closeInventory();plugin.goWork(player,false);}
            else if(index==slot("fish")){player.closeInventory();plugin.goWork(player,true);}
            else if(index==slot("kit"))kit(player);
            else if(index==slot("parkour")){player.closeInventory();plugin.goParkour(player);}
            else if(index==slot("close"))player.closeInventory();
        }catch(Exception e){plugin.tell(player,plugin.settings().msg("error").replace("{reason}",Objects.toString(e.getMessage(),e.getClass().getSimpleName())));}
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent event){if(event.getView().getTopInventory().getHolder() instanceof Menu)event.setCancelled(true);}
    /** 거래소 월드(낚시터)에서 던진 낚시찌만 입질을 앞당깁니다. 다른 월드 낚시는 바닐라 그대로입니다. */
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void cast(org.bukkit.event.player.PlayerFishEvent event) {
        if(event.getState()!=org.bukkit.event.player.PlayerFishEvent.State.FISHING||!settings.enabled()||!settings.pond().enabled()||!plugin.inExchange(event.getPlayer()))return;
        var pond=settings.pond();var hook=event.getHook();
        hook.setWaitTime(pond.biteMinTicks(),pond.biteMaxTicks());hook.setLureTime(pond.lureMinTicks(),pond.lureMaxTicks());
    }
    void close(){for(Player player:Bukkit.getOnlinePlayers())if(player.getOpenInventory().getTopInventory().getHolder() instanceof Menu)player.closeInventory();}
}

