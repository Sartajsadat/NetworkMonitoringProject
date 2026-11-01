import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Runs `fn` whenever `deps` change and tracks loading/error state.
 * Stale responses from earlier calls are ignored.
 */
export function useApi(fn, deps = []) {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);
  const callId = useRef(0);

  // eslint-disable-next-line react-hooks/exhaustive-deps
  const run = useCallback(fn, deps);

  const reload = useCallback(async () => {
    const id = ++callId.current;
    setLoading(true);
    setError(null);
    try {
      const result = await run();
      if (id === callId.current) setData(result);
    } catch (e) {
      if (id === callId.current) setError(e);
    } finally {
      if (id === callId.current) setLoading(false);
    }
  }, [run]);

  useEffect(() => {
    reload();
  }, [reload]);

  return { data, error, loading, reload, setData };
}
