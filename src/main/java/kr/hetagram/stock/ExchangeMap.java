package kr.hetagram.stock;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.io.File;
import java.util.*;

/** 별도의 빈 월드에 거래소를 만듭니다. 건설과 차트 블록 갱신은 모두 메인 스레드입니다. */
final class ExchangeMap {
    private final StockPlugin plugin;
    private final NamespacedKey owner;
    private World world;
    private BukkitTask builder;
    private boolean ready;
    private boolean restored,clearLegacyPedestals;
    private TextDisplay entrance;
    private TextDisplay leaderboard;
    private TextDisplay rankingBoard;
    private NpcTraders npcTraders;
    private PluginChunkPins chunkPins;
    private final List<Panel> panels=new ArrayList<>();
    private final List<Kiosk> kiosks=new ArrayList<>();
    private final Map<org.bukkit.block.Block,String> terminalBlocks=new HashMap<>();
    private final Map<UUID,Map<String,TextDisplay>> portfolios=new HashMap<>();
    private final List<Entity> decorations=new ArrayList<>();
    private long tickerTick;
    private record Edit(int x,int y,int z,Material material) {}
    private record Panel(String stock,int x,int y,int z,TextDisplay label) {}
    private static final class TickerCell {
        final TextDisplay display;
        String text="";
        boolean parked=true;
        long parkedAt=Long.MIN_VALUE,retireAt=Long.MAX_VALUE,activateAt=Long.MAX_VALUE;
        String pendingText;
        double pendingY;
        int pendingDuration;
        TickerCell(TextDisplay display) {this.display=display;}
    }
    private static final class Kiosk {
        final String stock;
        final double x,z;
        final TextDisplay name;
        final List<TickerCell> rows=new ArrayList<>();
        TickerRows<Object> ticker;
        Market.Quote quote;
        long initialPrice;
        Kiosk(String stock,double x,double z,TextDisplay name) {this.stock=stock;this.x=x;this.z=z;this.name=name;}
    }
    ExchangeMap(StockPlugin plugin) { this.plugin=plugin; owner=new NamespacedKey(plugin,"exchange");chunkPins=new PluginChunkPins(plugin); }
    static final class VoidGenerator extends ChunkGenerator {
        @Override public boolean shouldGenerateNoise() { return false; }
        @Override public boolean shouldGenerateSurface() { return false; }
        @Override public boolean shouldGenerateBedrock() { return false; }
        @Override public boolean shouldGenerateCaves() { return false; }
        @Override public boolean shouldGenerateDecorations() { return false; }
        @Override public boolean shouldGenerateMobs() { return false; }
        @Override public boolean shouldGenerateStructures() { return false; }
        @Override public Location getFixedSpawnLocation(World world,Random random) { return new Location(world,0,65,15); }
    }
    World world() { return world; }
    boolean ready() { return ready; }
    Location spawn() {
        var c=plugin.settings().map(); return new Location(world,c.getInt("origin.x")+.5,c.getInt("origin.y")+1,c.getInt("origin.z")+c.getInt("layout.spawn-z-offset")+.5,180,0);
    }
    boolean owns(Entity entity) { return npcTraders!=null&&npcTraders.stock(entity)!=null||entity.getPersistentDataContainer().has(owner,PersistentDataType.STRING); }
    String stock(Entity entity) {
        String npcStock=npcTraders==null?null:npcTraders.stock(entity);
        return npcStock!=null?npcStock:entity.getPersistentDataContainer().get(owner,PersistentDataType.STRING);
    }
    String stock(org.bukkit.block.Block block) { return terminalBlocks.get(block); }
    /** 거래 기능이 직접 그리는 블록만 예약합니다. 일반 건축은 이 영역 밖에서 자유롭습니다. */
    boolean reserved(Block block) {
        if(terminalBlocks.containsKey(block))return true;
        if(!block.getWorld().equals(world)||kioskStyle())return false;
        int w=plugin.settings().map().getInt("chart.width"),h=plugin.settings().map().getInt("chart.height");
        return panels.stream().anyMatch(p->block.getZ()==p.z&&block.getX()>=p.x-1&&block.getX()<=p.x+w&&block.getY()>=p.y-1&&block.getY()<=p.y+h);
    }
    static boolean reuseCompletedMap(String saved,String signature,boolean preserve) {
        return saved.startsWith("complete\n")&&(preserve||saved.equals(signature));
    }
    static boolean isLegacyPedestal(List<Material> blocks,Material expected) {
        return !blocks.isEmpty()&&blocks.stream().allMatch(material->material==expected);
    }
    private boolean kioskStyle() { return plugin.settings().map().getString("layout.style","wall").equals("kiosks"); }
    private boolean playerTraders() {return kioskStyle()&&plugin.settings().map().getString("kiosk.display-mode","block").equals("player");}
    /** 종목 reload는 기존 NPC를 지우기 전에 새 위치의 바닥/몸 공간을 검증해야 합니다. 블록은 쓰지 않습니다. */
    void preflight(GameConfig next) throws Exception {
        GameConfig.require(ready&&world!=null,"거래소 준비가 끝난 뒤 종목을 변경하세요");
        GameConfig.require(world.getName().equals(next.map().getString("world-name")),"맵 월드 변경은 재시작 후 반영하세요");
        var wanted=PreservedFloor.bounds(next);
        try(var pins=new PluginChunkPins(plugin)) {
            pins.pinRectangle(world,wanted.minX(),wanted.minZ(),wanted.maxX(),wanted.maxZ());
            String saved=java.nio.file.Files.readString(new File(world.getWorldFolder(),"stockgame-map.txt").toPath());
            var expansion=floorExpansion(next,saved);verifyTerminalSites(next,expansion,saved);
        }
    }
    private PreservedFloor.Plan floorExpansion(GameConfig config,String saved) {
        var c=config.map();Material top=Material.valueOf(c.getString(c.getString("layout.style","wall").equals("kiosks")?"kiosk.floor":"palette.floor"));
        return PreservedFloor.plan(PreservedFloor.bounds(config),PreservedFloor.readBounds(saved),top,(x,y,z)->world.getBlockAt(x,y,z).getType());
    }
    private void verifyTerminalSites(GameConfig config,PreservedFloor.Plan expansion,String saved) {
        var c=config.map();boolean kiosks=c.getString("layout.style","wall").equals("kiosks"),players=kiosks&&c.getString("kiosk.display-mode","block").equals("player");
        var planned=new HashMap<String,Material>();for(var fill:expansion.fills())planned.put(fill.x()+","+fill.y()+","+fill.z(),fill.material());
        int floor=c.getInt("origin.y"),index=0;
        for(var stock:config.stocks().values()) {
            Location feet;
            if(kiosks)feet=kioskLocation(config,index,players?c.getDouble("kiosk.npc-height"):1);
            else {
                int cols=c.getInt("layout.panel-columns"),w=c.getInt("chart.width"),gap=c.getInt("layout.panel-gap"),margin=c.getInt("layout.floor-margin");
                int width=cols*w+(cols-1)*gap+margin*2;
                feet=new Location(world,c.getInt("origin.x")-width/2+margin+(index%cols)*(w+gap)+w/2.0,floor+1,c.getInt("origin.z")+(index/cols)*c.getInt("layout.row-gap")+6.5);
            }
            index++;
            GameConfig.require(world.getWorldBorder().isInside(feet),"새 거래원 위치가 월드 경계 밖입니다: "+stock.name());
            boolean legacy=false;int pedestalLeft=0,pedestalBack=0,pedestalWidth=c.getInt("kiosk.pedestal-width"),pedestalHeight=c.getInt("kiosk.pedestal-height");
            if(kiosks&&(!players||!saved.contains("\ntraders:player"))) {
                pedestalLeft=(int)Math.round(feet.getX()-pedestalWidth/2.0);pedestalBack=(int)Math.round(feet.getZ()-pedestalWidth/2.0);
                var materials=new ArrayList<Material>();
                for(int x=0;x<pedestalWidth;x++)for(int z=0;z<pedestalWidth;z++)for(int y=1;y<=pedestalHeight;y++)materials.add(world.getBlockAt(pedestalLeft+x,floor+y,pedestalBack+z).getType());
                legacy=isLegacyPedestal(materials,stock.pedestal());
            }
            for(int x=(int)Math.floor(feet.getX()-.3);x<=(int)Math.floor(feet.getX()+.3-1e-7);x++)
                for(int z=(int)Math.floor(feet.getZ()-.3);z<=(int)Math.floor(feet.getZ()+.3-1e-7);z++) {
                    Material ground=planned.getOrDefault(x+","+floor+","+z,world.getBlockAt(x,floor,z).getType());
                    GameConfig.require(ground.isSolid(),"거래원 아래 기존 바닥이 비어 있거나 확장 건축물과 충돌합니다: "+stock.name()+" ("+x+","+floor+","+z+")");
                    for(int y=(int)Math.floor(feet.getY());y<=(int)Math.floor(feet.getY()+1.8-1e-7);y++) {
                        var block=world.getBlockAt(x,y,z);
                        boolean pedestal=legacy&&x>=pedestalLeft&&x<pedestalLeft+pedestalWidth&&z>=pedestalBack&&z<pedestalBack+pedestalWidth&&y>floor&&y<=floor+pedestalHeight;
                        GameConfig.require(pedestal||block.isPassable()&&!block.isLiquid(),"새 거래원 위치에 기존 건축물이 있습니다: "+stock.name()+" ("+x+","+y+","+z+")");
                    }
                }
        }
    }
    void open() throws Exception {
        if(chunkPins==null)chunkPins=new PluginChunkPins(plugin);
        var c=plugin.settings().map(); String name=c.getString("world-name");
        if(playerTraders()) {
            // Citizens API 클래스는 플러그인이 실제로 활성화됐을 때만 로드합니다.
            GameConfig.require(Bukkit.getPluginManager().isPluginEnabled("Citizens"),"플레이어 거래원 모드에는 Paper 1.20.6용 Citizens가 필요합니다. Citizens 설치 후 서버를 재시작하세요");
            npcTraders=new NpcTraders(plugin);
        }
        // 동일한 이름의 기존 일반 월드를 덮어쓰지 않도록 전용 마커를 검사합니다.
        File folder=new File(Bukkit.getWorldContainer(),name), marker=new File(folder,"stockgame-map.txt");
        GameConfig.require(!folder.exists() || marker.exists(),"기존 월드 이름과 충돌: "+name+". map.yml에 다른 world-name을 지정하세요");
        world=Bukkit.createWorld(new WorldCreator(name).generator(new VoidGenerator()).generateStructures(false));
        GameConfig.require(world!=null,"거래소 월드 생성 실패");
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE,false); world.setGameRule(GameRule.DO_WEATHER_CYCLE,false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING,false); world.setGameRule(GameRule.DO_FIRE_TICK,false);
        world.setGameRule(GameRule.KEEP_INVENTORY,true); world.setGameRule(GameRule.DO_INSOMNIA,false);
        world.setTime(kioskStyle()?c.getInt("kiosk.time"):6000); world.setStorm(false); world.setPVP(false); world.setDifficulty(Difficulty.PEACEFUL);
        int floor=c.getInt("origin.y"); Location spawn=spawn(); world.setSpawnLocation(spawn);
        world.getWorldBorder().setCenter(spawn.getX(),spawn.getZ()); world.getWorldBorder().setSize(2000);
        // 건설 도중 종료되면 다음 실행에서 같은 전용 월드를 안전하게 복구합니다.
        String baseSignature="complete\n"+Integer.toHexString((c.saveToString()+plugin.market().quotes.keySet()).hashCode())+"\ntraders:"+(playerTraders()?"player":"block");
        var footprint=PreservedFloor.bounds(plugin.settings());
        String expectedSignature=baseSignature+"\n"+footprint.marker();
        String saved=marker.exists()?java.nio.file.Files.readString(marker.toPath()):"";
        boolean built=reuseCompletedMap(saved,expectedSignature,c.getBoolean("layout.preserve-builds",true));restored=built;
        chunkPins.pinRectangle(world,footprint.minX(),footprint.minZ(),footprint.maxX(),footprint.maxZ());
        PreservedFloor.Plan expansion=null;
        if(built) {
            expansion=floorExpansion(plugin.settings(),saved);verifyTerminalSites(plugin.settings(),expansion,saved);
            footprint=expansion.retained();
        }
        chunkPins.pinRectangle(world,footprint.minX(),footprint.minZ(),footprint.maxX(),footprint.maxZ());
        final String signature=baseSignature+"\n"+footprint.marker();
        clearLegacyPedestals=built&&playerTraders()&&!saved.contains("\ntraders:player");
        if(!built)java.nio.file.Files.writeString(marker.toPath(),"building");
        for(Entity e:world.getEntities())if(owns(e))e.remove();
        if(built) {
            if(PreservedFloor.readBounds(saved)==null&&expansion.previous()!=null)writeMarker(marker,saved+"\n"+expansion.previous().marker());
            restorePreserved(marker,signature,expansion,saved);
            return;
        }
        if(kioskStyle()){buildKiosks(marker,signature);return;}
        ArrayDeque<Edit> edits=new ArrayDeque<>();
        int w=c.getInt("chart.width"),h=c.getInt("chart.height"),cols=c.getInt("layout.panel-columns"),gap=c.getInt("layout.panel-gap"),margin=c.getInt("layout.floor-margin");
        int rows=(plugin.market().quotes.size()+cols-1)/cols,totalWidth=cols*w+(cols-1)*gap+margin*2;
        int minX=c.getInt("origin.x")-totalWidth/2,maxX=minX+totalWidth,minZ=c.getInt("origin.z")-margin,maxZ=c.getInt("origin.z")+rows*c.getInt("layout.row-gap");
        int roof=floor+c.getInt("layout.ceiling-height");
        // 종목 추가로 실내가 길어질 때 예전 끝벽이 새 거래원 앞을 막지 않도록 엽니다.
        // 통로 중앙에 끝벽이 있는 행만 처리하며, 패널/프레임은 아래에서 다시 그립니다.
        int probeY=floor+2;
        for(int z=c.getInt("origin.z")+c.getInt("layout.row-gap");z<maxZ;z+=c.getInt("layout.row-gap")) {
            Material expected=mat(probeY%5==0?"palette.trim":"palette.wall");
            if(world.getBlockAt(c.getInt("origin.x"),probeY,z).getType()!=expected)continue;
            for(int x=minX+1;x<maxX;x++)for(int y=floor+1;y<roof;y++) {
                Material old=world.getBlockAt(x,y,z).getType();
                if(old==mat("palette.wall")||old==mat("palette.trim"))edits.add(new Edit(x,y,z,Material.AIR));
            }
        }
        for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++) {
            Material base=(Math.abs(x-c.getInt("origin.x"))<=2)?mat("palette.aisle"):mat("palette.floor");
            edits.add(new Edit(x,floor,z,base));
            edits.add(new Edit(x,roof,z,(Math.floorMod(x,8)==0&&Math.floorMod(z,8)==0)?mat("palette.light"):mat("palette.roof")));
        }
        for(int y=floor+1;y<roof;y++) {
            for(int x=minX;x<=maxX;x++)for(int z:new int[]{minZ,maxZ})edits.add(new Edit(x,y,z,y%5==0?mat("palette.trim"):mat("palette.wall")));
            for(int z=minZ;z<=maxZ;z++)for(int x:new int[]{minX,maxX})edits.add(new Edit(x,y,z,(y>floor+3&&y<roof-2)?mat("palette.glass"):mat("palette.wall")));
        }
        int index=0;
        for(String id:plugin.market().quotes.keySet()) {
            int x=minX+margin+(index%cols)*(w+gap),z=c.getInt("origin.z")+(index/cols)*c.getInt("layout.row-gap"),y=floor+c.getInt("chart.floor-offset");
            for(int dx=-1;dx<=w;dx++)for(int dy=-1;dy<=h;dy++)edits.add(new Edit(x+dx,y+dy,z,(dx==-1||dx==w||dy==-1||dy==h)?mat("palette.trim"):mat("chart.background")));
            for(int dx=0;dx<5;dx++)for(int dy=1;dy<=2;dy++)edits.add(new Edit(x+w/2-2+dx,floor+dy,z+8,mat("palette.counter")));
            index++;
        }
        plugin.getLogger().info("거래소 건설 예약: "+edits.size()+" 블록");
        builder=Bukkit.getScheduler().runTaskTimer(plugin,()->{
            for(int i=0;i<c.getInt("layout.build-blocks-per-tick")&&!edits.isEmpty();i++) {
                Edit e=edits.removeFirst(); set(e.x,e.y,e.z,e.material);
            }
            if(edits.isEmpty()) {
                builder.cancel();builder=null;
                try { finish(); world.save(); java.nio.file.Files.writeString(marker.toPath(),signature); }
                catch(Exception ex) { plugin.getLogger().severe("맵 마무리 실패: "+ex.getMessage()); }
            }
        },1,1);
    }
    private static void writeMarker(File marker,String text) throws Exception {
        var path=marker.toPath();var temporary=path.resolveSibling(path.getFileName()+".tmp");
        try {
            java.nio.file.Files.writeString(temporary,text,java.nio.charset.StandardCharsets.UTF_8);
            try {java.nio.file.Files.move(temporary,path,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
            catch(java.nio.file.AtomicMoveNotSupportedException unsupported){java.nio.file.Files.move(temporary,path,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
        }finally {java.nio.file.Files.deleteIfExists(temporary);}
    }
    private void restorePreserved(File marker,String signature,PreservedFloor.Plan plan,String saved) {
        var edits=new ArrayDeque<>(plan.fills());var config=plugin.settings();
        plugin.getLogger().info("보존 거래소 바닥 확장 예약: "+edits.size()+" 빈 블록");
        builder=Bukkit.getScheduler().runTaskTimer(plugin,()->{
            try {
                for(int count=0;count<config.map().getInt("layout.build-blocks-per-tick")&&!edits.isEmpty();count++) {
                    var edit=edits.removeFirst();var block=world.getBlockAt(edit.x(),edit.y(),edit.z());
                    GameConfig.require(block.getType().isAir()||block.getType()==edit.material(),"바닥 확장 중 새 건축물과 충돌: "+edit.x()+","+edit.y()+","+edit.z());
                    if(block.getType().isAir())block.setType(edit.material(),false);
                }
                if(edits.isEmpty()) {
                    builder.cancel();builder=null;
                    // 사전 검사 뒤 건설 도중 다른 플레이어가 놓은 블록도 덮어쓰거나 NPC에 묻지 않습니다.
                    verifyTerminalSites(config,new PreservedFloor.Plan(plan.previous(),plan.retained(),List.of()),saved);
                    finish();world.save();writeMarker(marker,signature);
                }
            }catch(Exception failure) {
                if(builder!=null){builder.cancel();builder=null;}
                plugin.getLogger().log(java.util.logging.Level.SEVERE,"거래소 보존 확장 실패. 기존 건축을 유지합니다",failure);
                Bukkit.getPluginManager().disablePlugin(plugin);
            }
        },1,1);
    }
    private Material mat(String key) { return Material.valueOf(plugin.settings().map().getString(key)); }
    private void set(int x,int y,int z,Material material) { Block b=world.getBlockAt(x,y,z); if(b.getType()!=material)b.setType(material,false); }
    private TextDisplay label(Location location,String text) {
        return label(location,text,true);
    }
    private TextDisplay label(Location location,String text,boolean visible) {
        chunkPins.pin(location);
        TextDisplay display=world.spawn(location,TextDisplay.class,e->{e.setPersistent(false);e.setVisibleByDefault(visible);e.setBillboard(Display.Billboard.FIXED); e.setRotation(0,0); e.setSeeThrough(false);e.setShadowed(true);e.setLineWidth(500);e.text(Text.component(text));e.getPersistentDataContainer().set(owner,PersistentDataType.STRING,"label");});
        decorations.add(display);return display;
    }
    private void finish() {
        if(kioskStyle()){finishKiosks();return;}
        var c=plugin.settings().map(); int w=c.getInt("chart.width"),h=c.getInt("chart.height"),cols=c.getInt("layout.panel-columns"),gap=c.getInt("layout.panel-gap"),margin=c.getInt("layout.floor-margin");
        int totalWidth=cols*w+(cols-1)*gap+margin*2,minX=c.getInt("origin.x")-totalWidth/2,floor=c.getInt("origin.y"),index=0;
        for(var q:plugin.market().quotes.values()) {
            int x=minX+margin+(index%cols)*(w+gap),z=c.getInt("origin.z")+(index/cols)*c.getInt("layout.row-gap"),y=floor+c.getInt("chart.floor-offset");
            panels.add(new Panel(q.spec.id(),x,y,z,label(new Location(world,x+w/2.0,y+h+2,z+.6),q.spec.name())));
            Villager v=world.spawn(new Location(world,x+w/2.0,floor+1,z+6.5,0,0),Villager.class,e->{
                e.setAI(false);e.setInvulnerable(true);e.setSilent(true);e.setCollidable(false);e.setPersistent(false);
                e.setProfession(Villager.Profession.LIBRARIAN);e.customName(Text.component(c.getString("text.terminal").replace("{name}",q.spec.name())));e.setCustomNameVisible(true);
                e.getPersistentDataContainer().set(owner,PersistentDataType.STRING,q.spec.id());
            });decorations.add(v);index++;
        }
        Location sp=spawn();
        entrance=label(sp.clone().add(0,4,-3),newsText());
        ready=true; render(); plugin.getLogger().info("거래소 준비 완료: "+world.getName()+", "+panels.size()+"개 전광판");
        if(!restored)for(var p:Bukkit.getOnlinePlayers())if(!p.hasMetadata("NPC")&&p.getWorld().equals(world))p.teleport(sp);
    }
    void render() {
        if(!ready)return; var c=plugin.settings().map();int w=c.getInt("chart.width"),h=c.getInt("chart.height");
        refreshNews();
        if(kioskStyle()){renderKiosks();return;}
        for(Entity e:decorations)if(e instanceof Villager) {
            var q=plugin.market().quotes.get(stock(e));if(q!=null)e.customName(Text.component(c.getString("text.terminal").replace("{name}",q.spec.name())));
        }
        for(Panel panel:panels) {
            var q=plugin.market().quotes.get(panel.stock); if(q==null)continue;
            var history=new ArrayList<>(q.history);if(history.size()>w)history=new ArrayList<>(history.subList(history.size()-w,history.size()));
            long min=history.stream().mapToLong(Market.Candle::low).min().orElse(q.price),max=history.stream().mapToLong(Market.Candle::high).max().orElse(q.price);
            double padding=Math.max(1,(max-min)*.1),bottom=Math.max(0,min-padding),span=Math.max(1,max+padding-bottom);
            int offset=w-history.size();
            for(int dx=0;dx<w;dx++)for(int dy=0;dy<h;dy++) {
                Material material=mat(dy%4==0?"chart.grid":"chart.background");
                if(dx>=offset) {
                    var candle=history.get(dx-offset); int o=scale(candle.open(),bottom,span,h),cl=scale(candle.close(),bottom,span,h);
                    if(dy>=Math.min(o,cl)&&dy<=Math.max(o,cl))material=mat(candle.close()>candle.open()?"chart.up":candle.close()<candle.open()?"chart.down":"chart.flat");
                }
                set(panel.x+dx,panel.y+dy,panel.z,material);
            }
            panel.label.text(Text.component(Text.fill(c.getString("text.panel"),Map.of("name",q.spec.name(),"price",Text.money(plugin.settings(),q.price),"change",Text.percent(plugin.settings(),q.change()),"color",Text.color(plugin.settings(),q.change()),"state",plugin.settings().msg(q.delisted?"state-delisted":plugin.market().running?"state-open":"state-closed"),"count",Integer.toString(history.size())))));
        }
    }
    private int scale(long value,double min,double span,int height) { return Math.max(0,Math.min(height-1,(int)Math.round((value-min)/span*(height-1)))); }
    private Location kioskLocation(int index,double height) {
        return kioskLocation(plugin.settings(),index,height);
    }
    private Location kioskLocation(GameConfig config,int index,double height) {
        var c=config.map();int columns=c.getInt("kiosk.columns");
        double x=c.getInt("origin.x")+(index%columns-(columns-1)/2.0)*c.getDouble("kiosk.spacing");
        double z=c.getInt("origin.z")+index/columns*c.getDouble("kiosk.row-spacing");
        return new Location(world,x,c.getInt("origin.y")+height,z);
    }
    private void buildKiosks(File marker,String signature) {
        var c=plugin.settings().map();var edits=new ArrayDeque<Edit>();int floor=c.getInt("origin.y"),columns=c.getInt("kiosk.columns");
        int rows=(plugin.market().quotes.size()+columns-1)/columns,margin=c.getInt("kiosk.margin");
        int half=(int)Math.ceil((columns-1)*c.getDouble("kiosk.spacing")/2)+margin;
        int minZ=c.getInt("origin.z")-margin-14,maxZ=c.getInt("origin.z")+(int)Math.ceil((rows-1)*c.getDouble("kiosk.row-spacing"))+30;
        for(int x=c.getInt("origin.x")-half;x<=c.getInt("origin.x")+half;x++)for(int z=minZ;z<=maxZ;z++) {
            edits.add(new Edit(x,floor-2,z,Material.BEDROCK));edits.add(new Edit(x,floor-1,z,Material.DIRT));edits.add(new Edit(x,floor,z,mat("kiosk.floor")));
        }
        int index=0,width=c.getInt("kiosk.pedestal-width"),height=c.getInt("kiosk.pedestal-height");
        for(var q:plugin.market().quotes.values()) {
            Location center=kioskLocation(index++,0);int left=(int)Math.round(center.getX()-width/2.0),back=(int)Math.round(center.getZ()-width/2.0);
            // 플레이어 모드 전환 시 이 거래소가 소유한 받침대 공간만 비워 풀밭에 서게 합니다.
            for(int x=0;x<width;x++)for(int z=0;z<width;z++)for(int y=1;y<=height;y++)edits.add(new Edit(left+x,floor+y,back+z,playerTraders()?Material.AIR:q.spec.pedestal()));
        }
        plugin.getLogger().info("블록 거래소 건설 예약: "+edits.size()+" 블록");
        builder=Bukkit.getScheduler().runTaskTimer(plugin,()->{
            for(int i=0;i<c.getInt("layout.build-blocks-per-tick")&&!edits.isEmpty();i++){var e=edits.removeFirst();set(e.x,e.y,e.z,e.material);}
            if(edits.isEmpty()){
                builder.cancel();builder=null;
                try{finishKiosks();world.save();java.nio.file.Files.writeString(marker.toPath(),signature);}
                catch(Exception e){plugin.getLogger().log(java.util.logging.Level.SEVERE,"거래소 생성 실패",e);Bukkit.getPluginManager().disablePlugin(plugin);}
            }
        },1,1);
    }
    private TextDisplay hologram(Location location,String text,double scale,int lineWidth,boolean visible) {
        var display=label(location,text,visible);display.setBillboard(Display.Billboard.CENTER);display.setDefaultBackground(false);
        display.setBackgroundColor(Color.fromARGB(0));display.setBrightness(new Display.Brightness(15,15));display.setLineWidth(lineWidth);
        float s=(float)scale;
        display.setTransformation(new Transformation(new Vector3f(),new Quaternionf(),new Vector3f(s,s,s),new Quaternionf()));
        return display;
    }
    private void finishKiosks() {
        if(clearLegacyPedestals){clearLegacyPedestals();clearLegacyPedestals=false;}
        var c=plugin.settings().map();int index=0,floor=c.getInt("origin.y"),width=c.getInt("kiosk.pedestal-width"),height=c.getInt("kiosk.pedestal-height");
        for(var q:plugin.market().quotes.values()) {
            Location base=kioskLocation(index,0);
            var name=hologram(kioskLocation(index,c.getDouble("kiosk.name-height")),"&f&l"+q.spec.name(),1,c.getInt("kiosk.name-line-width"),true);
            var kiosk=new Kiosk(q.spec.id(),base.getX(),base.getZ(),name);kiosks.add(kiosk);createTicker(kiosk);
            if(playerTraders()) {
                Location feet=kioskLocation(index,c.getDouble("kiosk.npc-height"));feet.setYaw((float)c.getDouble("kiosk.npc-yaw"));
                chunkPins.pin(feet);
                npcTraders.spawn(q.spec.id(),feet,q.spec.skinName());
            } else {
                ItemDisplay block=world.spawn(kioskLocation(index,c.getDouble("kiosk.block-height")),ItemDisplay.class,e->{
                    e.setItemStack(new ItemStack(q.spec.displayBlock()));e.setPersistent(false);e.setBrightness(new Display.Brightness(15,15));
                    float s=(float)c.getDouble("kiosk.block-scale");e.setTransformation(new Transformation(new Vector3f(),new Quaternionf(),new Vector3f(s,s,s),new Quaternionf()));
                    e.getPersistentDataContainer().set(owner,PersistentDataType.STRING,q.spec.id());
                });decorations.add(block);
                Interaction hit=world.spawn(kioskLocation(index,1),Interaction.class,e->{e.setPersistent(false);e.setInteractionWidth(width+.2f);e.setInteractionHeight((float)c.getDouble("kiosk.block-height"));e.setResponsive(true);e.getPersistentDataContainer().set(owner,PersistentDataType.STRING,q.spec.id());});decorations.add(hit);
                int left=(int)Math.round(base.getX()-width/2.0),back=(int)Math.round(base.getZ()-width/2.0);
                for(int dx=0;dx<width;dx++)for(int dz=0;dz<width;dz++)for(int y=1;y<=height;y++)terminalBlocks.put(world.getBlockAt(left+dx,floor+y,back+dz),q.spec.id());
            }
            index++;
        }
        leaderboard=hologram(new Location(world,c.getInt("origin.x")+c.getDouble("kiosk.leaderboard-x"),floor+c.getDouble("kiosk.leaderboard-y"),c.getInt("origin.z")+c.getDouble("kiosk.leaderboard-z")),"",c.getDouble("kiosk.leaderboard-scale"),c.getInt("kiosk.leaderboard-line-width"),true);
        entrance=hologram(spawn().clone().add(c.getDouble("kiosk.news-offset-x"),c.getDouble("kiosk.news-offset-y"),c.getDouble("kiosk.news-offset-z")),newsText(),c.getDouble("kiosk.news-scale"),c.getInt("kiosk.news-line-width"),true);
        if(c.getBoolean("ranking.world-enabled"))rankingBoard=hologram(new Location(world,c.getInt("origin.x")+c.getDouble("ranking.x"),floor+c.getDouble("ranking.y"),c.getInt("origin.z")+c.getDouble("ranking.z")),"",c.getDouble("ranking.scale"),c.getInt("ranking.line-width"),true);
        ready=true;for(var kiosk:kiosks)updateTicker(kiosk);renderKiosks();
        plugin.getLogger().info("블록 거래소 준비 완료: "+world.getName()+", "+kiosks.size()+"개 받침대, "+kiosks.size()+"개 실시간 가격 이력, 전체 시세판 1개");
        if(!restored)for(var p:Bukkit.getOnlinePlayers())if(!p.hasMetadata("NPC")&&p.getWorld().equals(world))p.teleport(spawn());
    }
    private void clearLegacyPedestals() {
        var c=plugin.settings().map();int index=0,width=c.getInt("kiosk.pedestal-width"),height=c.getInt("kiosk.pedestal-height"),floor=c.getInt("origin.y");
        for(var q:plugin.market().quotes.values()) {
            Location center=kioskLocation(index++,0);int left=(int)Math.round(center.getX()-width/2.0),back=(int)Math.round(center.getZ()-width/2.0);
            var blocks=new ArrayList<Block>();
            for(int x=0;x<width;x++)for(int z=0;z<width;z++)for(int y=1;y<=height;y++)blocks.add(world.getBlockAt(left+x,floor+y,back+z));
            if(isLegacyPedestal(blocks.stream().map(Block::getType).toList(),q.spec.pedestal()))for(Block block:blocks)block.setType(Material.AIR,false);
        }
    }
    private String tickerColor(double change) {return plugin.settings().map().getString(change>0?"kiosk.rise-color":change<0?"kiosk.fall-color":"kiosk.flat-color");}
    private String tickerSymbol(double change) {
        var c=plugin.settings().map();return c.getString(change==0?"kiosk.flat-symbol":change>0?(change>=c.getDouble("kiosk.large-change-percent")?"kiosk.large-rise-symbol":"kiosk.rise-symbol"):(-change>=c.getDouble("kiosk.large-change-percent")?"kiosk.large-fall-symbol":"kiosk.fall-symbol"));
    }
    private String tickerPrice(long price) {return java.math.BigDecimal.valueOf(price,2).setScale(plugin.settings().ui().getInt("ui.money-decimals",0),java.math.RoundingMode.HALF_UP).toPlainString();}
    private double tickerBase() {var c=plugin.settings().map();return c.getInt("origin.y")+c.getDouble("kiosk.history-height");}
    private double tickerGap() {var c=plugin.settings().map();return c.getDouble("kiosk.line-spacing")*c.getDouble("kiosk.text-scale");}
    private void createTicker(Kiosk kiosk) {
        for(var cell:kiosk.rows){cell.display.remove();decorations.remove(cell.display);}kiosk.rows.clear();
        kiosk.ticker=new TickerRows<>(plugin.settings().map().getInt("kiosk.history-lines"));
        var c=plugin.settings().map();
        for(int i=0;i<kiosk.ticker.poolSize();i++)kiosk.rows.add(new TickerCell(hologram(new Location(world,kiosk.x,tickerBase()-tickerGap(),kiosk.z),"",c.getDouble("kiosk.text-scale"),260,true)));
        kiosk.quote=null;
    }
    private void tickerText(TickerCell cell,String text) {
        if(!cell.text.equals(text)){cell.display.text(Text.component(text));cell.text=text;}
    }
    private void tickerMove(Kiosk kiosk,TickerCell cell,double y,int duration) {
        cell.display.setTeleportDuration(duration);
        cell.display.teleport(new Location(world,kiosk.x,y,kiosk.z));
    }
    /** 빈 행을 화면 아래에 미리 놓습니다. 다음 서버 틱 전에는 글자를 넣지 않아 재사용 점프가 보이지 않습니다. */
    private void parkTicker(Kiosk kiosk,TickerCell cell) {
        tickerText(cell,"");cell.pendingText=null;cell.activateAt=Long.MAX_VALUE;cell.retireAt=Long.MAX_VALUE;
        if(!cell.parked){tickerMove(kiosk,cell,tickerBase()-tickerGap(),0);cell.parked=true;cell.parkedAt=tickerTick;}
    }
    private void showTicker(Kiosk kiosk,TickerCell cell,String text,double y,int duration) {
        cell.pendingText=null;cell.activateAt=Long.MAX_VALUE;cell.retireAt=Long.MAX_VALUE;
        tickerText(cell,text);tickerMove(kiosk,cell,y,duration);cell.parked=false;
    }
    private String tickerRow(Kiosk kiosk,Object value) {
        if(value instanceof Market.Candle candle) {
            double change=candle.open()==0?0:100.0*(candle.close()-candle.open())/candle.open();
            return tickerColor(change)+tickerSymbol(change)+" "+tickerPrice(candle.close());
        }
        return tickerColor(0)+tickerSymbol(0)+" "+tickerPrice(kiosk.initialPrice);
    }
    /** 가격 엔진/블록 차트 주기와 별개로 1서버 틱마다 확인하며, 변경된 행만 전송합니다. */
    void tickTickers() {
        tickerTick++;
        if(!ready||!kioskStyle())return;
        for(var kiosk:kiosks)updateTicker(kiosk);
    }
    private void updateTicker(Kiosk kiosk) {
        var c=plugin.settings().map();var q=plugin.market().quotes.get(kiosk.stock);if(q==null)return;
        if(kiosk.rows.size()!=c.getInt("kiosk.history-lines")+2)createTicker(kiosk);
        // 한 번 이동이 다음 가격 변화보다 길지 않게 해 0초/0.1초 설정에서도 행이 계속 뒤처지지 않습니다.
        int duration=(int)Math.min(c.getInt("kiosk.scroll-ticks"),GameConfig.intervalMillis(plugin.settings().rules().tickSeconds())/50);
        for(var cell:kiosk.rows) {
            if(cell.retireAt<=tickerTick)parkTicker(kiosk,cell);
            if(cell.pendingText!=null&&cell.activateAt<=tickerTick)showTicker(kiosk,cell,cell.pendingText,cell.pendingY,cell.pendingDuration);
        }
        if(kiosk.quote!=q){kiosk.quote=q;kiosk.initialPrice=q.history.isEmpty()?q.price:q.history.getFirst().open();}
        var values=new ArrayList<Object>();values.add(q);values.addAll(q.history);
        var frame=kiosk.ticker.update(q,values);double gap=tickerGap(),base=tickerBase();
        if(frame.snap()) {
            for(var cell:kiosk.rows)parkTicker(kiosk,cell);
            for(var row:frame.rows())showTicker(kiosk,kiosk.rows.get(row.slot()),tickerRow(kiosk,row.value()),base+row.toLevel()*gap,0);
            return;
        }
        if(!frame.changed()) {
            // 색상/표시 소수 자리 reload는 위치 애니메이션을 다시 시작하지 않고 내용만 갱신합니다.
            for(var row:frame.rows()) {
                var cell=kiosk.rows.get(row.slot());String text=tickerRow(kiosk,row.value());
                if(cell.pendingText!=null)cell.pendingText=text;else tickerText(cell,text);
            }
            return;
        }
        for(var row:frame.rows()) {
            var cell=kiosk.rows.get(row.slot());String text=tickerRow(kiosk,row.value());double y=base+row.toLevel()*gap;
            cell.retireAt=Long.MAX_VALUE;
            if(duration==0)showTicker(kiosk,cell,text,y,0);
            else if(cell.pendingText!=null){cell.pendingText=text;cell.pendingY=y;cell.pendingDuration=duration;}
            else if(row.entering()) {
                if(cell.parked&&cell.parkedAt<tickerTick)showTicker(kiosk,cell,text,y,duration);
                else {
                    parkTicker(kiosk,cell);cell.pendingText=text;cell.pendingY=y;cell.pendingDuration=duration;cell.activateAt=tickerTick+1;
                }
            } else showTicker(kiosk,cell,text,y,duration);
        }
        for(var exit:frame.exits()) {
            var cell=kiosk.rows.get(exit.slot());
            if(duration==0||cell.parked)parkTicker(kiosk,cell);
            else {cell.pendingText=null;cell.activateAt=Long.MAX_VALUE;tickerMove(kiosk,cell,base+exit.toLevel()*gap,duration);cell.retireAt=tickerTick+duration;}
        }
    }
    private void renderKiosks() {
        var c=plugin.settings().map();
        if(npcTraders!=null)npcTraders.syncSkins();
        for(var kiosk:kiosks){
            var q=plugin.market().quote(kiosk.stock);kiosk.name.text(Text.component(q.delisted?c.getString("text.delisted-name").replace("{name}",q.spec.name()):"&f&l"+q.spec.name()));
        }
        var board=new StringBuilder(c.getString("text.leaderboard-title"));
        for(var q:plugin.market().quotes.values())board.append("\n").append(Text.fill(c.getString(q.delisted?"text.delisted-row":"text.leaderboard-row"),Map.of("name",q.spec.name(),"price",tickerPrice(q.price),"change",Text.percent(plugin.settings(),q.change()),"color",tickerColor(q.change()),"symbol",tickerSymbol(q.change()))));
        if(leaderboard!=null)leaderboard.text(Text.component(board.toString()));
        refreshNews();renderRanking();
    }
    private String newsText() {
        var c=plugin.settings().map();String headline=plugin.market().latestHeadline.isBlank()?c.getString("text.news-idle"):plugin.market().latestHeadline;
        return c.getString("text.news-panel").replace("{headline}",headline);
    }
    void refreshNews() {if(entrance!=null)entrance.text(Text.component(newsText()));}
    private void renderRanking() {
        if(rankingBoard==null)return;var c=plugin.settings().map();var ranks=plugin.market().rankings(c.getString("ranking.metric"),c.getInt("ranking.max-entries"));
        var text=new StringBuilder(c.getString("text.ranking-title"));int place=1;
        for(var rank:ranks)text.append("\n").append(Text.fill(c.getString("text.ranking-row"),Map.of("rank",Integer.toString(place++),"player",rank.name(),"money",Text.money(plugin.settings(),rank.money()))));
        if(ranks.isEmpty())text.append("\n").append(c.getString("text.ranking-empty"));rankingBoard.text(Text.component(text.toString()));
    }
    void refreshPortfolios() {
        if(!ready||!kioskStyle())return;
        renderRanking();
        for(var p:Bukkit.getOnlinePlayers()) {
            if(p.hasMetadata("NPC"))continue;
            if(!p.getWorld().equals(world)){removePlayer(p.getUniqueId());continue;}
            var displays=portfolios.computeIfAbsent(p.getUniqueId(),id->new HashMap<>());var account=plugin.market().account(p.getUniqueId());var c=plugin.settings().map();
            for(var kiosk:kiosks){
                var display=displays.get(kiosk.stock);
                if(display==null){display=hologram(new Location(world,kiosk.x+c.getDouble("kiosk.portfolio-x"),c.getInt("origin.y")+c.getDouble("kiosk.portfolio-y"),kiosk.z+.2),"",.85,240,false);p.showEntity(plugin,display);displays.put(kiosk.stock,display);}
                var pos=account.holdings.getOrDefault(kiosk.stock,new Market.Position(0,0));long value=plugin.market().positionValue(kiosk.stock,pos),profit=value-pos.cost;
                display.text(Text.component(Text.fill(c.getString("text.portfolio"),Map.of("quantity",Long.toString(pos.quantity),"average",Text.money(plugin.settings(),pos.average()),"profit",Text.money(plugin.settings(),profit),"return",Text.percent(plugin.settings(),Market.returns(value,pos.cost)),"color",tickerColor(profit)))));
            }
        }
    }
    void removePlayer(UUID id) {var displays=portfolios.remove(id);if(displays!=null)for(var display:displays.values()){display.remove();decorations.remove(display);}}
    boolean hasPlayerTraders() {return ready&&npcTraders!=null;}
    void updateSkin(String stock,String skinName,java.util.function.Consumer<String> feedback) {
        GameConfig.require(hasPlayerTraders(),"플레이어 거래원 모드의 NPC가 준비된 뒤 사용하세요");
        npcTraders.updateSkin(stock,skinName,feedback);
    }
    void close() {
        ready=false;if(builder!=null){builder.cancel();builder=null;}
        try {if(npcTraders!=null){npcTraders.close();npcTraders=null;}panels.clear();kiosks.clear();terminalBlocks.clear();portfolios.clear();decorations.forEach(Entity::remove);decorations.clear();entrance=null;leaderboard=null;rankingBoard=null;}
        finally {if(chunkPins!=null){chunkPins.close();chunkPins=null;}}
    }
}
