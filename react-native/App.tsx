import Slider from '@react-native-community/slider';
import { CameraView, useCameraPermissions } from 'expo-camera';
import { useKeepAwake } from 'expo-keep-awake';
import React, { useEffect, useRef, useState } from 'react';
import {
  AppState,
  BackHandler,
  Keyboard,
  KeyboardAvoidingView,
  Modal,
  Pressable,
  ScrollView,
  StatusBar,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import Touchpad from './src/Touchpad';
import { connect, pair, parsePairingQr } from './src/connection';
import type { Computer, ConnectionStatus } from './src/connection';
import { keyboardIssues, keyboardRecords, specialKeys } from './src/keyboard';
import type { KeyboardIssue, KeyboardKey } from './src/keyboard';
import { defaultSettings, load, saveComputer, saveSettings } from './src/storage';
import type { Settings } from './src/storage';

type Screen = 'loading' | 'pairing' | 'trackpad' | 'settings' | 'error';
type PairingStatus =
  | { kind: 'idle' | 'scanning' }
  | { kind: 'pairing' | 'error'; message: string };

const palette = {
  background: '#11161c',
  accent: '#a8e9ca',
  text: '#ecf3f9',
  muted: '#aab7c3',
  button: '#25303b',
  error: '#ffb3ab',
};

function Button({
  label,
  onPress,
  primary = false,
  disabled = false,
  accessibilityLabel = label,
}: {
  label: string;
  onPress: () => void;
  primary?: boolean;
  disabled?: boolean;
  accessibilityLabel?: string;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel}
      accessibilityState={{ disabled }}
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => [
        styles.button,
        primary && styles.primary,
        (pressed || disabled) && styles.dimmed,
      ]}
    >
      <Text style={[styles.buttonText, primary && styles.primaryText]}>{label}</Text>
    </Pressable>
  );
}

function Heading({ title }: { title: string }) {
  return (
    <View style={styles.heading}>
      <Text style={styles.brand}>NULLPTR</Text>
      <Text accessibilityRole="header" style={styles.title}>{title}</Text>
    </View>
  );
}

