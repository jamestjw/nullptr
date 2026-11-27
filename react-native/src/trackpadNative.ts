import { NativeModules } from 'react-native';
import type { InputEvent } from './Touchpad';

export type ConnectionStatus = 'IDLE' | 'CONNECTING' | 'CONNECTED' | 'RETRYING' | 'UNPAIRED';

export type Settings = {
  sensitivity: number;
  invertScroll: boolean;
};

type Pairing = { address: string; code: string };

export const trackpad: {
  load(): Promise<Settings & { address: string | null }>;
  pair(address: string, code: string): Promise<string>;
  scan(): Promise<Pairing | null>;
  setConnectionEnabled(enabled: boolean): void;
  sendDelta(type: 'move' | 'scroll', dx: number, dy: number): void;
  sendButton(button: 'left' | 'right', down: boolean): void;
  setSensitivity(value: number): void;
  setInvertScroll(value: boolean): void;
} = NativeModules.Trackpad;

export function sendInput(event: InputEvent) {
  if (event.type === 'button') {
    trackpad.sendButton(event.button, event.down);
  } else {
    trackpad.sendDelta(event.type, event.dx, event.dy);
  }
}
