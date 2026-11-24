package kr.hetagram.stock;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 사채 관리자 전용 설정 창입니다. 금액은 원, 시간은 초, 비율은 % 그대로 입력합니다.
 * 채팅 스레드는 입력을 가로채고 전달하기만 하며, 권한 검사와 설정 저장은 서버 스레드에서 합니다.
 */
final class LoanAdminUi implements Listener {
    private static final String ROOT="ui.loan-admin.";
    record Entry(String key,String name,Material icon,int slot,boolean toggle,List<String> lore) {
        Entry {lore=List.copyOf(lore);}
    }
    record Layout(int size,String title,List<Entry> entries,int closeSlot,Material closeIcon,String closeName) {
        Layout {entries=List.copyOf(entries);}
    }
    private record DefaultEntry(String key,String name,Material icon,int slot,boolean toggle) {}
    private static final List<DefaultEntry> DEFAULTS=List.of(
            new DefaultEntry("max-principal","총 대출 한도 (원)",Material.GOLD_BLOCK,10,false),
            new DefaultEntry("default-principal","기본 대출 금액 (원)",Material.GOLD_INGOT,11,false),
            new DefaultEntry("min-principal","최소 대출 금액 (원)",Material.GOLD_NUGGET,12,false),
            new DefaultEntry("term-seconds","만기: 빌리고 몇 초 뒤 한 번에 회수 (최소 30)",Material.CLOCK,13,false),
            new DefaultEntry("interest-interval-seconds","이자 붙는 간격 (초, 최소 10)",Material.REPEATER,14,false),
            new DefaultEntry("interest-percent","간격마다 원금의 이자 (%)",Material.PAPER,15,false),
            new DefaultEntry("upfront-fee-percent","선이자: 빌릴 때 떼는 비율 (%)",Material.REDSTONE,16,false),
            new DefaultEntry("max-interest-percent","총이자 상한 (선이자 포함, %)",Material.BOOK,19,false),
            new DefaultEntry("due-warning-seconds","만기 경고 (몇 초 전)",Material.BELL,20,false),
            new DefaultEntry("allow-top-up","추가 대출 허용",Material.EMERALD,21,true),
            new DefaultEntry("require-low-assets","저자산 조건 사용",Material.CHEST,22,true),
            new DefaultEntry("enabled","사채 시스템 사용",Material.LEVER,23,true),
            new DefaultEntry("collector.grace-seconds","파산 후 첫 추심까지 (초)",Material.BELL,28,false),
            new DefaultEntry("collector.damage","추심 공격 피해 (체력)",Material.IRON_SWORD,29,false),
            new DefaultEntry("collector.attack-interval-ticks","추심 공격 간격 (틱)",Material.REPEATER,30,false),
            new DefaultEntry("collector.rest-seconds","잡힌 뒤 다시 찾아올 때까지 (초)",Material.IRON_BARS,31,false));

    /** 새 창/재입력의 토큰이 다르면 이미 큐에 들어간 옛 채팅은 설정을 바꿀 수 없습니다. */
    record PendingInput(UUID token,String key,String name,String value) {}
    static final class InputSessions {
        private final ConcurrentMap<UUID,PendingInput> pending=new ConcurrentHashMap<>();
        PendingInput begin(UUID player,String key,String name,String value) {
            var input=new PendingInput(UUID.randomUUID(),key,name,value);pending.put(player,input);return input;
        }
        PendingInput current(UUID player) {return pending.get(player);}
        boolean consume(UUID player,PendingInput input) {return input!=null&&pending.remove(player,input);}
        void forget(UUID player) {pending.remove(player);}
        void clear() {pending.clear();}
    }
    private static final class Menu implements InventoryHolder {
        final UUID player;
        final Layout layout;
        final Map<Integer,Entry> actions=new HashMap<>();
        Inventory inventory;
        Menu(UUID player,Layout layout) {this.player=player;this.layout=layout;layout.entries().forEach(e->actions.put(e.slot(),e));}
        @Override public Inventory getInventory() {return inventory;}
    }
    private final StockPlugin plugin;
    private final InputSessions inputs=new InputSessions();
    // GUI 정보는 서버 스레드만 접근합니다. 비동기 채팅에 필요한 정보는 위 ConcurrentMap에만 둡니다.
    private final Map<UUID,Menu> menus=new HashMap<>();
    private volatile boolean active=true;

    LoanAdminUi(StockPlugin plugin) {this.plugin=plugin;}

