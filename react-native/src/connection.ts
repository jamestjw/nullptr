import { fetch } from 'expo/fetch';
import type { InputEvent } from './Touchpad';
import { parseKeyboardIssue } from './keyboard';
import type { KeyboardEvent, KeyboardIssue } from './keyboard';

export type Computer = { address: string; token: string };
export type ConnectionStatus =
  | { kind: 'CONNECTED'; keyboard: boolean }
  | { kind: 'IDLE' | 'CONNECTING' | 'RETRYING' | 'UNPAIRED' | 'UPDATE_REQUIRED' };

const nativeOrigin = 'https://nullptr.invalid';
const codePattern = /^[0-9a-f]{12}$/i;
const tokenPattern = /^[A-Za-z0-9_-]{32,128}$/;

export function parseAddress(raw: string): string | null {
  const match = /^(100)\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})(?::(\d{1,5}))?$/.exec(raw.trim());
  if (!match) return null;
  const [, first, second, third, fourth, rawPort] = match;
  const port = rawPort ? Number(rawPort) : 47822;
  if (Number(third) > 255 || Number(fourth) > 255 || port < 1 || port > 65535) return null;
  return `${first}.${second}.${third}.${fourth}:${port}`;
}

export function parsePairingQr(data: string) {
  try {
    const url = new URL(data);
    const address = parseAddress(url.searchParams.get('host') ?? '');
    const code = url.searchParams.get('code') ?? '';
    if (url.protocol === 'nullptr:' && url.host === 'pair' && !url.username && !url.password &&
        address && codePattern.test(code)) {
      return { address, code };
    }
  } catch {}
  throw new Error("This isn't a nullptr pairing code.");
}

export function parseComputer(value: unknown): Computer | null {
  if (!value || typeof value !== 'object' || !('address' in value) || !('token' in value)) return null;
  const address = typeof value.address === 'string' ? parseAddress(value.address) : null;
  return address && typeof value.token === 'string' && tokenPattern.test(value.token)
    ? { address, token: value.token }
    : null;
}

async function request(address: string, path: string, init: Parameters<typeof fetch>[1]) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 8000);
  try {
    const response = await fetch(`http://${address}${path}`, {
      ...init,
      credentials: 'omit',
      redirect: 'error',
      signal: controller.signal,
    });
    const body: unknown = init?.method === 'POST' && response.status === 200
      ? await response.json().catch(() => null)
      : null;
    return { status: response.status, body, keyboard: response.headers.get('X-Nullptr-Keyboard') === '1' };
  } finally {
    clearTimeout(timeout);
  }
}

export async function pair(rawAddress: string, rawCode: string): Promise<Computer> {
  const address = parseAddress(rawAddress);
  const code = rawCode.trim();
  if (!address || !codePattern.test(code)) {
    throw new Error('Enter a Tailscale IPv4 address and the 12-character pairing code.');
  }
  let response;
  try {
    response = await request(address, '/v1/status', undefined);
  } catch {
    throw new Error(`Could not reach ${address}. Check Tailscale on both devices.`);
  }
  if (response.status === 404) {
    throw new Error('Update the companion on your computer before pairing.');
  }
  try {
    response = await request(address, '/v1/pair', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code }),
    });
  } catch {
    throw new Error(`Could not reach ${address}. Check Tailscale on both devices.`);
  }
  switch (response.status) {
    case 200: {
      const { body } = response;
      const token = body && typeof body === 'object' && 'token' in body ? body.token : null;
      const computer = parseComputer({ address, token });
      if (computer) return computer;
      throw new Error('The computer returned an invalid credential.');
    }
    case 401:
    case 403: throw new Error('The pairing code is invalid or expired.');
    case 409: throw new Error('The computer already has an active trackpad connection.');
    case 429: throw new Error('Too many attempts. Wait before trying again.');
    default: throw new Error(`Pairing failed (HTTP ${response.status}).`);
  }
}

export function connect(
  computer: Computer,
  onStatus: (status: ConnectionStatus) => void,
  onKeyboardIssue: (issue: KeyboardIssue) => void,
) {
  const headers = { Authorization: `Bearer ${computer.token}` };
  let socket: WebSocket | undefined;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let stopped = false;
  let retryDelay = 500;
  let keyboard = false;

  function closeSocket() {
    const previous = socket;
    socket = undefined;
    if (!previous) return;
    keyboard = false;
    previous.onopen = previous.onclose = previous.onerror = previous.onmessage = null;
    previous.close();
  }

  function retry() {
    if (timer !== undefined) clearTimeout(timer);
    closeSocket();
    onStatus({ kind: 'RETRYING' });
    timer = setTimeout(open, retryDelay);
    retryDelay = Math.min(retryDelay * 2, 5000);
  }

  async function open() {
    onStatus({ kind: 'CONNECTING' });
    try {
      const response = await request(computer.address, '/v1/status', { headers });
      if (stopped) return;
      if (response.status === 401 || response.status === 403) {
        onStatus({ kind: 'UNPAIRED' });
        return;
      }
      if (response.status === 404) {
        onStatus({ kind: 'UPDATE_REQUIRED' });
        return;
      }
      if (response.status !== 204) {
        retry();
        return;
      }
      const current = new WebSocket(`ws://${computer.address}/v1/input`, undefined, {
        headers: { ...headers, Origin: nativeOrigin },
      });
      socket = current;
      timer = setTimeout(retry, 8000);
      current.onopen = () => {
        if (socket !== current) return;
        clearTimeout(timer);
        retryDelay = 500;
        keyboard = response.keyboard;
        onStatus({ kind: 'CONNECTED', keyboard });
      };
      current.onmessage = event => {
        if (socket !== current || !keyboard) return;
        const issue = parseKeyboardIssue(event.data);
        if (issue) onKeyboardIssue(issue);
      };
      current.onerror = current.onclose = () => {
        if (socket === current) retry();
      };
    } catch {
      if (!stopped) retry();
    }
  }

  void open();
  return {
    send(event: InputEvent | KeyboardEvent): boolean {
      if (socket?.readyState !== WebSocket.OPEN) return false;
      if ((event.type === 'text' || event.type === 'keyTap') && !keyboard) return false;
      try {
        socket.send(JSON.stringify(event));
        return true;
      } catch {
        retry();
        return false;
      }
    },
    stop() {
      stopped = true;
      clearTimeout(timer);
      closeSocket();
    },
  };
}
