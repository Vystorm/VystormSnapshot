package at.esoren.vystormsnapshot;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import Vystorm.vystorm_core.lang.Lang;
import java.util.*;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

class CaptureValidationTest {
    private static void field(Object target,String name,Object value) throws Exception {
        var f=SnapshotPlugin.class.getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
    @Test void unloadedAndNonFiniteConsolePositionsDoNotQueuePhotos() throws Exception {
        var plugin=mock(SnapshotPlugin.class,CALLS_REAL_METHODS);var lang=mock(Lang.class);
        var sender=mock(CommandSender.class);var world=mock(World.class);var jobs=new ArrayList<PhotoJob>();
        field(plugin,"lang",lang);field(plugin,"jobs",jobs);field(plugin,"maxJobs",3);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(()->Bukkit.getWorld("test")).thenReturn(world);
            plugin.capture(sender,new String[]{"capture","test","0","64","0","0","0"});
            verify(lang).send(sender,"capture-unloaded");
            plugin.capture(sender,new String[]{"capture","test","NaN","64","0","0","0"});
            verify(lang).send(sender,"usage-capture");
            assertTrue(jobs.isEmpty());
            field(plugin,"maxJobs",1);jobs.add(mock(PhotoJob.class));
            plugin.capture(sender,new String[]{"capture","test","0","64","0","0","0"});
            verify(lang).send(sender,"busy");assertEquals(1,jobs.size());
        }
    }
}
