import React, {useLayoutEffect, useState} from 'react';
import {
  Platform,
  StyleSheet,
  Text,
  View,
  type NativeTouchEvent,
} from 'react-native';

export type InputEvent =
  | {type: 'move'; dx: number; dy: number}
  | {type: 'scroll'; dx: number; dy: number}
  | {type: 'button'; button: 'left' | 'right'; down: boolean};

type Props = {
  enabled: boolean;
  sensitivity: number;
  invertScroll: boolean;
  send: (event: InputEvent) => void;
};

type Point = {x: number; y: number};
type Contact = {id: NativeTouchEvent['identifier']; origin: Point};
type Gesture =
  | {phase: 'idle' | 'suppressed'}
  | {phase: 'tap' | 'move' | 'drag'; finger: Contact; last: Point; startedAt: number}
  | {
      phase: 'two-finger-tap' | 'scroll';
      first: Contact;
      second: Contact;
      last: Point;
      startedAt: number;
    }
  | {phase: 'finishing-tap'; finger: Contact; startedAt: number};

const touchSlop = 8;
const dragDelay = 420;
const rightClickTimeout = 300;
const dotSpacing = 24;
const waitingMessage = 'Waiting for a computer';
const gestureHint = 'Slide to move. Tap to click. Hold to drag. Two fingers to scroll or right-click.';

function point(touch: NativeTouchEvent): Point {
  return {x: touch.pageX, y: touch.pageY};
}

function contact(touch: NativeTouchEvent): Contact {
  return {id: touch.identifier, origin: point(touch)};
}

function midpoint(first: Point, second: Point): Point {
  return {x: (first.x + second.x) / 2, y: (first.y + second.y) / 2};
}

function movedFrom(current: Point, origin: Point): boolean {
  return (current.x - origin.x) ** 2 + (current.y - origin.y) ** 2 > touchSlop ** 2;
}

function createGestureTracker(initialProps: Props, onActiveChange: (active: boolean) => void) {
  let props = initialProps;
  let gesture: Gesture = {phase: 'idle'};
  let dragTimer: ReturnType<typeof setTimeout> | undefined;
  let frame: number | undefined;
  let pendingX = 0;
  let pendingY = 0;

  function click(button: 'left' | 'right') {
    props.send({type: 'button', button, down: true});
    props.send({type: 'button', button, down: false});
  }

  function cancelDragTimer() {
    if (dragTimer !== undefined) {
      clearTimeout(dragTimer);
      dragTimer = undefined;
    }
  }

  function flush() {
    if (frame !== undefined) {
      cancelAnimationFrame(frame);
      frame = undefined;
    }
    const scrolling = gesture.phase === 'scroll';
    const limit = scrolling ? 20 : 500;
    const dx = Math.max(-limit, Math.min(limit, Math.trunc(pendingX) || 0));
    const dy = Math.max(-limit, Math.min(limit, Math.trunc(pendingY) || 0));
    pendingX %= 1;
    pendingY %= 1;
    if (dx !== 0 || dy !== 0) {
      props.send({type: scrolling ? 'scroll' : 'move', dx, dy});
    }
  }

  function accumulate(current: Point, last: Point, scale: number) {
    pendingX += (current.x - last.x) * scale;
    pendingY += (current.y - last.y) * scale;
    if (frame === undefined && (Math.abs(pendingX) >= 1 || Math.abs(pendingY) >= 1)) {
      frame = requestAnimationFrame(() => {
        frame = undefined;
        flush();
      });
    }
  }

  function finish(next: Gesture = {phase: 'idle'}) {
    cancelDragTimer();
    flush();
    const previous = gesture.phase;
    gesture = next;
    pendingX = 0;
    pendingY = 0;
    if (previous === 'drag') {
      props.send({type: 'button', button: 'left', down: false});
    }
    if ((previous === 'idle') !== (next.phase === 'idle')) {
      onActiveChange(next.phase !== 'idle');
    }
  }

  function start({touches, changedTouches, timestamp}: NativeTouchEvent) {
    if (!props.enabled) return;
    const current = gesture;
    if (current.phase === 'idle' && touches.length === 1) {
      const finger = contact(touches[0]);
      const tap: Gesture = {phase: 'tap', finger, last: finger.origin, startedAt: timestamp};
      gesture = tap;
      dragTimer = setTimeout(() => {
        dragTimer = undefined;
        if (gesture !== tap || tap.phase !== 'tap') return;
        tap.phase = 'drag';
        props.send({type: 'button', button: 'left', down: true});
      }, dragDelay);
      onActiveChange(true);
      return;
    }

    if (touches.length === 2) {
      if (current.phase === 'idle' && changedTouches.length === 2) {
        const first = contact(touches[0]);
        const second = contact(touches[1]);
        finish({
          phase: 'two-finger-tap',
          first,
          second,
          last: midpoint(first.origin, second.origin),
          startedAt: timestamp,
        });
        return;
      }
      if (current.phase === 'tap' || current.phase === 'move' || current.phase === 'drag') {
        const firstTouch = touches.find(touch => touch.identifier === current.finger.id);
        const secondTouch = touches.find(touch => touch.identifier !== current.finger.id);
        if (firstTouch && secondTouch) {
          const first = contact(firstTouch);
          const second = contact(secondTouch);
          const tap = current.phase === 'tap' && !movedFrom(first.origin, current.finger.origin);
          finish({
            phase: tap ? 'two-finger-tap' : 'scroll',
            first,
            second,
            last: midpoint(first.origin, second.origin),
            startedAt: current.startedAt,
          });
          return;
        }
      }
    }
    finish({phase: 'suppressed'});
  }

  function move(touches: readonly NativeTouchEvent[]) {
    const current = gesture;
    switch (current.phase) {
      case 'tap':
      case 'move':
      case 'drag': {
        const touch = touches.find(candidate => candidate.identifier === current.finger.id);
        if (!touch) return;
        const next = point(touch);
        if (current.phase === 'tap') {
          if (!movedFrom(next, current.finger.origin)) return;
          cancelDragTimer();
          current.phase = 'move';
        }
        accumulate(next, current.last, props.sensitivity);
        current.last = next;
        break;
      }
      case 'two-finger-tap':
      case 'scroll': {
        const firstTouch = touches.find(touch => touch.identifier === current.first.id);
        const secondTouch = touches.find(touch => touch.identifier === current.second.id);
        if (!firstTouch || !secondTouch) return;
        const first = point(firstTouch);
        const second = point(secondTouch);
        const next = midpoint(first, second);
        const scale = (props.invertScroll ? -1 : 1) / 24;
        if (current.phase === 'two-finger-tap') {
          if (!movedFrom(first, current.first.origin) && !movedFrom(second, current.second.origin)) {
            return;
          }
          current.phase = 'scroll';
        }
        accumulate(next, current.last, scale);
        current.last = next;
        break;
      }
      case 'finishing-tap': {
        const touch = touches.find(candidate => candidate.identifier === current.finger.id);
        if (touch && movedFrom(point(touch), current.finger.origin)) {
          gesture = {phase: 'suppressed'};
        }
        break;
      }
    }
  }

  function end({touches, changedTouches, timestamp}: NativeTouchEvent) {
    if (gesture.phase === 'idle') return;
    move([...touches, ...changedTouches]);
    const current = gesture;
    if (current.phase === 'two-finger-tap' && timestamp - current.startedAt <= rightClickTimeout) {
      const remaining = touches[0]?.identifier;
      if (
        touches.length === 1 &&
        (remaining === current.first.id || remaining === current.second.id)
      ) {
        const finger = remaining === current.first.id ? current.first : current.second;
        finish({phase: 'finishing-tap', finger, startedAt: current.startedAt});
        return;
      }
      if (
        touches.length === 0 &&
        changedTouches.some(touch => touch.identifier === current.first.id) &&
        changedTouches.some(touch => touch.identifier === current.second.id)
      ) {
        click('right');
      }
    } else if (touches.length === 0) {
      if (
        current.phase === 'tap' &&
        timestamp - current.startedAt < dragDelay &&
        changedTouches.some(touch => touch.identifier === current.finger.id)
      ) {
        click('left');
      }
      if (
        current.phase === 'finishing-tap' &&
        timestamp - current.startedAt <= rightClickTimeout &&
        changedTouches.some(touch => touch.identifier === current.finger.id)
      ) {
        click('right');
      }
    }
    finish(touches.length === 0 ? {phase: 'idle'} : {phase: 'suppressed'});
  }

  return {
    update(next: Props) {
      if (!next.enabled) finish();
      props = next;
    },
    start,
    move(event: NativeTouchEvent) {
      move(event.touches);
    },
    end,
    cancel() {
      finish();
    },
    activate() {
      if (props.enabled) click('left');
    },
  };
}

