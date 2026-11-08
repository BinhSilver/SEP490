import { RefObject, useEffect, useRef } from 'react';

const FOCUSABLE = 'button:not([disabled]), [href], input:not([disabled]), select, textarea, [tabindex]:not([tabindex="-1"])';

/**
 * Keeps keyboard focus inside an open dialog: focuses its first control, wraps Tab / Shift+Tab,
 * calls `onEscape` for Escape and gives focus back to the previously focused element on close.
 */
export function useFocusTrap(ref: RefObject<HTMLElement | null>, active: boolean, onEscape?: () => void) {
  const escapeRef = useRef(onEscape);
  escapeRef.current = onEscape;

  useEffect(() => {
    if (!active) return;
    const previous = document.activeElement as HTMLElement | null;
    const timer = window.setTimeout(() => {
      const node = ref.current;
      if (!node) return;
      const preferred = node.querySelector<HTMLElement>('[data-autofocus]');
      (preferred ?? node.querySelector<HTMLElement>(FOCUSABLE))?.focus();
    }, 40);

    const onKeyDown = (event: KeyboardEvent) => {
      const node = ref.current;
      if (!node) return;
      if (event.key === 'Escape' && escapeRef.current) {
        event.preventDefault();
        event.stopPropagation();
        escapeRef.current();
        return;
      }
      if (event.key !== 'Tab') return;
      const focusables = Array.from(node.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(el => el.offsetParent !== null);
      if (focusables.length === 0) return;
      const first = focusables[0];
      const last = focusables[focusables.length - 1];
      if (!node.contains(document.activeElement)) {
        event.preventDefault();
        first.focus();
      } else if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };

    window.addEventListener('keydown', onKeyDown, true);
    return () => {
      window.clearTimeout(timer);
      window.removeEventListener('keydown', onKeyDown, true);
      if (previous && document.contains(previous)) {
        window.setTimeout(() => previous.focus(), 0);
      }
    };
  }, [active, ref]);
}
