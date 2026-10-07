"""Two isolated background slots; credentials and scheduling exist only in RAM."""

import asyncio
import logging
from collections import OrderedDict
from contextlib import asynccontextmanager
from datetime import UTC, datetime

from starlette.concurrency import run_in_threadpool

from .memory_semantic import identify_turn

logger = logging.getLogger(__name__)
MAX_OWNERS = 4096
OWNER_BATCH = 8


class MemoryWorker:
    def __init__(self, chat, sessions, provider):
        self.chat, self.sessions, self.provider = chat, sessions, provider
        self._tokens = OrderedDict()
        self._active = {}
        self._running_tokens = {}
        self._recovered = OrderedDict()
        self._foreground = 0
        self._ready = asyncio.Event()
        self._ready.set()
        self._closed = False

    def activate(self, token, session):
        if (self._closed or not self.chat.auto_annotations or self.chat.annotation_mode != "semantic"
                or self.sessions.get(token) != session):
            return
        if session.owner not in self._tokens and len(self._tokens) >= MAX_OWNERS:
            return  # Original/job remains durable; a later authenticated request can resume.
        self._tokens[session.owner] = token
        self._pump()

    @asynccontextmanager
    async def foreground(self):
        self._foreground += 1
        self._ready.clear()
        try:
            yield
        finally:
            self._foreground -= 1
            if not self._foreground:
                self._ready.set()
                self._pump()

    def _pump(self):
        if self._closed or self._foreground:
            return
        for owner in tuple(self._tokens):
            if len(self._active) >= 2:
                return
            if owner not in self._active:
                self._active[owner] = asyncio.create_task(self._run(owner))

    def _session(self, owner, token):
        if not isinstance(token, str):
            return None
        session = self.sessions.get(token)
        return session if session and session.owner == owner else None

    def _discard_token(self, owner, token):
        # A reconnect may have replaced this credential while its call ran.
        if self._tokens.get(owner) == token:
            self._tokens.pop(owner, None)

    async def _run(self, owner):
        job, token = None, self._tokens.get(owner)
        try:
            if not self._session(owner, token):
                self._discard_token(owner, token)
                return
            if owner not in self._recovered:
                await run_in_threadpool(self.chat.memory_jobs.recover, owner)
                self._recovered[owner] = True
                if len(self._recovered) > MAX_OWNERS:
                    self._recovered.popitem(last=False)
            for _ in range(OWNER_BATCH):
                await self._ready.wait()
                token = self._tokens.get(owner)
                session = self._session(owner, token)
                if session is None:
                    self._discard_token(owner, token)
                    return
                self._running_tokens[owner] = token
                job = await self._claim(owner, session.provider_id)
                if job is None:
                    if self._tokens.get(owner) == token:
                        self._tokens.pop(owner, None)
                    return
                await self._process(owner, token, session, job)
                job = None
                if not self._session(owner, token):
                    self._discard_token(owner, token)
                    return
            if owner in self._tokens:
                self._tokens.move_to_end(owner)
        except asyncio.CancelledError:
            if job:
                await self._release(owner, job, "waiting_auth")
            raise
        except Exception:
            # Never log provider exceptions, inputs, identities, keys, or tokens.
            logger.warning("后台记忆任务暂时无法完成；完整原文已保留")
            if job:
                await self._release(owner, job, "failed")
            self._discard_token(owner, token)
        finally:
            self._active.pop(owner, None)
            self._running_tokens.pop(owner, None)
            self._pump()

    async def _claim(self, owner, provider_id):
        # Cancellation cannot abandon a thread that may still COMMIT its lease.
        pending = asyncio.create_task(run_in_threadpool(self.chat.memory_jobs.claim, owner, provider_id))
        try:
            return await asyncio.shield(pending)
        except asyncio.CancelledError:
            job = await pending
            if job:
                await self._release(owner, job, "waiting_auth")
            raise

    async def _process(self, owner, token, session, job):
        sources = await run_in_threadpool(self.chat.memory_jobs.sources, owner, job)
        if sources is None:
            await self._release(owner, job, "failed")
            return
        catalogue = await run_in_threadpool(self.chat.recognition_catalogue, owner,
                                            job["conversation_id"], before=job["user_sequence"])
        await self._ready.wait()
        if not self._session(owner, token):
            await self._release(owner, job, "waiting_auth")
            return
        remaining = (session.expires_at - datetime.now(UTC)).total_seconds()
        try:
            async with asyncio.timeout(max(remaining, 0)):
                recognition = await identify_turn(self.provider, session.provider_id, session.api_key,
                                                  job["model"], sources, catalogue=catalogue)
        except TimeoutError:
            await self._release(owner, job, "waiting_auth")
            return
        if not self._session(owner, token):
            await self._release(owner, job, "waiting_auth")
            return
        await run_in_threadpool(self.chat.memory_jobs.finish, owner, job, recognition)

    async def _release(self, owner, job, status):
        try:
            await run_in_threadpool(self.chat.memory_jobs.release, owner, job, status)
        except Exception:
            # A still-running lease must be recoverable on the next valid auth.
            self._recovered.pop(owner, None)
            logger.warning("后台记忆状态暂时无法保存；重新认证后可检查")

    async def revoke(self, token):
        owners = [owner for owner, value in self._tokens.items() if value == token]
        for owner in owners:
            self._tokens.pop(owner, None)
        tasks = [self._active[owner] for owner, value in self._running_tokens.items()
                 if value == token and owner in self._active]
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)

    async def wait_idle(self):
        """Bounded test/maintenance hook; application requests never await this."""
        async with asyncio.timeout(10):
            while self._active or self._tokens:
                await asyncio.sleep(0.005)

    async def aclose(self):
        self._closed = True
        self._tokens.clear()
        tasks = tuple(self._active.values())
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