    private static String text(ConfigurationSection config,String key,String fallback) {
        return config.isSet(ROOT+key)?GameConfig.text(config,ROOT+key):fallback;
    }
    private static List<String> lines(ConfigurationSection config,String key,List<String> fallback) {
        if(!config.isSet(ROOT+key))return fallback;
        Object raw=config.get(ROOT+key);
        GameConfig.require(raw instanceof List<?> && ((List<?>)raw).stream().allMatch(String.class::isInstance),ROOT+key+": 문자열 목록 필요");
        return ((List<?>)raw).stream().map(String.class::cast).toList();
    }
    private static Material icon(ConfigurationSection config,String key,Material fallback) {
        return config.isSet(ROOT+key)?GameConfig.material(config,ROOT+key,false):fallback;
    }
    /** 잘못된 칸 배치는 버튼을 덮어쓰는 대신 명확히 거부합니다. 옛 config에는 기본 배치를 사용합니다. */
    static Layout layout(ConfigurationSection config) {
        int size=config.isSet(ROOT+"size")?GameConfig.integer(config,ROOT+"size",27,54):45;
        GameConfig.require(size%9==0,ROOT+"size: 9의 배수 필요");
        int close=config.isSet(ROOT+"close-slot")?GameConfig.integer(config,ROOT+"close-slot",0,size-1):40;
        GameConfig.require(close<size,ROOT+"close-slot: 인벤토리 크기 밖입니다");
        var used=new HashSet<Integer>();used.add(close);var entries=new ArrayList<Entry>();
        for(var item:DEFAULTS) {
            String prefix="fields."+item.key()+".";
            int slot=config.isSet(ROOT+prefix+"slot")?GameConfig.integer(config,ROOT+prefix+"slot",0,size-1):item.slot();
            GameConfig.require(slot<size&&used.add(slot),ROOT+prefix+"slot: 중복되거나 크기 밖의 슬롯입니다");
            var fallback=lines(config,item.toggle()?"boolean-lore":"number-lore",item.toggle()
                    ?List.of("&7현재 값: &e{value}","&a클릭하여 켜기/끄기")
                    :List.of("&7현재 값: &e{value}","&a클릭한 뒤 채팅으로 숫자 입력"));
            entries.add(new Entry(item.key(),text(config,prefix+"name",item.name()),icon(config,prefix+"icon",item.icon()),slot,item.toggle(),lines(config,prefix+"lore",fallback)));
        }
        var cancellations=lines(config,"cancel-keywords",List.of("취소","cancel"));
        GameConfig.require(!cancellations.isEmpty()&&cancellations.stream().noneMatch(String::isBlank),ROOT+"cancel-keywords: 취소 단어가 필요합니다");
        return new Layout(size,text(config,"title","사채 관리자 설정"),entries,close,icon(config,"close-icon",Material.BARRIER),text(config,"close-name","&c닫기"));
    }