function PairingScreen({
  savedAddress,
  onPaired,
  onCancel,
}: {
  savedAddress: string | null;
  onPaired: (computer: Computer) => void;
  onCancel: () => void;
}) {
  const [address, setAddress] = useState(savedAddress ?? '');
  const [code, setCode] = useState('');
  const [status, setStatus] = useState<PairingStatus>({ kind: 'idle' });
  const [permission, requestPermission] = useCameraPermissions();
  const operation = useRef(false);
  const scanning = useRef(false);
  const busy = status.kind === 'scanning' || status.kind === 'pairing';

  async function scan() {
    if (operation.current) return;
    operation.current = true;
    Keyboard.dismiss();
    try {
      if (!(permission?.granted || (await requestPermission()).granted)) {
        setStatus({ kind: 'error', message: 'Allow camera access in your phone settings, or pair manually.' });
        return;
      }
      scanning.current = true;
      setStatus({ kind: 'scanning' });
    } catch {
      setStatus({ kind: 'error', message: 'Could not open the QR scanner.' });
    } finally {
      operation.current = false;
    }
  }

  async function submit(qr?: string) {
    if (operation.current || (qr !== undefined && !scanning.current)) return;
    scanning.current = false;
    operation.current = true;
    try {
      Keyboard.dismiss();
      const details = qr === undefined ? { address, code } : parsePairingQr(qr);
      if (qr !== undefined) {
        setAddress(details.address);
        setCode(details.code);
      }
      setStatus({ kind: 'pairing', message: `Pairing with ${details.address.trim()}…` });
      const computer = await pair(details.address, details.code);
      await saveComputer(computer);
      onPaired(computer);
    } catch (error) {
      setStatus({
        kind: 'error',
        message: error instanceof Error ? error.message : 'Could not pair with the computer.',
      });
    } finally {
      operation.current = false;
    }
  }

  function closeScanner(next: PairingStatus) {
    if (!scanning.current) return;
    scanning.current = false;
    setStatus(next);
  }

  return (
    <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={styles.form}>
      <Heading title="Pair a computer" />
      <Text style={styles.muted}>Run the companion on your computer with Tailscale connected.</Text>
      <Button label="Scan pairing QR" onPress={scan} disabled={busy} />
      {status.kind === 'scanning' && (
        <Modal onRequestClose={() => closeScanner({ kind: 'idle' })}>
          <SafeAreaView style={[styles.root, styles.content]}>
            <Text style={styles.label}>Scan the QR code printed by the companion</Text>
            <CameraView
              style={styles.pad}
              barcodeScannerSettings={{ barcodeTypes: ['qr'] }}
              onBarcodeScanned={({ data }) => { void submit(data); }}
              onMountError={() => closeScanner({ kind: 'error', message: 'Could not open the QR scanner.' })}
            />
            <Button label="Cancel" onPress={() => closeScanner({ kind: 'idle' })} />
          </SafeAreaView>
        </Modal>
      )}
      <Text style={styles.muted}>Or enter the details manually</Text>
      <View style={styles.field}>
        <Text style={styles.label}>Tailscale address</Text>
        <TextInput
          accessibilityLabel="Tailscale address"
          autoCapitalize="none"
          autoCorrect={false}
          editable={!busy}
          keyboardType="url"
          onChangeText={setAddress}
          placeholder="100.x.x.x:47822"
          placeholderTextColor={palette.muted}
          style={styles.input}
          value={address}
        />
      </View>
      <View style={styles.field}>
        <Text style={styles.label}>Pairing code</Text>
        <TextInput
          accessibilityLabel="Pairing code"
          autoCapitalize="none"
          autoCorrect={false}
          editable={!busy}
          keyboardType="visible-password"
          maxLength={12}
          onChangeText={setCode}
          onSubmitEditing={() => submit()}
          placeholder="12 characters"
          placeholderTextColor={palette.muted}
          style={styles.input}
          value={code}
        />
      </View>
      {'message' in status && (
        <Text accessibilityLiveRegion="polite" style={[styles.muted, status.kind === 'error' && styles.error]}>
          {status.message}
        </Text>
      )}
      <Button label="Pair computer" primary onPress={() => submit()} disabled={busy} />
      {savedAddress && <Button label="Back to trackpad" onPress={onCancel} disabled={busy} />}
    </ScrollView>
  );
}

function SettingsScreen({
  settings,
  onChange,
  onBack,
}: {
  settings: Settings;
  onChange: (update: (previous: Settings) => Settings) => void;
  onBack: () => void;
}) {
  return (
    <ScrollView contentContainerStyle={styles.form}>
      <Heading title="Settings" />
      <Text style={styles.label}>Pointer sensitivity · {40 + settings.sensitivity * 2}%</Text>
      <Slider
        accessibilityLabel="Pointer sensitivity"
        minimumValue={0}
        maximumValue={100}
        step={1}
        value={settings.sensitivity}
        minimumTrackTintColor={palette.accent}
        maximumTrackTintColor={palette.button}
        thumbTintColor={palette.accent}
        onValueChange={sensitivity => onChange(previous => ({ ...previous, sensitivity }))}
      />
      <View style={styles.row}>
        <Text style={styles.label}>Invert scroll</Text>
        <Switch
          accessibilityLabel="Invert scroll"
          value={settings.invertScroll}
          onValueChange={invertScroll => onChange(previous => ({ ...previous, invertScroll }))}
        />
      </View>
      <Text style={styles.muted}>Reverse the direction of two-finger scrolling.</Text>
      <Button label="Back to trackpad" onPress={onBack} />
    </ScrollView>
  );
}

