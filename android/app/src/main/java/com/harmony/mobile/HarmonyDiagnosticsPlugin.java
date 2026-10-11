package com.harmony.mobile;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Exposes the on-device call diagnostics (see {@link CallDiagnostics}) to the
 * shell, so a person with no PC can read them and send them with the normal
 * Android share sheet instead of needing adb.
 */
@CapacitorPlugin(name = "HarmonyDiagnostics")
public class HarmonyDiagnosticsPlugin extends Plugin {

    @PluginMethod
    public void getReport(PluginCall call) {
        Context context = getContext();
        if (context == null) {
            call.reject("No context available.");
            return;
        }
        JSObject result = new JSObject();
        result.put("report", CallDiagnostics.INSTANCE.snapshot(context));
        call.resolve(result);
    }

    @PluginMethod
    public void shareReport(PluginCall call) {
        Context context = getContext();
        if (context == null) {
            call.reject("No context available.");
            return;
        }
        String text = call.getString("text", "");
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "Harmony call diagnostics");
        send.putExtra(Intent.EXTRA_TEXT, text);
        Intent chooser = Intent.createChooser(send, "Share diagnostics");
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(chooser);
        call.resolve();
    }

    /**
     * Opens battery settings. When the app is not yet exempt this asks directly
     * -- the dialog that actually exempts it -- because white-listing is the one
     * thing people on aggressive ROMs can do; already-exempt devices get the list.
     * The general list needs no permission, and a ROM without it falls back to the
     * app's own settings page.
     */
    @PluginMethod
    public void openBatterySettings(PluginCall call) {
        Context context = getContext();
        if (context == null) {
            call.reject("No context available.");
            return;
        }
        Intent intent;
        if (BatteryOptimization.INSTANCE.isExempt(context)) {
            intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        } else {
            intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.fromParts("package", context.getPackageName(), null));
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (Exception e) {
            Intent details = new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.getPackageName(), null)
            );
            details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(details);
        }
        call.resolve();
    }

    @PluginMethod
    public void clearLog(PluginCall call) {
        Context context = getContext();
        if (context != null) {
            CallDiagnostics.INSTANCE.clear(context);
        }
        call.resolve();
    }

    /** Milestone-1 check: connect the native voice gateway and read the rooms. */
    @PluginMethod
    public void testVoice(PluginCall call) {
        Context context = getContext();
        if (context == null) {
            call.reject("No context available.");
            return;
        }
        NativeVoice.INSTANCE.probe(context);
        call.resolve();
    }

    @PluginMethod
    public void stopVoice(PluginCall call) {
        Context context = getContext();
        if (context != null) {
            NativeVoice.INSTANCE.stop(context);
        }
        call.resolve();
    }

    /** Milestone-2 check: run an actual native call in a voice room. */
    @PluginMethod
    public void voiceJoin(PluginCall call) {
        Context context = getContext();
        if (context == null) {
            call.reject("No context available.");
            return;
        }
        NativeVoice.INSTANCE.join(context);
        call.resolve();
    }

    @PluginMethod
    public void voiceLeave(PluginCall call) {
        Context context = getContext();
        if (context != null) {
            NativeVoice.INSTANCE.leave(context);
        }
        call.resolve();
    }
}
