#!/usr/bin/env python3
"""
NetMon load test: simulates many agents reporting at once while users query history.

Use only against a TEST deployment: it creates servers named loadtest-NNN and enrolls fake agents.

  python3 agent_load.py --url http://localhost:8088 --user root --password-file pw.txt \
      --servers 200 --interval 30 --duration 300 --workers 32

Each simulated agent sends a full report (CPU, memory, disks, 4 interfaces, 20 services,
30 processes) every --interval seconds, like a real agent. In parallel, readers request the
dashboard, a server's 24-hour CPU history and the event timeline. The tool prints throughput,
error counts and latency percentiles. Only the Python standard library is used.
"""
import argparse
import concurrent.futures as cf
import json
import random
import statistics
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone


def call(base, method, path, body=None, auth=None, timeout=30):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if auth:
        req.add_header("Authorization", auth)
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            payload = r.read()
            return r.status, (json.loads(payload) if payload else None), time.perf_counter() - t0
    except urllib.error.HTTPError as e:
        return e.code, None, time.perf_counter() - t0
    except Exception:  # connection reset, timeout
        return 0, None, time.perf_counter() - t0


def now():
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def report(host, rng):
    cpu = rng.uniform(2, 95)
    return {
        "agentVersion": "0.7.0-loadtest", "collectedAt": now(), "intervalSeconds": 30,
        "host": {"hostname": host, "platform": "LINUX", "osName": "Ubuntu", "uptimeSeconds": 86400, "cpuCount": 8},
        "cpu": {"usagePercent": cpu, "load1": cpu / 25, "perCore": [rng.uniform(0, 100) for _ in range(8)]},
        "memory": {"totalBytes": 16_000_000_000, "availableBytes": 8_000_000_000, "usedBytes": 8_000_000_000, "usedPercent": 50.0,
                   "swapTotalBytes": 0, "swapUsedBytes": 0},
        "disks": [{"mount": m, "device": "/dev/sda1", "fsType": "ext4", "totalBytes": 100, "usedBytes": 40, "freeBytes": 60,
                   "usedPercent": rng.uniform(20, 80)} for m in ("/", "/var", "/data")],
        "network": [{"name": f"eth{i}", "operState": "up", "rxBytes": 1000, "txBytes": 2000, "rxBitsPerSec": rng.uniform(1e3, 1e8),
                     "txBitsPerSec": rng.uniform(1e3, 1e8), "addresses": [f"10.{i}.0.5/24"]} for i in range(4)],
        "services": [{"name": f"svc{i}.service", "status": "RUNNING", "activeState": "active"} for i in range(20)],
        "processes": [{"pid": 100 + i, "name": f"proc{i}", "user": "app", "cpuPercent": rng.uniform(0, 5), "memoryBytes": 10_000_000}
                      for i in range(30)],
        "collectors": [{"name": "cpu", "status": "OK"}],
    }


