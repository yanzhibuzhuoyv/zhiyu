import pytest
from test_memory_worker import setup, turn

from app.config import Settings
from app.main import create_app


async def test_failed_release_is_recovered_after_fresh_authentication(tmp_path):
    sessions, chat, provider, worker = setup(tmp_path)
    token, session, _, _ = turn(sessions, chat)
    try:
        worker.activate(token, session)
        await provider.started.wait()
        with chat.connection() as db:
            db.execute("""CREATE TRIGGER release_fault BEFORE UPDATE ON memory_jobs
                WHEN NEW.status='waiting_auth' BEGIN SELECT RAISE(ABORT,'synthetic-fault'); END""")
        sessions.revoke(token)
        await worker.revoke(token)
        await worker.wait_idle()
        with chat.connection() as db:
            assert db.execute("SELECT status FROM memory_jobs").fetchone()[0] == "running"
            db.execute("DROP TRIGGER release_fault")
        fresh, connected = sessions.issue("deepseek", "synthetic-key-0", "test-model")
        provider.release.set()
        worker.activate(fresh, connected)
        await worker.wait_idle()
        assert len(provider.calls) == 2
        with chat.connection() as db:
            assert db.execute("SELECT status FROM memory_jobs").fetchone()[0] == "ok"
    finally:
        await worker.aclose()


async def test_new_auth_during_old_call_is_not_removed_with_expired_token(tmp_path):
    sessions, chat, provider, worker = setup(tmp_path)
    token, session, _, _ = turn(sessions, chat)
    try:
        worker.activate(token, session)
        await provider.started.wait()
        sessions.revoke(token)
        fresh, connected = sessions.issue("deepseek", "synthetic-key-0", "test-model")
        worker.activate(fresh, connected)
        provider.release.set()
        await worker.wait_idle()
        assert sessions.get(fresh) == connected
        assert len(provider.calls) == 2
        with chat.connection() as db:
            assert db.execute("SELECT status FROM memory_jobs").fetchone()[0] == "ok"
    finally:
        await worker.aclose()


@pytest.mark.parametrize("worker_close_fails", [False, True])
async def test_provider_closes_even_when_lifespan_or_worker_close_raises(tmp_path, worker_close_fails):
    class Provider:
        closed = False

        async def aclose(self):
            self.closed = True

    provider = Provider()
    app = create_app(Settings(data_dir=tmp_path), provider_client=provider)

    async def worker_close():
        if worker_close_fails:
            raise RuntimeError("synthetic-worker-close")

    app.state.memory_worker.aclose = worker_close
    with pytest.raises(RuntimeError):
        async with app.router.lifespan_context(app):
            raise RuntimeError("synthetic-lifespan")
    assert provider.closed
