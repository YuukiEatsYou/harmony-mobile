import { Capacitor, registerPlugin } from '@capacitor/core';

interface HarmonyDiagnosticsPlugin {
  /** Returns the on-device call diagnostics as plain text. */
  getReport(): Promise<{ report: string }>;
  /** Shares the text via the Android share sheet. */
  shareReport(options: { text: string }): Promise<void>;
  /** Opens the system battery-optimization list. */
  openBatterySettings(): Promise<void>;
  /** Clears the rolling event log. */
  clearLog(): Promise<void>;
  /** Connects the native voice gateway and reads the rooms (milestone-1 check). */
  testVoice(): Promise<void>;
  /** Disconnects the native voice gateway. */
  stopVoice(): Promise<void>;
  /** Joins a voice room and runs the call natively (milestone-2 check). */
  voiceJoin(): Promise<void>;
  /** Leaves the native call. */
  voiceLeave(): Promise<void>;
}

const HarmonyDiagnostics = registerPlugin<HarmonyDiagnosticsPlugin>('HarmonyDiagnostics');

/**
 * The on-device call diagnostics (see CallDiagnostics.kt). These exist for the
 * OEM ROMs that kill a live call, where the affected person cannot run adb; the
 * report is read from and shared on the phone itself.
 */
export async function diagnosticsReport(): Promise<string> {
  if (!Capacitor.isNativePlatform()) {
    return 'Call diagnostics are only available in the Android app.';
  }
  const { report } = await HarmonyDiagnostics.getReport();
  return report;
}

export async function shareDiagnostics(text: string): Promise<void> {
  if (!Capacitor.isNativePlatform()) return;
  await HarmonyDiagnostics.shareReport({ text });
}

export async function openBatterySettings(): Promise<void> {
  if (!Capacitor.isNativePlatform()) return;
  await HarmonyDiagnostics.openBatterySettings();
}

export async function clearDiagnostics(): Promise<void> {
  if (!Capacitor.isNativePlatform()) return;
  await HarmonyDiagnostics.clearLog();
}

export async function testVoice(): Promise<void> {
  if (!Capacitor.isNativePlatform()) return;
  await HarmonyDiagnostics.testVoice();
}

export async function stopVoice(): Promise<void> {
  if (!Capacitor.isNativePlatform()) return;
  await HarmonyDiagnostics.stopVoice();
}

export async function voiceJoin(): Promise<void> {
  if (!Capacitor.isNativePlatform()) return;
  await HarmonyDiagnostics.voiceJoin();
}

export async function voiceLeave(): Promise<void> {
  if (!Capacitor.isNativePlatform()) return;
  await HarmonyDiagnostics.voiceLeave();
}
