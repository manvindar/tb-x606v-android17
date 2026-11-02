package org.tablet.tuner;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Long press on the Hub mode tile: open Settings, Screen saver, where the screen saver for charging is chosen. */
public class HubPrefsActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        startActivity(new Intent("android.settings.DREAM_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }
}
