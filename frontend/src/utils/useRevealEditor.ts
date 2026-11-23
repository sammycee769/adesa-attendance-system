import { useEffect, useRef } from 'react';

const firstField = [
  'input:not([type="hidden"]):not(:disabled):not([readonly])',
  'select:not(:disabled)',
  'textarea:not(:disabled):not([readonly])',
].join(', ');

export function useRevealEditor<T extends HTMLElement>(editor: object | string | number | boolean | null | undefined = true) {
  const ref = useRef<T>(null);

  useEffect(() => {
    const panel = ref.current;
    if (!panel || editor === false || editor == null) return;

    const reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    panel.scrollIntoView?.({ block: 'start', behavior: reducedMotion ? 'auto' : 'smooth' });
    panel.querySelector<HTMLElement>(firstField)?.focus({ preventScroll: true });
  }, [editor]);

  return ref;
}
