package com.harmony.mobile;

import android.content.Intent;
import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    /** Cleared after the first resume, to defer once to a deep link we launched with. */
    private boolean firstResume = true;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Local (app-owned) plugin that opens an instance in our own webview,
        // which can inject the screen-share shim. See InstanceWebViewActivity.
        registerPlugin(HarmonyWebViewPlugin.class);
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onResume() {
        super.onResume();

        // A `harmony://` link launches us to open a specific instance, which the
        // shell's appUrlOpen handler does; never pre-empt it with the last one.
        boolean launchedWithLink = getIntent() != null && getIntent().getData() != null;
        boolean deferToLink = firstResume && launchedWithLink;
        firstResume = false;
        if (deferToLink) return;

        // Return to the instance that was last open. A cold start recreates this
        // shell, and a task that is merely resumed can come back with the
        // instance's activity gone; either way, resume it rather than showing the
        // selector. It is forgotten when the person leaves it on purpose.
        String url = InstanceSession.lastUrl(this);
        if (url == null) return;
        Intent intent = new Intent(this, InstanceWebViewActivity.class);
        intent.putExtra(InstanceWebViewActivity.EXTRA_URL, url);
        startActivity(intent);
    }
}
