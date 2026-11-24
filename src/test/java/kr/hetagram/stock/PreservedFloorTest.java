package kr.hetagram.stock;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class PreservedFloorTest {
    @TempDir Path directory;
    private static final class Floor implements PreservedFloor.Reader {
        final Map<String,Material> blocks=new HashMap<>();
        void put(int x,int y,int z,Material material) {blocks.put(x+","+y+","+z,material);}
        public Material at(int x,int y,int z) {return blocks.getOrDefault(x+","+y+","+z,Material.AIR);}
    }
    @Test void fiveToElevenStocksAddTwoRowsOfFloorWithoutMovingTheExistingRow() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml"))try(var input=getClass().getResourceAsStream("/"+name)){Files.copy(Objects.requireNonNull(input),directory.resolve(name));}
        var original=GameConfig.load(directory.toFile());var c=original.map();c.set("origin.x",0);c.set("origin.z",0);c.set("origin.y",64);
        c.set("layout.style","kiosks");c.set("kiosk.columns",5);c.set("kiosk.spacing",10);c.set("kiosk.row-spacing",16);c.set("kiosk.margin",10);
        var stocks=new LinkedHashMap<String,GameConfig.Stock>();var sample=original.stocks().values().iterator().next();
        for(int i=0;i<5;i++)stocks.put("fixture-"+i,sample);
        var five=PreservedFloor.bounds(new GameConfig(original.rules(),stocks,original.news(),original.ui(),c));
        for(int i=5;i<11;i++)stocks.put("fixture-"+i,sample);
        var eleven=PreservedFloor.bounds(new GameConfig(original.rules(),stocks,original.news(),original.ui(),c));
        assertEquals(new PreservedFloor.Bounds(-30,-24,30,30,64),five);
        assertEquals(new PreservedFloor.Bounds(-30,-24,30,62,64),eleven);
        var plan=PreservedFloor.plan(eleven,five,Material.GRASS_BLOCK,new Floor());
        assertEquals(61*32*3,plan.fills().size());assertTrue(plan.fills().stream().allMatch(fill->fill.z()>30));
    }
    @Test void existingHolesAndCustomBuildingsInsideTheRecordedFootprintStayUntouched() {
        var old=new PreservedFloor.Bounds(0,0,1,1,64);var wanted=new PreservedFloor.Bounds(0,0,1,2,64);var floor=new Floor();
        floor.put(0,64,0,Material.CHEST);var before=new HashMap<>(floor.blocks);
        var plan=PreservedFloor.plan(wanted,old,Material.GRASS_BLOCK,floor);
        assertEquals(6,plan.fills().size());assertTrue(plan.fills().stream().allMatch(fill->fill.z()==2));assertEquals(before,floor.blocks);
    }
    @Test void customFoundationColumnsAreNotBuriedByNewGrassOrDirt() {
        var floor=new Floor();floor.put(1,63,0,Material.CHEST);floor.put(2,64,0,Material.STONE);
        var plan=PreservedFloor.plan(new PreservedFloor.Bounds(0,0,2,0,64),new PreservedFloor.Bounds(0,0,0,0,64),Material.GRASS_BLOCK,floor);
        assertTrue(plan.fills().isEmpty());assertEquals(Material.CHEST,floor.at(1,63,0));assertEquals(Material.AIR,floor.at(1,64,0));
    }
    @Test void interruptedExtensionsResumeOnlyTheStillAirLayersOutsideTheOldFootprint() {
        var floor=new Floor();floor.put(1,62,0,Material.BEDROCK);floor.put(1,63,0,Material.DIRT);
        var plan=PreservedFloor.plan(new PreservedFloor.Bounds(0,0,1,0,64),new PreservedFloor.Bounds(0,0,0,0,64),Material.GRASS_BLOCK,floor);
        assertEquals(List.of(new PreservedFloor.Fill(1,64,0,Material.GRASS_BLOCK)),plan.fills());
    }
    @Test void legacyMarkersInferTheOldOuterFloorAndPreserveAnIntentionalHoleInsideIt() {
        var floor=new Floor();for(int x=0;x<=2;x++)for(int z=0;z<=2;z++)if(x!=1||z!=1)floor.put(x,64,z,Material.GRASS_BLOCK);
        var plan=PreservedFloor.plan(new PreservedFloor.Bounds(0,0,2,3,64),null,Material.GRASS_BLOCK,floor);
        assertEquals(new PreservedFloor.Bounds(0,0,2,2,64),plan.previous());assertEquals(9,plan.fills().size());
        assertTrue(plan.fills().stream().allMatch(fill->fill.z()==3));
    }
    @Test void shrinkingTheDisplayDoesNotForgetOwnershipOfThePreviousLargerFloor() {
        var old=new PreservedFloor.Bounds(-30,-24,30,62,64);var smaller=new PreservedFloor.Bounds(-30,-24,30,30,64);
        var plan=PreservedFloor.plan(smaller,old,Material.GRASS_BLOCK,new Floor());
        assertEquals(old,plan.retained());assertTrue(plan.fills().isEmpty());
    }
    @Test void markerBoundsRoundtripAndWrongHeightOrMalformedBoundsAreRejected() {
        var bounds=new PreservedFloor.Bounds(-30,-24,30,62,64);
        assertEquals(bounds,PreservedFloor.readBounds("complete\nabc\ntraders:player\n"+bounds.marker()));
        assertNull(PreservedFloor.readBounds("complete\nlegacy"));
        assertThrows(IllegalArgumentException.class,()->PreservedFloor.readBounds("floor-bounds:1,2,3"));
        assertThrows(IllegalArgumentException.class,()->PreservedFloor.readBounds("floor-bounds:2,0,1,0,64"));
        assertThrows(IllegalArgumentException.class,()->PreservedFloor.plan(new PreservedFloor.Bounds(0,0,1,1,65),bounds,Material.GRASS_BLOCK,new Floor()));
    }
}
