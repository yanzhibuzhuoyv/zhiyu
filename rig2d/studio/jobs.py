"""Bound actual worker threads even when the HTTP caller stops waiting."""
import asyncio
import threading


class BusyError(Exception):
    pass


class WorkSlots:
    def __init__(self, maximum=2):
        if type(maximum) is not int or not 1 <= maximum <= 16:
            raise ValueError('INVALID_WORKER_BUDGET')
        self._slots = threading.BoundedSemaphore(maximum)
        self._pending = set()

    async def run(self, function, *args):
        if not self._slots.acquire(blocking=False):
            raise BusyError()
        def work():
            try:
                return function(*args)
            finally:
                self._slots.release()
        try:
            task = asyncio.create_task(asyncio.to_thread(work))
        except BaseException:
            self._slots.release()
            raise
        self._pending.add(task)
        def complete(done):
            self._pending.discard(done)
            if not done.cancelled():
                done.exception()  # Retrieve failures even if its HTTP waiter has left.
        task.add_done_callback(complete)
        return await asyncio.shield(task)
