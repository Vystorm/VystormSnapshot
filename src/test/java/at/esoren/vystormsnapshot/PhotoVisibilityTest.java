package at.esoren.vystormsnapshot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

class PhotoVisibilityTest {
    @Test void selfieIncludesPhotographerButExcludesPlayersHiddenFromThem() throws Exception {
        World world=mock(World.class);
        Player viewer=mock(Player.class),hidden=mock(Player.class);
        Location eye=new Location(world,0,2,0);
        when(world.getNearbyEntities(eye,32,32,32)).thenReturn(List.of(hidden,viewer));
        when(viewer.getBoundingBox()).thenReturn(new BoundingBox(-.3,0,-.3,.3,1.8,.3));
        when(viewer.getType()).thenReturn(EntityType.PLAYER);
        PhotoJob job=new PhotoJob(eye,null,viewer,70,32,1,1,0,Style.COLOR,Style.Frame.NONE,BlockTextures.EMPTY,null,pixels->{});
        var field=PhotoJob.class.getDeclaredField("targets");field.setAccessible(true);
        assertEquals(1,((List<?>)field.get(job)).size());
        verify(viewer).canSee((org.bukkit.entity.Entity)hidden);
        verify(hidden,never()).getBoundingBox();
    }
}
