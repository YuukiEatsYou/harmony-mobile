package com.harmony.mobile;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Local (app-owned) plugin that opens an instance in our own webview,
        // which can inject the screen-share shim. See InstanceWebViewActivity.
        registerPlugin(HarmonyWebViewPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
