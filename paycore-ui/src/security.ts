import { useCallback, useEffect, useState } from 'react';
import { api } from './api/client';
import type { Device, PinStatus } from './api/types';

const listeners = new Set<() => void>();

export function refreshSecurity() {
  listeners.forEach((listener) => listener());
}

export interface TransactionSecurity {
  loading: boolean;
  pin: PinStatus | null;
  device: Device | null;
  ready: boolean;
  reload: () => Promise<void>;
}

export function useTransactionSecurity(): TransactionSecurity {
  const [loading, setLoading] = useState(true);
  const [pin, setPin] = useState<PinStatus | null>(null);
  const [device, setDevice] = useState<Device | null>(null);

  const reload = useCallback(async () => {
    const [nextPin, nextDevice] = await Promise.all([
      api<PinStatus>('/api/v1/security/pin').catch(() => null),
      api<Device>('/api/v1/devices/current').catch(() => null),
    ]);
    setPin(nextPin);
    setDevice(nextDevice);
    setLoading(false);
  }, []);

  useEffect(() => {
    void reload();
    listeners.add(reload);
    return () => {
      listeners.delete(reload);
    };
  }, [reload]);

  return {
    loading,
    pin,
    device,
    ready: device?.status === 'TRUSTED' && !!pin?.set && !pin.locked,
    reload,
  };
}
