package kr.hetagram.stock;

import org.bukkit.Material;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkAreaSafetyTest {
    @Test void initialInstallationAcceptsOnlyEmptySpaceOrOriginalGroundProfile() {
        assertTrue(WorkArea.baseline(Material.AIR,4));assertTrue(WorkArea.baseline(Material.VOID_AIR,-4));
        assertTrue(WorkArea.baseline(Material.GRASS_BLOCK,0));assertTrue(WorkArea.baseline(Material.DIRT,-1));assertTrue(WorkArea.baseline(Material.BEDROCK,-2));
        for(Material structure:new Material[]{Material.CHEST,Material.STONE_BRICKS,Material.GLASS,Material.OAK_PLANKS})
            for(int y=-4;y<=4;y++)assertFalse(WorkArea.baseline(structure,y));
        assertFalse(WorkArea.baseline(Material.GRASS_BLOCK,1));assertFalse(WorkArea.baseline(Material.DIRT,0));assertFalse(WorkArea.baseline(Material.BEDROCK,-1));
    }
    @Test void exactRegionBoundsExcludeUnrelatedNearbyConstruction() {
        var box=new WorkArea.Box(34,60,5,42,67,13);
        assertTrue(box.contains(34,60,5));assertTrue(box.contains(42,67,13));assertFalse(box.contains(33,64,9));assertFalse(box.contains(38,68,9));assertFalse(box.contains(38,64,14));
    }
    @Test void adjacentPathsAreSafeButSharingAnyBlockIsCollision() {
        var mine=new WorkArea.Box(34,60,5,42,67,13);var path=new WorkArea.Box(27,62,8,33,66,10);
        assertFalse(mine.overlaps(path));assertTrue(mine.overlaps(new WorkArea.Box(42,64,9,43,65,10)));
        assertFalse(mine.overlaps(new WorkArea.Box(34,68,5,42,70,13)));
    }
    @Test void regenerationWaitsForBothFeetAndHeadSpaceInsteadOfTrappingPlayer() {
        var player=new BoundingBox(35.2,62,9.2,35.8,63.8,9.8);
        assertTrue(WorkArea.intersectsBody(35,62,9,player));assertTrue(WorkArea.intersectsBody(35,63,9,player));
        assertFalse(WorkArea.intersectsBody(35,61,9,player));assertFalse(WorkArea.intersectsBody(35,64,9,player));assertFalse(WorkArea.intersectsBody(36,62,9,player));
    }
}
