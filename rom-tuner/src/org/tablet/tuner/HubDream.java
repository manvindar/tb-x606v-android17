package org.tablet.tuner;

import android.service.dreams.DreamService;

/** Screen saver (Hub mode while charging): see {@link HubScreen}. */
public class HubDream extends DreamService {
    private HubScreen screen;

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);       // a touch ends the screen saver
        setFullscreen(true);
        setScreenBright(true);
        screen = new HubScreen(this);
        setContentView(screen.build());
    }

    @Override public void onDreamingStarted() { super.onDreamingStarted(); screen.start(); }

    @Override public void onDreamingStopped() { screen.stop(); super.onDreamingStopped(); }
}
