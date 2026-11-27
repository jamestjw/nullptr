export const specialKeys = {
  backspace: '⌫',
  enter: 'Enter',
  tab: 'Tab',
  escape: 'Esc',
  left: '←',
  right: '→',
  up: '↑',
  down: '↓',
};

export type KeyboardKey = keyof typeof specialKeys;

export type KeyboardEvent =
  | { type: 'text'; text: string }
  | { type: 'keyTap'; key: KeyboardKey };

export const keyboardIssues = {
  unavailable: 'The desktop keyboard is unavailable.',
  modifiersActive: 'Input skipped. Release modifier keys on the desktop.',
  unsupportedLayout: 'Some input is not available in the desktop keyboard layout.',
};

export type KeyboardIssue = keyof typeof keyboardIssues;

export function* keyboardRecords(draft: string): Generator<{ event: KeyboardEvent; end: number }> {
  let text = '';
  let count = 0;
  for (let start = 0; start < draft.length;) {
    const code = draft.codePointAt(start)!;
    let end = start + (code > 0xffff ? 2 : 1);
    if (code === 9 || code === 10 || code === 13) {
      if (text) yield { event: { type: 'text', text }, end: start };
      if (code === 13 && draft[end] === '\n') end++;
      yield { event: { type: 'keyTap', key: code === 9 ? 'tab' : 'enter' }, end };
      text = '';
      count = 0;
    } else if (!(code < 32 || (code >= 127 && code <= 159) || (code >= 0xd800 && code <= 0xdfff))) {
      text += draft.slice(start, end);
      count++;
      if (count === 64) {
        yield { event: { type: 'text', text }, end };
        text = '';
        count = 0;
      }
    }
    start = end;
  }
  if (text) yield { event: { type: 'text', text }, end: draft.length };
}

export function parseKeyboardIssue(data: unknown): KeyboardIssue | null {
  if (typeof data !== 'string') return null;
  try {
    const notice: unknown = JSON.parse(data);
    if (notice && typeof notice === 'object' &&
        'type' in notice && notice.type === 'keyboardIssue' &&
        'reason' in notice && typeof notice.reason === 'string' &&
        Object.hasOwn(keyboardIssues, notice.reason)) {
      return notice.reason as KeyboardIssue;
    }
  } catch {}
  return null;
}