export default function App() {
  useKeepAwake();
  const [screen, setScreen] = useState<Screen>('loading');
  const [computer, setComputer] = useState<Computer | null>(null);
  const [settings, setSettings] = useState(defaultSettings);
  const [settingsError, setSettingsError] = useState(false);
  const [status, setStatus] = useState<ConnectionStatus>({ kind: 'IDLE' });
  const [keyboardOpen, setKeyboardOpen] = useState(false);
  const [draft, setDraft] = useState('');
  const [keyboardNotice, setKeyboardNotice] = useState<string | null>(null);
  const connection = useRef<ReturnType<typeof connect> | null>(null);
  const settingsWrites = useRef(Promise.resolve());
  const address = computer?.address ?? null;
  const wantsConnection = screen === 'trackpad' || screen === 'settings';
  const keyboardReady = status.kind === 'CONNECTED' && status.keyboard;

  function sendDraft() {
    let end = 0;
    for (const record of keyboardRecords(draft)) {
      if (!connection.current?.send(record.event)) {
        setDraft(draft.slice(end));
        setKeyboardNotice('The remaining text was kept. Reconnect and tap Send to try again.');
        return;
      }
      end = record.end;
    }
    setDraft('');
  }

  useEffect(() => {
    let active = true;
    load().then(saved => {
      if (!active) return;
      setComputer(saved.computer);
      setSettings(saved.settings);
      setScreen(saved.computer ? 'trackpad' : 'pairing');
    }).catch(() => {
      if (active) setScreen('error');
    });
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    if (!wantsConnection || !computer) return;
    const paired = computer;
    function update(state: string | null) {
      if (state === 'active') {
        connection.current ??= connect(paired, setStatus, (issue: KeyboardIssue) => {
          setKeyboardNotice(keyboardIssues[issue]);
        });
      } else {
        connection.current?.stop();
        connection.current = null;
        setStatus({ kind: 'IDLE' });
      }
    }
    const subscription = AppState.addEventListener('change', update);
    update(AppState.currentState);
    return () => {
      subscription.remove();
      connection.current?.stop();
      connection.current = null;
    };
  }, [computer, wantsConnection]);

  useEffect(() => {
    if (screen === 'trackpad' && keyboardReady) return;
    setKeyboardOpen(false);
  }, [screen, keyboardReady]);

  useEffect(() => {
    if (!keyboardNotice) return;
    const timeout = setTimeout(() => setKeyboardNotice(null), 5000);
    return () => clearTimeout(timeout);
  }, [keyboardNotice]);

  useEffect(() => {
    if (!wantsConnection) return;
    settingsWrites.current = settingsWrites.current.then(async () => {
      try {
        await saveSettings(settings);
        setSettingsError(false);
      } catch {
        setSettingsError(true);
      }
    });
  }, [settings, wantsConnection]);

  useEffect(() => {
    if (screen !== 'settings') return;
    const back = BackHandler.addEventListener('hardwareBackPress', () => {
      setScreen('trackpad');
      return true;
    });
    return () => back.remove();
  }, [screen]);

  const statusText: Record<ConnectionStatus['kind'], string> = {
    IDLE: address ? `Saved computer · ${address}` : 'No computer paired',
    CONNECTING: `Connecting to ${address}…`,
    CONNECTED: `Connected · ${address}${keyboardReady ? '' : ' · Trackpad only'}`,
    RETRYING: 'Connection lost. Retrying…',
    UNPAIRED: 'Pairing expired or was replaced. Pair again.',
    UPDATE_REQUIRED: 'Update the companion on your computer.',
  };

  return (
    <SafeAreaProvider style={styles.root}>
      <StatusBar barStyle="light-content" />
      <KeyboardAvoidingView style={styles.root} behavior="padding">
        <SafeAreaView style={styles.content}>
          {settingsError && <Text style={styles.error}>Could not save trackpad settings.</Text>}
          {screen === 'error' && <Text style={styles.error}>Could not load the saved trackpad settings.</Text>}
          {screen === 'pairing' && (
            <PairingScreen
              savedAddress={address}
              onPaired={pairedComputer => {
                setComputer(pairedComputer);
                setScreen('trackpad');
              }}
              onCancel={() => setScreen('trackpad')}
            />
          )}
          {screen === 'trackpad' && (
            <>
              <View style={styles.row}>
                <Heading title="Trackpad" />
                <Button label="Pair" onPress={() => {
                  setStatus({ kind: 'IDLE' });
                  setScreen('pairing');
                }} />
                <Button label="Settings" onPress={() => setScreen('settings')} />
              </View>
              <Text accessibilityLiveRegion="polite" style={[styles.muted, status.kind === 'CONNECTED' && styles.connected]}>
                {statusText[status.kind]}
              </Text>
              <View style={styles.pad}>
                <Touchpad
                  enabled={status.kind === 'CONNECTED'}
                  sensitivity={0.4 + settings.sensitivity / 50}
                  invertScroll={settings.invertScroll}
                  send={event => connection.current?.send(event)}
                />
              </View>
              <Text style={styles.hint}>Tap to click · Hold to drag · Two fingers to scroll or right-click</Text>
              <View style={styles.row}>
                <Button
                  label={keyboardOpen ? 'Hide keyboard' : 'Keyboard'}
                  disabled={!keyboardReady}
                  onPress={() => setKeyboardOpen(!keyboardOpen)}
                />
                {keyboardReady && (
                  <ScrollView
                    horizontal
                    accessibilityLabel="Special keys"
                    keyboardShouldPersistTaps="always"
                    contentContainerStyle={styles.row}
                  >
                    {(Object.keys(specialKeys) as KeyboardKey[]).map(key => (
                      <Button
                        key={key}
                        label={specialKeys[key]}
                        accessibilityLabel={key}
                        onPress={() => connection.current?.send({ type: 'keyTap', key })}
                      />
                    ))}
                  </ScrollView>
                )}
              </View>
              {keyboardOpen && keyboardReady && (
                <View style={styles.row}>
                  <TextInput
                    accessibilityLabel="Text to send to the desktop"
                    autoFocus
                    multiline
                    placeholder="Type text to send"
                    placeholderTextColor={palette.muted}
                    style={[styles.input, styles.draft]}
                    value={draft}
                    onChangeText={setDraft}
                  />
                  <Button label="Send" primary disabled={!draft} onPress={sendDraft} />
                </View>
              )}
              {keyboardNotice && <Text accessibilityLiveRegion="polite" style={styles.notice}>{keyboardNotice}</Text>}
            </>
          )}
          {screen === 'settings' && (
            <SettingsScreen settings={settings} onChange={setSettings} onBack={() => setScreen('trackpad')} />
          )}
        </SafeAreaView>
      </KeyboardAvoidingView>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: palette.background },
  content: { flex: 1, paddingHorizontal: 20, paddingVertical: 16, gap: 10 },
  form: { gap: 14, paddingBottom: 12 },
  row: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  heading: { flexGrow: 1, flexShrink: 1 },
  brand: { color: palette.accent, fontSize: 12, fontWeight: '700' },
  title: { color: palette.text, fontSize: 28, fontWeight: '700', marginTop: 4 },
  label: { color: palette.text, fontSize: 16, flexShrink: 1 },
  muted: { color: palette.muted, fontSize: 14 },
  hint: { color: palette.muted, fontSize: 13 },
  connected: { color: palette.accent },
  error: { color: palette.error },
  notice: { color: '#ffcd8e', fontSize: 13 },
  draft: { flex: 1, maxHeight: 100 },
  field: { gap: 4 },
  input: {
    color: palette.text,
    backgroundColor: palette.button,
    borderRadius: 10,
    paddingHorizontal: 12,
    minHeight: 48,
    fontSize: 16,
  },
  button: {
    minHeight: 48,
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: 14,
    backgroundColor: palette.button,
  },
  buttonText: { color: palette.text, fontSize: 14 },
  primary: { backgroundColor: palette.accent },
  primaryText: { color: palette.background, fontWeight: '700' },
  dimmed: { opacity: 0.6 },
  pad: { flex: 1, marginTop: 4 },
});
