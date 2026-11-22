"""Worker race regressions: source contracts and an asyncio scheduling model.

The model is not the Kotlin scanner or Android: device/provider/lifecycle tests
are still needed. Source checks help connect the model to the production code.
"""
import asyncio
from pathlib import Path
import unittest


SCANNER = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/example/myebookapp/LibraryScanner.kt'


def worker_source():
    source = SCANNER.read_text()
    return source[source.index('    fun startWorker('):source.index('    /** Timeout or unexpected service destruction')]


class WorkerSourceContracts(unittest.TestCase):
    def test_pause_and_resume_status_derive_from_persisted_mode_under_monitor(self):
        worker = worker_source()
        lock = worker.index('processingMutex.withLock {')
        monitor = worker.index('synchronized(lock) {', lock)
        check = worker.index('ensureActive()', monitor)
        token = worker.index('if (generation != token)', check)
        saved = worker.index('val savedMode = mode(context)', token)
        paused = worker.index('val isPaused = savedMode == PAUSED', saved)
        status = worker.index('isPaused = isPaused, canResume = isPaused', paused)
        scan = worker.index('runScan(context, token)', status)
        self.assertLess(lock, monitor)
        self.assertLess(status, scan)
        self.assertIn('if (isPaused) "Scan paused" else "Preparing…"', worker[status:scan])
        self.assertIn('savedMode != RUNNING && savedMode != PAUSED', worker[saved:paused])

    def test_all_generations_use_one_mutex_for_processing(self):
        source = SCANNER.read_text()
        worker = worker_source()
        self.assertIn('private val processingMutex = Mutex()', source)
        self.assertIn('import kotlinx.coroutines.sync.withLock', source)
        self.assertNotIn('previous?.join()', worker)
        self.assertLess(worker.index('processingMutex.withLock {'), worker.index('runScan(context, token)'))
        self.assertLess(worker.index('runScan(context, token)'), worker.index('} catch (e: CancellationException)'))
        # Control functions never wait on the processing mutex while holding lock.
        self.assertEqual(source.count('processingMutex.withLock'), 1)


class GenerationScheduleModel(unittest.IsolatedAsyncioTestCase):
    async def test_pause_while_worker_waits_survives_startup_until_resume(self):
        mutex = asyncio.Lock()
        acquired, paused_at_start, resume = (asyncio.Event() for _ in range(3))
        state = {'mode': 'running', 'paused': False, 'can_resume': False, 'processed': 0}
        await mutex.acquire()

        async def worker():
            async with mutex:
                acquired.set()
                is_paused = state['mode'] == 'paused'
                state.update(paused=is_paused, can_resume=is_paused)
                paused_at_start.set()
                if is_paused:
                    await resume.wait()
                state['processed'] += 1

        task = asyncio.create_task(worker())
        try:
            await asyncio.sleep(0)  # worker waits for the previous generation
            state.update(mode='paused', paused=True, can_resume=True)
            mutex.release()
            await asyncio.wait_for(paused_at_start.wait(), 1)
            self.assertTrue(acquired.is_set())
            self.assertTrue(state['paused'])
            self.assertTrue(state['can_resume'])
            self.assertEqual(state['processed'], 0)
            state.update(mode='running', paused=False, can_resume=False)
            resume.set()
            await asyncio.wait_for(task, 1)
            self.assertEqual(state['processed'], 1)
        finally:
            if mutex.locked() and not acquired.is_set():
                mutex.release()
            if not task.done():
                task.cancel()
            await asyncio.gather(task, return_exceptions=True)

    async def test_cancelled_waiter_cannot_allow_third_worker_past_blocked_first(self):
        mutex = asyncio.Lock()
        old_entered, io_release = asyncio.Event(), asyncio.Event()
        cleanup_entered, cleanup_release = asyncio.Event(), asyncio.Event()
        second_waiting, third_waiting, third_entered = (asyncio.Event() for _ in range(3))

        async def old_worker():
            async with mutex:
                old_entered.set()
                # Simulates a cancellation that cannot interrupt provider I/O.
                await io_release.wait()
                # Simulates non-cancellable cleanup before releasing ownership.
                cleanup_entered.set()
                await cleanup_release.wait()

        async def waiter(waiting, entered):
            waiting.set()
            async with mutex:
                entered.set()

        first = asyncio.create_task(old_worker())
        await asyncio.wait_for(old_entered.wait(), 1)
        second_entered = asyncio.Event()
        second = asyncio.create_task(waiter(second_waiting, second_entered))
        await asyncio.wait_for(second_waiting.wait(), 1)
        second.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await second
        third = asyncio.create_task(waiter(third_waiting, third_entered))
        try:
            await asyncio.wait_for(third_waiting.wait(), 1)
            await asyncio.sleep(0)
            self.assertFalse(second_entered.is_set())
            self.assertFalse(third_entered.is_set(), 'Third generation overlapped blocked I/O')
            io_release.set()
            await asyncio.wait_for(cleanup_entered.wait(), 1)
            self.assertFalse(third_entered.is_set(), 'Third generation overlapped cleanup')
            cleanup_release.set()
            await asyncio.wait_for(first, 1)
            await asyncio.wait_for(third, 1)
            self.assertTrue(third_entered.is_set())
        finally:
            io_release.set()
            cleanup_release.set()
            for task in (first, third):
                if not task.done():
                    task.cancel()
            await asyncio.gather(first, third, return_exceptions=True)


if __name__ == '__main__':
    unittest.main()
