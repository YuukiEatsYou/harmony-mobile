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
        // Exposes the on-device call diagnostics to the shell, so a device with
        // no adb can still report why a background call failed.
        registerPlugin(HarmonyDiagnosticsPlugin.class);
        // The push provider seam: endpoint, device registration, and tap
        // routing (docs/PUSH.md).
        registerPlugin(HarmonyPushPlugin.class);
        super.onCreate(savedInstanceState);

        // A notification tap that cold-started us: buffer it for the shell to
        // route once it has booted (docs/PUSH.md §5).
        PushStore.INSTANCE.consumeIntent(this, getIntent());
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // A tap that arrived while we were already alive (singleTask).
        setIntent(intent);
        PushStore.INSTANCE.consumeIntent(this, intent);
    }

    @Override
    public void onResume() {
        super.onResume();

        // Catch a tap extra that was already on the intent (e.g. delivered while
        // the process was starting); consumeIntent removes it so this runs once.
        PushStore.INSTANCE.consumeIntent(this, getIntent());

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
