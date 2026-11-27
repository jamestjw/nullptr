import Slider from '@react-native-community/slider';
import React, { useEffect, useRef, useState } from 'react';
import {
  BackHandler,
  DeviceEventEmitter,
  Keyboard,
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
import { sendInput, trackpad } from './src/trackpadNative';
import type { ConnectionStatus, Settings } from './src/trackpadNative';

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
}: {
  label: string;
  onPress: () => void;
  primary?: boolean;
  disabled?: boolean;
}) {
  return (
    <Pressable
      accessibilityRole="button"
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
  onPaired: (address: string) => void;
  onCancel: () => void;
}) {
  const [address, setAddress] = useState(savedAddress ?? '');
  const [code, setCode] = useState('');
  const [status, setStatus] = useState<PairingStatus>({ kind: 'idle' });
  const operation = useRef(false);
  const busy = status.kind === 'scanning' || status.kind === 'pairing';

  async function submit(scanning: boolean) {
    if (operation.current) return;
    operation.current = true;
    try {
      let pairing = { address, code };
      Keyboard.dismiss();
      if (scanning) {
        setStatus({ kind: 'scanning' });
        const scanned = await trackpad.scan();
        if (scanned === null) {
          setStatus({ kind: 'idle' });
          return;
        }
        pairing = scanned;
        setAddress(scanned.address);
        setCode(scanned.code);
      }
      setStatus({ kind: 'pairing', message: `Pairing with ${pairing.address.trim()}…` });
      onPaired(await trackpad.pair(pairing.address, pairing.code));
    } catch (error) {
      setStatus({
        kind: 'error',
        message: error instanceof Error ? error.message : 'Could not pair with the computer.',
      });
    } finally {
      operation.current = false;
    }
  }

  return (
    <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={styles.form}>
      <Heading title="Pair a computer" />
      <Text style={styles.muted}>Run the companion on your computer with Tailscale connected.</Text>
      <Button label="Scan pairing QR" onPress={() => submit(true)} disabled={busy} />
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
          onSubmitEditing={() => submit(false)}
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
      <Button label="Pair computer" primary onPress={() => submit(false)} disabled={busy} />
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
        onValueChange={sensitivity => {
          trackpad.setSensitivity(sensitivity);
          onChange(previous => ({ ...previous, sensitivity }));
        }}
      />
      <View style={styles.row}>
        <Text style={styles.label}>Invert scroll</Text>
        <Switch
          accessibilityLabel="Invert scroll"
          value={settings.invertScroll}
          onValueChange={invertScroll => {
            trackpad.setInvertScroll(invertScroll);
            onChange(previous => ({ ...previous, invertScroll }));
          }}
        />
      </View>
      <Text style={styles.muted}>Reverse the direction of two-finger scrolling.</Text>
      <Button label="Back to trackpad" onPress={onBack} />
    </ScrollView>
  );
}

export default function App() {
  const [screen, setScreen] = useState<Screen>('loading');
  const [address, setAddress] = useState<string | null>(null);
  const [settings, setSettings] = useState<Settings>({ sensitivity: 30, invertScroll: false });
  const [status, setStatus] = useState<ConnectionStatus>('IDLE');
  const wantsConnection = screen === 'trackpad' || screen === 'settings';

  useEffect(() => {
    let active = true;
    const subscription = DeviceEventEmitter.addListener('trackpadStatus', setStatus);
    trackpad.load().then(saved => {
      if (!active) return;
      setAddress(saved.address);
      setSettings({ sensitivity: saved.sensitivity, invertScroll: saved.invertScroll });
      setScreen(saved.address ? 'trackpad' : 'pairing');
    }).catch(() => {
      if (active) setScreen('error');
    });
    return () => {
      active = false;
      subscription.remove();
      trackpad.setConnectionEnabled(false);
    };
  }, []);

  useEffect(() => {
    trackpad.setConnectionEnabled(wantsConnection);
  }, [wantsConnection]);

  useEffect(() => {
    if (screen !== 'settings') return;
    const back = BackHandler.addEventListener('hardwareBackPress', () => {
      setScreen('trackpad');
      return true;
    });
    return () => back.remove();
  }, [screen]);

  const statusText: Record<ConnectionStatus, string> = {
    IDLE: address ? `Saved computer · ${address}` : 'No computer paired',
    CONNECTING: `Connecting to ${address}…`,
    CONNECTED: `Connected · ${address}`,
    RETRYING: 'Connection lost. Retrying…',
    UNPAIRED: 'Pairing expired or was replaced. Pair again.',
  };

  return (
    <SafeAreaProvider style={styles.root}>
      <StatusBar barStyle="light-content" />
      <SafeAreaView style={styles.content}>
        {screen === 'error' && <Text style={styles.error}>Could not load the saved trackpad settings.</Text>}
        {screen === 'pairing' && (
          <PairingScreen
            savedAddress={address}
            onPaired={pairedAddress => {
              setAddress(pairedAddress);
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
                setStatus('IDLE');
                setScreen('pairing');
              }} />
              <Button label="Settings" onPress={() => setScreen('settings')} />
            </View>
            <Text accessibilityLiveRegion="polite" style={[styles.muted, status === 'CONNECTED' && styles.connected]}>
              {statusText[status]}
            </Text>
            <View style={styles.pad}>
              <Touchpad
                enabled={status === 'CONNECTED'}
                sensitivity={0.4 + settings.sensitivity / 50}
                invertScroll={settings.invertScroll}
                send={sendInput}
              />
            </View>
            <Text style={styles.hint}>Tap to click · Hold to drag · Two fingers to scroll or right-click</Text>
          </>
        )}
        {screen === 'settings' && (
          <SettingsScreen settings={settings} onChange={setSettings} onBack={() => setScreen('trackpad')} />
        )}
      </SafeAreaView>
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
