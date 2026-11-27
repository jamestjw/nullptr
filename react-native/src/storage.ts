import AsyncStorage from '@react-native-async-storage/async-storage';
import * as SecureStore from 'expo-secure-store';
import { parseComputer } from './connection';
import type { Computer } from './connection';

export type Settings = { sensitivity: number; invertScroll: boolean };

export const defaultSettings: Settings = { sensitivity: 30, invertScroll: false };
const computerKey = 'paired-computer';
const settingsKey = 'trackpad-settings';

export async function load() {
  const [computer, settings] = await Promise.all([
    SecureStore.getItemAsync(computerKey),
    AsyncStorage.getItem(settingsKey),
  ]);
  const saved = settings ? JSON.parse(settings) : null;
  const sensitivity = saved?.sensitivity;
  return {
    computer: computer ? parseComputer(JSON.parse(computer)) : null,
    settings: {
      sensitivity: Number.isInteger(sensitivity) && sensitivity >= 0 && sensitivity <= 100
        ? sensitivity : defaultSettings.sensitivity,
      invertScroll: saved?.invertScroll === true,
    },
  };
}

export function saveComputer(computer: Computer) {
  return SecureStore.setItemAsync(computerKey, JSON.stringify(computer));
}

export function saveSettings(settings: Settings) {
  return AsyncStorage.setItem(settingsKey, JSON.stringify(settings));
}
