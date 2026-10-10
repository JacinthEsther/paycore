import { useEffect, useState } from 'react';
import { api } from './client';
import type { DemoInfo } from './types';

let cached: Promise<DemoInfo | null> | null = null;

export function fetchDemoInfo(): Promise<DemoInfo | null> {
  cached ??= api<DemoInfo>('/api/v1/demo', { auth: 'none' }).catch(() => null);
  return cached;
}

export function useDemoInfo() {
  const [info, setInfo] = useState<DemoInfo | null | undefined>(undefined);
  useEffect(() => {
    void fetchDemoInfo().then(setInfo);
  }, []);
  return info;
}
