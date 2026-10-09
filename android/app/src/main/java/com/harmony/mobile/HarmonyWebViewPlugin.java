package com.harmony.mobile;

import android.content.Intent;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import com.harmony.mobile.InstanceWebViewActivity;

/**
 * Opens an instance's own web client in {@link InstanceWebViewActivity} — a
 * webview this app owns, so the screen-share shim can be injected into it. This
 * replaces the generic in-app browser plugin, which offered no injection seam.
 */
@CapacitorPlugin(name = "HarmonyWebView")
public class HarmonyWebViewPlugin extends Plugin {
    @PluginMethod
    public void openInstance(PluginCall call) {
        String url = call.getString("url");
        if (url == null || url.isEmpty()) {
            call.reject("A url is required.");
            return;
        }
        if (getActivity() == null) {
            call.reject("No activity to launch from.");
            return;
        }
        Intent intent = new Intent(getActivity(), InstanceWebViewActivity.class);
        intent.putExtra(InstanceWebViewActivity.EXTRA_URL, url);
        getActivity().startActivity(intent);
        call.resolve();
    }
}
