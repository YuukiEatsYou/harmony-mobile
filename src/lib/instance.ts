import { Capacitor, registerPlugin } from '@capacitor/core';
import { buildInstanceUrl, type DeepLink } from './urls';

export type { DeepLink } from './urls';

interface HarmonyWebViewPlugin {
  /** Opens a url in the app's own instance webview. */
  openInstance(options: { url: string }): Promise<void>;
}

const HarmonyWebView = registerPlugin<HarmonyWebViewPlugin>('HarmonyWebView');

/**
 * Opens an instance's own client full-screen in a webview this app controls
 * (`InstanceWebViewActivity`), which is per-origin isolated and is where the
 * screen-share shim is injected. This replaces the generic in-app browser, which
 * had no seam to add `getDisplayMedia` to.
 */
export async function openInstance(origin: string, deepLink?: DeepLink): Promise<void> {
  const url = buildInstanceUrl(origin, deepLink);
  // In a desktop browser (`npm run dev`) there is no native webview; open a tab.
  if (!Capacitor.isNativePlatform()) {
    window.open(url, '_blank', 'noopener');
    return;
  }
  await HarmonyWebView.openInstance({ url });
}
