import { useEffect } from 'react';
import { metricsApi } from '../../api/endpoints.js';
import { useApi } from '../../api/useApi.js';

/** Latest values for all reporting servers, refreshed every 30 s. */
export function useFleet() {
  const result = useApi(() => metricsApi.fleet(), []);
  useEffect(() => {
    const timer = setInterval(result.reload, 30000);
    return () => clearInterval(timer);
  }, [result.reload]);
  return result;
}
