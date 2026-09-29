import { useEffect, useState } from 'react';
import { api } from './client';
import type { DemoInfo } from './types';

// Fetched once per page load; null when demo mode is off on the server.
let cached: Promise<DemoInfo | null> | null = null;

export function fetchDemoInfo(): Promise<DemoInfo | null> {
  cached ??= api<DemoInfo>('/api/v1/demo', { auth: 'none' }).catch(() => null);
  return cached;
}

/** undefined while loading, null when demo mode is off. */
export function useDemoInfo() {
  const [info, setInfo] = useState<DemoInfo | null | undefined>(undefined);
  useEffect(() => {
    void fetchDemoInfo().then(setInfo);
  }, []);
  return info;
}