export default function Touchpad(props: Props) {
  const {enabled} = props;
  const [active, setActive] = useState(false);
  const [size, setSize] = useState({width: 0, height: 0});
  const [tracker] = useState(() => createGestureTracker(props, setActive));

  useLayoutEffect(() => {
    tracker.update(props);
  }, [tracker, props]);
  useLayoutEffect(() => tracker.cancel, [tracker]);

  const columns = Math.max(0, Math.floor(size.width / dotSpacing) - 1);
  const rows = Math.max(0, Math.floor(size.height / dotSpacing) - 1);
  const left = (size.width - (columns - 1) * dotSpacing) / 2;
  const top = (size.height - (rows - 1) * dotSpacing) / 2;

  return (
    <View
      accessible
      accessibilityRole="button"
      accessibilityLabel="Trackpad"
      accessibilityHint={enabled ? gestureHint : waitingMessage}
      accessibilityState={{disabled: !enabled}}
      accessibilityActions={enabled && Platform.OS === 'android' ? [{name: 'activate'}] : undefined}
      onAccessibilityAction={event => {
        if (event.nativeEvent.actionName === 'activate') tracker.activate();
      }}
      onAccessibilityTap={tracker.activate}
      style={styles.pad}
      pointerEvents="box-only"
      onLayout={event => {
        const {width, height} = event.nativeEvent.layout;
        setSize({width, height});
      }}
      onStartShouldSetResponder={() => enabled}
      onResponderTerminate={tracker.cancel}
      onTouchStart={event => tracker.start(event.nativeEvent)}
      onTouchMove={event => tracker.move(event.nativeEvent)}
      onTouchEnd={event => tracker.end(event.nativeEvent)}
      onTouchCancel={tracker.cancel}
    >
      {enabled ? (
        Array.from({length: rows}, (_, row) =>
          Array.from({length: columns}, (_, column) => (
            <View
              key={`${row}:${column}`}
              style={[
                styles.dot,
                active && styles.activeDot,
                {left: left + column * dotSpacing - 1, top: top + row * dotSpacing - 1},
              ]}
            />
          )),
        )
      ) : (
        <Text style={styles.waiting}>{waitingMessage}</Text>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  pad: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#19212b',
    borderRadius: 24,
  },
  dot: {
    position: 'absolute',
    width: 2,
    height: 2,
    borderRadius: 1,
    backgroundColor: '#394c5a',
  },
  activeDot: {
    backgroundColor: '#5f907f',
  },
  waiting: {
    color: '#9fadb9',
    fontSize: 14,
  },
});
