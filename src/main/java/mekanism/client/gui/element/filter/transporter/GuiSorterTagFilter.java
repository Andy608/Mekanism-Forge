package mekanism.client.gui.element.filter.transporter;

import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.filter.GuiTagFilter;
import mekanism.common.content.transporter.SorterTagFilter;
import mekanism.common.tile.TileEntityLogisticalSorter;

public class GuiSorterTagFilter extends GuiTagFilter<SorterTagFilter, TileEntityLogisticalSorter> implements GuiSorterFilterHelper {

    public static GuiSorterTagFilter create(IGuiWrapper gui, TileEntityLogisticalSorter tile) {
        return new GuiSorterTagFilter(gui, (gui.getWidth() - 152) / 2, 15, tile, null);
    }

    public static GuiSorterTagFilter edit(IGuiWrapper gui, TileEntityLogisticalSorter tile, SorterTagFilter filter) {
        return new GuiSorterTagFilter(gui, (gui.getWidth() - 152) / 2, 15, tile, filter);
    }

    private GuiSorterTagFilter(IGuiWrapper gui, int x, int y, TileEntityLogisticalSorter tile, SorterTagFilter origFilter) {
        super(gui, x, y, 152, 90, tile, origFilter);
    }

    @Override
    protected void init() {
        super.init();
        addSorterDefaults(guiObj, filter, getSlotOffset(), this::addChild);
    }

    @Override
    protected SorterTagFilter createNewFilter() {
        return new SorterTagFilter();
    }
}