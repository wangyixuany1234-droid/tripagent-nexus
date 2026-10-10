import asyncio
import math
import os
import sys
import threading
from collections import deque
from datetime import UTC, datetime

if sys.platform != "win32":
    import resource

from trip_agent_ai.schemas.runtime import RuntimeMetricsResponse


class RuntimeMetricsService:
    def __init__(self, interval_seconds: float = 0.1) -> None:
        self._interval_seconds = interval_seconds
        self._event_loop_lag_ms: deque[float] = deque(maxlen=36000)

    async def monitor_event_loop(self) -> None:
        loop = asyncio.get_running_loop()
        while True:
            started_at = loop.time()
            await asyncio.sleep(self._interval_seconds)
            elapsed = loop.time() - started_at
            lag_ms = max(0.0, elapsed - self._interval_seconds) * 1000
            self._event_loop_lag_ms.append(lag_ms)

    def snapshot(self) -> RuntimeMetricsResponse:
        samples = list(self._event_loop_lag_ms)
        ordered = sorted(samples)
        p95_index = max(
            0,
            min(len(ordered) - 1, math.ceil(len(ordered) * 0.95) - 1),
        )
        return RuntimeMetricsResponse(
            timestamp=datetime.now(UTC),
            processRssBytes=_process_rss_bytes(),
            threadCount=threading.active_count(),
            eventLoopLagMs=samples[-1] if samples else 0.0,
            eventLoopLagP95Ms=ordered[p95_index] if ordered else 0.0,
            eventLoopLagMaxMs=max(samples, default=0.0),
            eventLoopSamples=len(samples),
        )


def _process_rss_bytes() -> int:
    if sys.platform.startswith("linux"):
        try:
            with open("/proc/self/statm", encoding="ascii") as statm:
                resident_pages = int(statm.read().split()[1])
            return resident_pages * os.sysconf("SC_PAGE_SIZE")
        except (FileNotFoundError, IndexError, OSError, ValueError):
            pass
    if sys.platform == "win32":
        try:
            import ctypes
            from ctypes import wintypes

            class PROCESS_MEMORY_COUNTERS(ctypes.Structure):
                _fields_ = [
                    ("cb", wintypes.DWORD),
                    ("PageFaultCount", wintypes.DWORD),
                    ("PeakWorkingSetSize", ctypes.c_size_t),
                    ("WorkingSetSize", ctypes.c_size_t),
                    ("QuotaPeakPagedPoolUsage", ctypes.c_size_t),
                    ("QuotaPagedPoolUsage", ctypes.c_size_t),
                    ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t),
                    ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
                    ("PagefileUsage", ctypes.c_size_t),
                    ("PeakPagefileUsage", ctypes.c_size_t),
                ]

            psapi = ctypes.WinDLL("psapi")
            kernel32 = ctypes.WinDLL("kernel32")
            kernel32.GetCurrentProcess.restype = wintypes.HANDLE
            psapi.GetProcessMemoryInfo.argtypes = (
                wintypes.HANDLE,
                ctypes.POINTER(PROCESS_MEMORY_COUNTERS),
                wintypes.DWORD,
            )
            psapi.GetProcessMemoryInfo.restype = wintypes.BOOL
            counters = PROCESS_MEMORY_COUNTERS()
            counters.cb = ctypes.sizeof(PROCESS_MEMORY_COUNTERS)
            handle = kernel32.GetCurrentProcess()
            if psapi.GetProcessMemoryInfo(
                handle,
                ctypes.byref(counters),
                counters.cb,
            ):
                return int(counters.WorkingSetSize)
        except (OSError, AttributeError, ValueError):
            pass
        return 0
    # macOS / other Unix: resource.ru_maxrss (bytes on macOS, KiB on Linux)
    try:
        maximum_rss = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
        return int(maximum_rss if sys.platform == "darwin" else maximum_rss * 1024)
    except (OSError, ValueError, NameError):
        return 0
