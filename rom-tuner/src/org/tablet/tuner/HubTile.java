package org.tablet.tuner;

import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick settings tile that starts the screen saver (Hub mode) at once, as if the tablet had been docked. */
public class HubTile extends TileService {
    private void render() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setIcon(Icon.createWithResource(this, R.drawable.ic_hub));
        t.setLabel("Hub mode");
        t.setSubtitle("Start now");
        t.setState(Tile.STATE_INACTIVE);
        t.updateTile();
    }

    @Override public void onStartListening() { render(); }

    @Override public void onClick() {
        Cfg.request(this, "hub_mode", "1");
    }
}