def pct(values, p):
    if not values:
        return float("nan")
    s = sorted(values)
    return s[min(len(s) - 1, int(round(p / 100 * (len(s) - 1))))] * 1000


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True)
    ap.add_argument("--user", required=True)
    ap.add_argument("--password-file", required=True)
    ap.add_argument("--servers", type=int, default=100)
    ap.add_argument("--interval", type=float, default=30)
    ap.add_argument("--duration", type=float, default=120)
    ap.add_argument("--workers", type=int, default=32)
    ap.add_argument("--readers", type=int, default=4)
    a = ap.parse_args()
    base = a.url.rstrip("/") + "/api/v1"
    pw = open(a.password_file).read().strip()

    st, tok, _ = call(base, "POST", "/auth/login", {"username": a.user, "password": pw})
    assert st == 200, f"login failed: {st}"
    admin = "Bearer " + tok["accessToken"]

    print(f"Enrolling {a.servers} simulated agents ...")
    agents = []
    tag = datetime.now().strftime("%H%M%S")

    def enroll(i):
        name = f"loadtest-{tag}-{i:03d}"
        st, srv, _ = call(base, "POST", "/servers", {"name": name, "hostname": name + ".lab", "osFamily": "LINUX",
                                                       "environment": "TESTING", "organization": "Load test"}, admin)
        if st != 201:
            return ("server", st)
        st, t, _ = call(base, "POST", f"/servers/{srv['id']}/agent/enrollment-token", None, admin)
        if st != 200:
            return ("token", st)
        for attempt in range(30):  # enrolment is rate-limited per address (brute-force protection)
            st, e, _ = call(base, "POST", "/agent/enroll", {"token": t["token"], "hostname": name + ".lab", "platform": "LINUX",
                                                         "osName": "Ubuntu", "osVersion": "24.04", "kernel": "6.8", "architecture": "amd64",
                                                         "agentVersion": "0.7.0-loadtest"})
            if st != 429:
                break
            time.sleep(5)
        return (srv["id"], name + ".lab", f"Agent {e['agentId']}:{e['secret']}") if st == 200 else ("enroll", st)

    with cf.ThreadPoolExecutor(8) as ex:
        results = list(ex.map(enroll, range(a.servers)))
    agents = [x for x in results if len(x) == 3]
    failed = [x for x in results if len(x) == 2]
    print(f"  {len(agents)} enrolled" + (f"; setup failures: {failed[:5]}" if failed else ""))
    if not agents:
        raise SystemExit("no agent could be enrolled")

    stop = time.time() + a.duration
    lock = threading.Lock()
    rep_lat, rep_err, read_lat, read_err = [], {}, {}, {}

    def agent_loop(ag, offset):
        rng = random.Random(offset)
        time.sleep(offset)
        nxt = time.time()
        while time.time() < stop:
            st, _, dt = call(base, "POST", "/agent/report", report(ag[1], rng), ag[2])
            with lock:
                rep_lat.append(dt)
                if st != 200:
                    rep_err[st] = rep_err.get(st, 0) + 1
            nxt += a.interval
            time.sleep(max(0, nxt - time.time()))

    def reader_loop(k):
        rng = random.Random(1000 + k)
        while time.time() < stop:
            sid = rng.choice(agents)[0]
            frm = (datetime.now(timezone.utc) - timedelta(hours=24)).isoformat().replace("+00:00", "Z")
            for label, path in (("dashboard", "/dashboard/summary"),
                                ("cpu history 24h", f"/servers/{sid}/metrics?metric=cpu.usage_pct&from={frm}"),
                                ("timeline", "/events?size=50")):
                st, _, dt = call(base, "GET", path, None, admin)
                with lock:
                    read_lat.setdefault(label, []).append(dt)
                    if st != 200:
                        read_err[(label, st)] = read_err.get((label, st), 0) + 1
            time.sleep(0.5)

    print(f"Running for {a.duration:.0f} s: {len(agents)} agents every {a.interval:.0f} s, {a.readers} readers ...")
    t0 = time.time()
    with cf.ThreadPoolExecutor(max(a.workers, len(agents)) + a.readers) as ex:
        futs = [ex.submit(agent_loop, ag, random.uniform(0, a.interval)) for ag in agents]
        futs += [ex.submit(reader_loop, k) for k in range(a.readers)]
        cf.wait(futs)
    elapsed = time.time() - t0

    print("\nResults")
    print(f"  reports sent: {len(rep_lat)} in {elapsed:.0f} s = {len(rep_lat) / elapsed:.1f}/s "
          f"(target {len(agents) / a.interval:.1f}/s); errors: {rep_err or 'none'}")
    print(f"  report latency ms: p50 {pct(rep_lat, 50):.0f}  p95 {pct(rep_lat, 95):.0f}  p99 {pct(rep_lat, 99):.0f}  max {max(rep_lat) * 1000:.0f}")
    for label, v in read_lat.items():
        print(f"  {label:16s} ms: p50 {pct(v, 50):.0f}  p95 {pct(v, 95):.0f}  max {max(v) * 1000:.0f}  (n={len(v)})")
    print(f"  read errors: {read_err or 'none'}")


if __name__ == "__main__":
    main()
