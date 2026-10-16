import asyncio
import threading
import unittest
from studio.jobs import WorkSlots, BusyError


class WorkSlotsTest(unittest.IsolatedAsyncioTestCase):
    async def test_cancelled_waiter_does_not_release_a_still_running_worker_slot(self):
        queue = WorkSlots(2)
        release = threading.Event()
        started = [threading.Event(), threading.Event()]
        finished = [threading.Event(), threading.Event()]
        def work(index):
            started[index].set()
            try:
                self.assertTrue(release.wait(3), 'Test cleanup must release the fake worker')
                return index
            finally:
                finished[index].set()
        tasks = [asyncio.create_task(queue.run(work, index)) for index in range(2)]
        try:
            for event in started:
                self.assertTrue(await asyncio.to_thread(event.wait, 1))
            tasks[0].cancel()
            with self.assertRaises(asyncio.CancelledError): await tasks[0]
            with self.assertRaises(BusyError): await queue.run(lambda: 'third')
            self.assertFalse(finished[0].is_set())
        finally:
            release.set()
            await asyncio.gather(*tasks, return_exceptions=True)
            for event in finished:
                await asyncio.to_thread(event.wait, 1)
        self.assertEqual('next', await queue.run(lambda: 'next'))

    async def test_worker_exception_releases_once_and_reaches_its_waiter(self):
        queue = WorkSlots(1)
        def fail(): raise ValueError('bad data')
        with self.assertRaisesRegex(ValueError, 'bad data'): await queue.run(fail)
        self.assertEqual(7, await queue.run(lambda: 7))

    async def test_budget_rejects_invalid_maximum(self):
        for value in (0, -1, True, 1.2, 17):
            with self.assertRaises(ValueError): WorkSlots(value)

    async def test_immediate_cancel_does_not_leak_a_claimed_slot(self):
        queue = WorkSlots(1)
        release = threading.Event()
        started = threading.Event()
        def work(): started.set(); release.wait(2)
        task = asyncio.create_task(queue.run(work))
        await asyncio.sleep(0)
        task.cancel()
        release.set()
        await asyncio.gather(task, return_exceptions=True)
        for _ in range(50):
            try:
                self.assertEqual('ok', await queue.run(lambda: 'ok'))
                break
            except BusyError:
                await asyncio.sleep(.005)
        else:
            self.fail('Cancelled waiter leaked a real worker slot')


if __name__ == '__main__': unittest.main()
