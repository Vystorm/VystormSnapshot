package at.esoren.vystormsnapshot;

import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._uiMethods.autoGui.GuiInteface;
import Vystorm.vystorm_core.compat.CompatInterface;
import Vystorm.vystorm_core.panel.PanelsInterface;

/** A Core hub for tests without a server: its parts are Core's defaults, which delegate to the static classes. */
final class TestCore {
    private TestCore() {}

    static API api() {
        API core = mock(API.class);
        GuiInteface gui = mock(GuiInteface.class);
        when(gui.panels()).thenReturn(PanelsInterface.DEFAULT);
        when(core.compat()).thenReturn(new CompatInterface() {});
        when(core.gui()).thenReturn(gui);
        return core;
    }
}
