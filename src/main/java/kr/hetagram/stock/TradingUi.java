package kr.hetagram.stock;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.scoreboard.*;
import java.util.*;

final class TradingUi implements Listener {
    private final StockPlugin plugin;
    private final Map<UUID,Scoreboard> previous=new HashMap<>(),boards=new HashMap<>();
    private long refreshCount,actionbarElapsedTicks;
    private record Action(String type,String stock,long quantity,int page) {}
    static final class Menu implements InventoryHolder {
        final UUID player; final String stock;final int page;final boolean lender;final Map<Integer,Action> actions=new HashMap<>(); Inventory inventory;
        Menu(UUID player,String stock,int page) { this(player,stock,page,false); }
        Menu(UUID player,String stock,int page,boolean lender) { this.player=player;this.stock=stock;this.page=page;this.lender=lender; }
        @Override public Inventory getInventory() { return inventory; }
    }
    TradingUi(StockPlugin plugin) { this.plugin=plugin; }
    private GameConfig c() { return plugin.settings(); }
    Map<String,String> accountValues(Player p) {
        Market market=plugin.market();var a=market.account(p.getUniqueId());long profit=market.profit(a);
        Map<String,String> values=new HashMap<>();values.put("cash",Text.money(c(),a.cash));values.put("equity",Text.money(c(),market.rankValue(a,"net-equity")));values.put("grossEquity",Text.money(c(),Math.addExact(a.cash,market.value(a))));
        values.put("profit",Text.money(c(),profit));values.put("realized",Text.money(c(),a.realized));values.put("profitColor",Text.color(c(),profit));
        values.put("currency",c().ui().getString("ui.currency"));values.put("state",c().msg(market.running?"state-open":"state-closed"));
        var d=a.debt;values.put("debt",Text.money(c(),d==null?0:d.balance()));
        long left=d==null?0:Math.max(0,d.dueAt-market.clockMillis);values.put("seconds",Long.toString(left<=0?0:(left-1)/1000+1));
        values.put("debtState",Text.fill(c().msg(d==null?"debt-none":d.bankrupt?"debt-bankrupt":"debt-active"),values));return values;
    }
    private Map<String,String> quoteValues(Player p,Market.Quote q) {
        var a=plugin.market().account(p.getUniqueId());var holding=a.holdings.getOrDefault(q.spec.id(),new Market.Position(0,0));long value=plugin.market().positionValue(q.spec.id(),holding),profit=value-holding.cost;
        var v=accountValues(p);v.put("stock",q.spec.name());v.put("shortStock",c().stockCommandName(q.spec.id()));v.put("price",Text.money(c(),q.price));v.put("color",Text.color(c(),q.change()));v.put("change",Text.percent(c(),q.change()));
        v.put("quantity",Long.toString(holding.quantity));v.put("average",Text.money(c(),holding.average()));v.put("profit",Text.money(c(),profit));v.put("profitColor",Text.color(c(),profit));v.put("return",Text.percent(c(),Market.returns(value,holding.cost)));v.put("state",c().msg(q.delisted?"state-delisted":plugin.market().running?"state-open":"state-closed"));return v;
    }
    private ItemStack item(Material type,String title,List<String> lore) {
        var item=new ItemStack(type);var meta=item.getItemMeta();meta.displayName(Text.component(title));meta.lore(lore.stream().map(Text::component).toList());item.setItemMeta(meta);return item;
    }
    private void button(Menu m,int slot,Material icon,String title,Action action) { m.inventory.setItem(slot,item(icon,title,List.of()));m.actions.put(slot,action); }
    void open(Player p,String stock,int page) {
        if(stock!=null)plugin.market().quote(stock);
        var menu=new Menu(p.getUniqueId(),stock,Math.max(0,page));menu.inventory=Bukkit.createInventory(menu,54,Text.component(c().ui().getString("ui.title")));
        fill(p,menu);p.openInventory(menu.inventory);
    }
    void openLender(Player p) {
        var menu=new Menu(p.getUniqueId(),null,0,true);
        menu.inventory=Bukkit.createInventory(menu,27,Text.component(c().ui().getString("ui.lender.title")));
        fill(p,menu);p.openInventory(menu.inventory);
    }
    static List<Long> loanChoices(GameConfig config) {
        return loanChoices(config,0);
    }
    static List<Long> loanChoices(GameConfig config,long outstandingPrincipal) {
        return loanChoices(config.loanRules(),outstandingPrincipal);
    }
    static List<Long> loanChoices(GameConfig.LoanRules r,long outstandingPrincipal) {
        if(outstandingPrincipal>0&&!r.allowTopUp())return List.of();
        long available=Math.max(0,r.maxPrincipal()-Math.max(0,outstandingPrincipal));
        return java.util.stream.Stream.of(r.minPrincipal(),r.defaultPrincipal(),r.maxPrincipal()).map(amount->Math.min(amount,available)).filter(amount->amount>=r.minPrincipal()).distinct().toList();
    }
    private void fillLender(Player p,Menu menu) {
        var r=plugin.loanRulesFor(p);var v=accountValues(p);
        v.put("interval",Integer.toString(r.interestIntervalSeconds()));v.put("rate",Text.percent(c(),r.interestPercent()));
        v.put("term",Integer.toString(r.termSeconds()));v.put("threshold",Text.money(c(),r.bankruptcyThreshold()));
        v.put("feePercent",Text.percent(c(),r.upfrontFeePercent()));v.put("maxInterest",Text.percent(c(),r.maxInterestPercent()));
        v.put("collectorGrace",Integer.toString(c().ui().getInt("loans.collector.grace-seconds")));
        v.put("eligibility",Text.fill(c().ui().getString(r.requireLowAssets()?"ui.lender.eligibility-limited":"ui.lender.eligibility-open"),v));
        var a=plugin.market().account(p.getUniqueId());var debt=a.debt;
        long remaining=Math.max(0,r.maxPrincipal()-(debt==null?0:debt.principal));v.put("remaining",Text.money(c(),remaining));
        boolean locked=!c().loanRules().enabled()||debt!=null&&debt.bankrupt;
        boolean eligible=!r.requireLowAssets()||Math.addExact(a.cash,plugin.market().value(a))<=r.bankruptcyThreshold();
        var slots=c().ui().getIntegerList("ui.lender.borrow-slots");var choices=locked||!eligible?List.<Long>of():loanChoices(r,debt==null?0:debt.principal);
        for(int i=0;i<choices.size();i++) {
            long amount=choices.get(i),fee=Loans.upfrontFee(r,amount);int slot=slots.get(i);
            v.put("amount",Text.money(c(),amount));v.put("fee",Text.money(c(),fee));v.put("received",Text.money(c(),amount-fee));
            menu.inventory.setItem(slot,item(GameConfig.material(c().ui(),"ui.lender.borrow-icon",false),Text.fill(c().ui().getString("ui.lender.borrow-name"),v),c().ui().getStringList("ui.lender.loan-lore").stream().filter(line->r.requireLowAssets()||!line.contains("{threshold}")).map(line->Text.fill(line,v)).toList()));
            menu.actions.put(slot,new Action("loan",null,amount,0));
        }
        int repay=c().ui().getInt("ui.lender.repay-slot"),status=c().ui().getInt("ui.lender.status-slot"),close=c().ui().getInt("ui.lender.close-slot");
        button(menu,repay,GameConfig.material(c().ui(),"ui.lender.repay-icon",false),c().ui().getString("ui.lender.repay-name"),new Action("repay",null,-1,0));
        menu.inventory.setItem(status,item(GameConfig.material(c().ui(),"ui.lender.status-icon",false),c().ui().getString("ui.lender.status-name"),c().ui().getStringList("ui.lender.status-lore").stream().map(line->Text.fill(line,v)).toList()));
        menu.actions.put(status,new Action("debt",null,0,0));
        button(menu,close,GameConfig.material(c().ui(),"ui.lender.close-icon",false),c().ui().getString("ui.lender.close-name"),new Action("close",null,0,0));
        if(p.hasPermission("stock.admin"))button(menu,c().ui().getInt("ui.lender.admin-slot"),GameConfig.material(c().ui(),"ui.lender.admin-icon",false),c().ui().getString("ui.lender.admin-name"),new Action("loanadmin",null,0,0));
    }
    private void fill(Player p,Menu menu) {
        menu.inventory.clear();menu.actions.clear();
        if(menu.lender){fillLender(p,menu);return;}
        if(menu.stock==null) {
            var quotes=new ArrayList<>(plugin.market().quotes.values());int first=menu.page*45;
            for(int slot=0;slot<45 && first+slot<quotes.size();slot++) {
                var q=quotes.get(first+slot);Map<String,String> values=quoteValues(p,q);
                menu.inventory.setItem(slot,item(q.spec.icon(),(q.delisted?"&8":"&f")+q.spec.name(),c().ui().getStringList("ui.stock-lore").stream().map(line->Text.fill(line,values)).toList()));
                menu.actions.put(slot,new Action("open",q.spec.id(),0,menu.page));
            }
            if(menu.page>0)button(menu,45,Material.ARROW,c().msg("button-previous"),new Action("page",null,0,menu.page-1));
            if(first+45<quotes.size())button(menu,53,Material.ARROW,c().msg("button-next"),new Action("page",null,0,menu.page+1));
            var values=accountValues(p);menu.inventory.setItem(49,item(Material.GOLD_INGOT,c().msg("button-portfolio"),List.of(Text.fill(c().msg("portfolio-header"),values))));
            menu.actions.put(49,new Action("portfolio",null,0,0));
            button(menu,c().ui().getInt("ui.work-shop-slot"),GameConfig.material(c().ui(),"ui.work-shop-icon",false),c().ui().getString("ui.work-shop-name"),new Action("workshop",null,0,0));
        } else {
            var q=plugin.market().quotes.get(menu.stock);if(q==null){p.closeInventory();return;}
            var values=quoteValues(p,q);menu.inventory.setItem(4,item(q.spec.icon(),"&f"+q.spec.name(),c().ui().getStringList("ui.stock-lore").stream().map(line->Text.fill(line,values)).toList()));
            int i=0;for(int quantity:c().rules().quantities()) {
                button(menu,10+i,Material.RED_CONCRETE,c().msg("button-buy").replace("{quantity}",""+quantity),new Action("buy",menu.stock,quantity,0));
                button(menu,28+i,Material.BLUE_CONCRETE,c().msg("button-sell").replace("{quantity}",""+quantity),new Action("sell",menu.stock,quantity,0));i++;
            }
            button(menu,16,Material.REDSTONE_BLOCK,c().msg("button-buy-all"),new Action("buy",menu.stock,-1,0));
            button(menu,34,Material.LAPIS_BLOCK,c().msg("button-sell-all"),new Action("sell",menu.stock,-1,0));
            button(menu,49,Material.ARROW,c().msg("button-back"),new Action("page",null,0,menu.page));
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent e) {
        if(!(e.getView().getTopInventory().getHolder() instanceof Menu menu))return;
        e.setCancelled(true);if(!(e.getWhoClicked() instanceof Player p)||!p.getUniqueId().equals(menu.player))return;
        if(e.getClick()!=ClickType.LEFT && e.getClick()!=ClickType.RIGHT)return;
        var action=menu.actions.get(e.getRawSlot());if(action==null)return;
        Bukkit.getScheduler().runTask(plugin,()->choose(p,menu,action));
    }
    private void choose(Player p,Menu menu,Action action) {
        if(!p.isOnline()||p.getOpenInventory().getTopInventory().getHolder()!=menu)return;
        try {
            switch(action.type) {
                case "open" -> open(p,action.stock,action.page);
                case "page" -> open(p,null,action.page);
                case "portfolio" -> portfolio(p);
                case "close" -> p.closeInventory();
                case "loanadmin" -> plugin.openLoanAdmin(p);
                case "workshop" -> plugin.openWorkShop(p);
                case "loan","repay","debt" -> {plugin.lenderAction(p,action.type,action.quantity);fill(p,menu);refreshPlayer(p);}
                case "buy","sell" -> {
                    plugin.executeTrade(p,action.stock,action.type.equals("buy"),action.quantity);
                    fill(p,menu);refreshPlayer(p);
                }
            }
        } catch(Exception ex) { plugin.tell(p,c().msg("error").replace("{reason}",ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage())); }
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent e) { if(e.getView().getTopInventory().getHolder() instanceof Menu)e.setCancelled(true); }
    void portfolio(Player p) {
        plugin.tell(p,Text.fill(c().msg("portfolio-header"),accountValues(p)));
        plugin.market().account(p.getUniqueId()).holdings.forEach((id,holding)->plugin.tell(p,Text.fill(c().msg("portfolio-line"),quoteValues(p,plugin.market().quote(id)))));
    }
    /** 관전자/접속자에 관계없이 같은 전체 계정 목록으로 순위를 만듭니다. 개인 자산은 이 목록에 섞지 않습니다. */
    static List<String> sidebarRankingLines(Market market,GameConfig config) {
        if(!config.ui().getBoolean("ui.sidebar-ranking-enabled",true))return List.of();
        var ranks=market.rankings(config.map().getString("ranking.metric"),config.ui().getInt("ui.sidebar-ranking-limit",5));
        if(ranks.isEmpty())return List.of(config.msg("sidebar-ranking-empty"));
        List<String> lines=new ArrayList<>();
        for(int i=0;i<ranks.size();i++) {
            var rank=ranks.get(i);
            lines.add(Text.fill(config.msg("sidebar-ranking-row"),Map.of("rank",Integer.toString(i+1),"player",rank.name(),"money",Text.money(config,rank.money()))));
        }
        return List.copyOf(lines);
    }
    /** 사이드바 15줄은 개인 기본 정보 → 공개 순위 → 남은 칸의 보유 종목 순서로 배정합니다. */
    static List<String> sidebarLines(List<String> accountLines,List<String> rankingLines,List<String> holdingLines,long refreshCount) {
        List<String> lines=new ArrayList<>(15);
        for(String line:accountLines) { if(lines.size()==15)break;lines.add(line); }
        for(String line:rankingLines) { if(lines.size()==15)break;lines.add(line); }
        int capacity=15-lines.size();
        // 순위가 남은 칸을 모두 썼거나 보유 종목이 없을 때는 회전 계산을 하지 않습니다.
        if(capacity>0&&!holdingLines.isEmpty()) {
            int start=holdingLines.size()>capacity?(int)Math.floorMod(refreshCount/3*capacity,(long)holdingLines.size()):0;
            for(int i=0;i<Math.min(capacity,holdingLines.size());i++)lines.add(holdingLines.get((start+i)%holdingLines.size()));
        }
        return List.copyOf(lines);
    }
    /** 기본 자산 페이지와 보유 종목 한 개씩을 같은 간격으로 순환합니다. */
    static String actionbarLine(GameConfig config,String base,List<String> holdings,long elapsedTicks) {
        if(!config.ui().getBoolean("ui.actionbar-holdings-enabled",true)||holdings.isEmpty())return base;
        int pageTicks=config.ui().getInt("ui.actionbar-page-ticks",60);
        int page=(int)Math.floorMod(Math.floorDiv(elapsedTicks,pageTicks),(long)holdings.size()+1);
        return page==0?base:holdings.get(page-1);
    }
    void refresh() { refreshCount++;for(Player p:Bukkit.getOnlinePlayers())if(!p.hasMetadata("NPC"))refreshPlayer(p);actionbarElapsedTicks+=c().rules().refreshTicks(); }
    void refreshPlayer(Player p) {
        if(p.hasMetadata("NPC"))return;
        Scoreboard board=boards.computeIfAbsent(p.getUniqueId(),id->{previous.put(id,p.getScoreboard());var b=Bukkit.getScoreboardManager().getNewScoreboard();b.registerNewObjective("stock","dummy",Text.component(c().ui().getString("ui.scoreboard-title"))).setDisplaySlot(DisplaySlot.SIDEBAR);p.setScoreboard(b);return b;});
        var objective=board.getObjective("stock");objective.displayName(Text.component(c().ui().getString("ui.scoreboard-title")));
        // 15, 14, ... 점수는 줄 정렬에만 사용하고 오른쪽에는 숨깁니다. 템플릿의 실제 금액은 그대로 표시합니다.
        objective.numberFormat(NumberFormat.blank());
        var v=accountValues(p);List<String> accountLines=new ArrayList<>();for(String template:c().ui().getStringList("messages.sidebar"))accountLines.add(Text.fill(template,v));
        if(plugin.market().account(p.getUniqueId()).debt!=null)accountLines.add(Math.max(0,accountLines.size()-1),Text.fill(c().msg("sidebar-debt"),v));
        List<String> holdingLines=new ArrayList<>();
        if(c().ui().getBoolean("ui.sidebar-holdings-enabled",false))
            for(String id:plugin.market().account(p.getUniqueId()).holdings.keySet())holdingLines.add(Text.fill(c().msg("holding-line"),quoteValues(p,plugin.market().quote(id))));
        var lines=sidebarLines(accountLines,sidebarRankingLines(plugin.market(),c()),holdingLines,refreshCount);
        for(int i=0;i<15;i++) {
            String entry=ChatColor.values()[i].toString();var team=board.getTeam("l"+i);if(team==null){team=board.registerNewTeam("l"+i);team.addEntry(entry);}
            if(i<lines.size()){team.prefix(Text.component(lines.get(i)));objective.getScore(entry).setScore(15-i);}else board.resetScores(entry);
        }
        List<String> actionbarHoldings=new ArrayList<>();
        if(c().ui().getBoolean("ui.actionbar-holdings-enabled",true))
            for(String id:plugin.market().account(p.getUniqueId()).holdings.keySet())actionbarHoldings.add(Text.fill(c().msg("actionbar-holding"),quoteValues(p,plugin.market().quote(id))));
        p.sendActionBar(Text.component(actionbarLine(c(),Text.fill(c().msg("actionbar"),v),actionbarHoldings,actionbarElapsedTicks)));
        if(p.getOpenInventory().getTopInventory().getHolder() instanceof Menu menu)fill(p,menu);
    }
    void remove(Player p) {
        var own=boards.remove(p.getUniqueId());var old=previous.remove(p.getUniqueId());if(own!=null&&p.getScoreboard()==own&&old!=null)p.setScoreboard(old);
    }
    void closeMenus() { for(Player p:Bukkit.getOnlinePlayers())if(p.getOpenInventory().getTopInventory().getHolder() instanceof Menu)p.closeInventory(); }
    void close() { closeMenus();for(Player p:Bukkit.getOnlinePlayers())remove(p); }
}
