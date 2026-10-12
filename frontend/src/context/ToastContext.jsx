import { createContext, useCallback, useContext, useMemo, useRef, useState } from 'react';
import { ToastContainer } from '../components/common/Toast';

/**
 * Lightweight toast notifications used for user feedback such as logging in, registering or
 * joining/leaving a community.
 *
 * Every toast gets a stable, unique id so several notifications can be visible at the same time
 * without interfering with each other. The dismissal timer lives inside <Toast />, which clears the
 * timer whenever the toast is dismissed manually or unmounted, so no stale timer can fire after a
 * notification is gone.
 */
const ToastContext = createContext(null);

const TOAST_TYPES = ['success', 'error', 'info'];
const DEFAULT_DURATION_MS = 3500;

export function ToastProvider({ children, defaultDuration = DEFAULT_DURATION_MS }) {
  const [toasts, setToasts] = useState([]);
  const nextIdRef = useRef(0);

  const dismiss = useCallback((id) => {
    setToasts((current) => current.filter((toast) => toast.id !== id));
  }, []);

  const showToast = useCallback((message, type = 'info', options = {}) => {
    const text = typeof message === 'string' ? message.trim() : '';
    if (!text) return null;

    nextIdRef.current += 1;
    const toast = {
      id: `threadhub-toast-${nextIdRef.current}`,
      message: text,
      type: TOAST_TYPES.includes(type) ? type : 'info',
      duration: Number.isFinite(options.duration) ? options.duration : defaultDuration,
    };

    // Newest first, so the most recent feedback is the one the user reads first.
    setToasts((current) => [toast, ...current]);
    return toast.id;
  }, [defaultDuration]);

  const value = useMemo(() => ({
    showToast,
    success: (message, options) => showToast(message, 'success', options),
    error: (message, options) => showToast(message, 'error', options),
    info: (message, options) => showToast(message, 'info', options),
    dismiss,
  }), [showToast, dismiss]);

  return (
    <ToastContext.Provider value={value}>
      {children}
      <ToastContainer toasts={toasts} onDismiss={dismiss} />
    </ToastContext.Provider>
  );
}

export function useToast() {
  const context = useContext(ToastContext);
  if (!context) {
    throw new Error('useToast must be used within a ToastProvider');
  }
  return context;
}
