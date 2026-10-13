import { useEffect } from 'react';

/**
 * Presentational toast components. Shared UI components live in `components/common/` in this
 * project (Button, Input, ErrorMessage, Loading), so this follows that convention.
 *
 * A single <Toast /> owns its auto-dismiss timer: it is created when the toast appears and cleared
 * as soon as the toast is dismissed or unmounted, which prevents timer leaks and state updates from
 * stale timers.
 */

const ICON_PATHS = {
  success: 'M9 12.75 11.25 15 15 9.75M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z',
  error: 'M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z',
  info: 'm11.25 11.25.041-.02a.75.75 0 0 1 1.063.852l-.708 2.836a.75.75 0 0 0 1.063.853l.041-.021M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Zm-9-3.75h.008v.008H12V8.25Z',
};

const VARIANTS = {
  success: { role: 'status', accent: 'text-emerald-400', leftBorder: 'border-l-emerald-500' },
  error: { role: 'alert', accent: 'text-rose-400', leftBorder: 'border-l-rose-500' },
  info: { role: 'status', accent: 'text-orange-400', leftBorder: 'border-l-orange-500' },
};

export function Toast({ id, message, type = 'info', duration, onDismiss }) {
  const variant = VARIANTS[type] ?? VARIANTS.info;

  useEffect(() => {
    if (!duration || duration <= 0) return undefined;

    const timer = window.setTimeout(() => onDismiss(id), duration);
    return () => window.clearTimeout(timer);
  }, [id, duration, onDismiss]);

  return (
    <div
      role={variant.role}
      className={`pointer-events-auto animate-toast-in motion-reduce:animate-none flex w-full items-start gap-3 rounded-lg border-y border-r border-y-slate-800 border-r-slate-800 border-l-4 ${variant.leftBorder} bg-slate-900/95 px-4 py-3 shadow-lg shadow-slate-950/40 backdrop-blur-sm`}
    >
      <svg
        className={`h-5 w-5 shrink-0 mt-0.5 ${variant.accent}`}
        fill="none"
        viewBox="0 0 24 24"
        stroke="currentColor"
        strokeWidth="2"
        aria-hidden="true"
      >
        <path strokeLinecap="round" strokeLinejoin="round" d={ICON_PATHS[type] ?? ICON_PATHS.info} />
      </svg>

      <p className="flex-1 text-sm font-medium leading-snug text-slate-200">{message}</p>

      <button
        type="button"
        aria-label="Dismiss notification"
        onClick={() => onDismiss(id)}
        className="shrink-0 -mr-1 rounded-md p-1 text-slate-500 transition-colors hover:bg-slate-800 hover:text-slate-200 focus:outline-none focus:ring-2 focus:ring-orange-500 focus:ring-offset-2 focus:ring-offset-slate-950"
      >
        <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2" aria-hidden="true">
          <path strokeLinecap="round" strokeLinejoin="round" d="M6 18 18 6M6 6l12 12" />
        </svg>
      </button>
    </div>
  );
}

export function ToastContainer({ toasts = [], onDismiss }) {
  if (toasts.length === 0) return null;

  return (
    <div
      className="pointer-events-none fixed inset-x-4 top-4 z-[100] flex flex-col gap-3 sm:inset-x-auto sm:right-6 sm:top-6 sm:w-96"
      data-testid="toast-container"
    >
      {toasts.map((toast) => (
        <Toast key={toast.id} {...toast} onDismiss={onDismiss} />
      ))}
    </div>
  );
}