    static String rawValue(Object value) {
        if(value instanceof Number)return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
        return String.valueOf(value);
    }
    private String message(String key,String fallback) {return text(plugin.settings().ui(),key,fallback);}
    private boolean permitted(Player player) {
        if(player.hasPermission("stock.admin")&&!player.hasMetadata("NPC"))return true;
        plugin.tell(player,message("permission-denied","&c사채 설정을 바꿀 관리자 권한이 없습니다."));return false;
    }
    private ItemStack item(Material icon,String name,List<String> lore) {
        var stack=new ItemStack(icon);var meta=stack.getItemMeta();
        meta.displayName(Text.component(name));meta.lore(lore.stream().map(Text::component).toList());stack.setItemMeta(meta);return stack;
    }
    void open(Player player) {
        if(!active||!permitted(player))return;
        inputs.forget(player.getUniqueId());var config=plugin.settings().ui();var layout=layout(config);
        var menu=new Menu(player.getUniqueId(),layout);
        menu.inventory=Bukkit.createInventory(menu,layout.size(),Text.component(layout.title()));
        for(var entry:layout.entries()) {
            Object current=config.get("loans."+entry.key());String value=rawValue(current);
            if(entry.toggle())value=(Boolean.TRUE.equals(current)?message("toggle-on","켜짐"):message("toggle-off","꺼짐"));
            var values=Map.of("value",value,"path","loans."+entry.key(),"name",entry.name());
            menu.inventory.setItem(entry.slot(),item(entry.icon(),Text.fill(entry.name(),values),entry.lore().stream().map(line->Text.fill(line,values)).toList()));
        }
        menu.inventory.setItem(layout.closeSlot(),item(layout.closeIcon(),layout.closeName(),List.of()));
        menus.put(player.getUniqueId(),menu);player.openInventory(menu.inventory);
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void click(InventoryClickEvent event) {
        if(!(event.getView().getTopInventory().getHolder() instanceof Menu menu))return;
        event.setCancelled(true);
        if(!active||!(event.getWhoClicked() instanceof Player player))return;
        int slot=event.getRawSlot();
        // 인벤토리 클릭 처리 중 직접 창을 열고 닫지 않고 다음 틱에 전환합니다.
        Bukkit.getScheduler().runTask(plugin,()->choose(player,menu,slot));
    }
    private void choose(Player player,Menu menu,int slot) {
        if(!active||!player.isOnline()||!menu.player.equals(player.getUniqueId())||menus.get(menu.player)!=menu||player.getOpenInventory().getTopInventory().getHolder()!=menu)return;
        if(!permitted(player)){inputs.forget(menu.player);player.closeInventory();return;}
        if(slot==menu.layout.closeSlot()){inputs.forget(menu.player);player.closeInventory();return;}
        var entry=menu.actions.get(slot);if(entry==null)return;
        if(entry.toggle()) {
            try {plugin.changeLoanSetting(player,"loans."+entry.key(),Boolean.toString(!plugin.settings().ui().getBoolean("loans."+entry.key())));}
            catch(Exception failure){error(player,failure);}
            if(active&&player.isOnline())open(player);return;
        }
        String current=rawValue(plugin.settings().ui().get("loans."+entry.key()));
        // close 이벤트가 먼저 끝난 뒤 입력을 등록하므로 GUI를 닫는 동작에 새 입력이 지워지지 않습니다.
        player.closeInventory();var input=inputs.begin(menu.player,entry.key(),entry.name(),current);prompt(player,input);
    }
    private void prompt(Player player,PendingInput input) {
        plugin.tell(player,Text.fill(message("input-prompt","&e{name}&f 값을 숫자로 입력하세요. 현재: {value} (취소: 취소 / cancel)"),Map.of("name",input.name(),"value",input.value(),"path","loans."+input.key())));
    }
    private void error(Player player,Exception failure) {
        plugin.tell(player,Text.fill(message("input-error","&c설정 변경 실패: {reason}"),Map.of("reason",failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage())));
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void chat(AsyncChatEvent event) {
        var player=event.getPlayer();UUID id=player.getUniqueId();var input=inputs.current(id);
        if(!active||input==null)return;
        event.setCancelled(true);
        String value=PlainTextComponentSerializer.plainText().serialize(event.message()).strip();
        try {Bukkit.getScheduler().runTask(plugin,()->accept(player,input,value));}
        catch(IllegalPluginAccessException disabled){inputs.consume(id,input);}
    }
    /** 토큰, 접속 상태, 권한은 저장 직전 서버 스레드에서 다시 확인합니다. */
    private void accept(Player player,PendingInput input,String value) {
        UUID id=player.getUniqueId();if(!active||!inputs.consume(id,input)||!player.isOnline()||!permitted(player))return;
        var cancelled=lines(plugin.settings().ui(),"cancel-keywords",List.of("취소","cancel"));
        if(cancelled.stream().anyMatch(keyword->keyword.strip().equalsIgnoreCase(value))) {
            plugin.tell(player,message("input-cancelled","&7설정 입력을 취소했습니다."));open(player);return;
        }
        try {plugin.changeLoanSetting(player,"loans."+input.key(),value);open(player);}
        catch(Exception failure) {
            error(player,failure);
            if(active&&player.isOnline()&&player.hasPermission("stock.admin")) {
                var retry=inputs.begin(id,input.key(),input.name(),rawValue(plugin.settings().ui().get("loans."+input.key())));prompt(player,retry);
            }
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void drag(InventoryDragEvent event) {if(event.getView().getTopInventory().getHolder() instanceof Menu)event.setCancelled(true);}
    @EventHandler
    public void inventoryOpened(InventoryOpenEvent event) {inputs.forget(event.getPlayer().getUniqueId());}
    @EventHandler
    public void inventoryClosed(InventoryCloseEvent event) {
        if(event.getView().getTopInventory().getHolder() instanceof Menu menu&&menus.remove(menu.player,menu))inputs.forget(menu.player);
    }
    @EventHandler
    public void quit(PlayerQuitEvent event) {UUID id=event.getPlayer().getUniqueId();inputs.forget(id);menus.remove(id);}

    /** reload 때 입력/창만 폐기하며 리스너와 다음 관리자 창 사용은 유지합니다. */
    void clearMenus() {
        inputs.clear();
        for(Player player:Bukkit.getOnlinePlayers())if(player.getOpenInventory().getTopInventory().getHolder() instanceof Menu)player.closeInventory();
        menus.clear();
    }
    /** 플러그인 종료 때 입력을 폐기하고 리스너도 해제합니다. */
    void close() {active=false;clearMenus();HandlerList.unregisterAll(this);}
}
