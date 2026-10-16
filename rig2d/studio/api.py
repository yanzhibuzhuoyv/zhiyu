"""Loopback-only editing API for the independent photo mesh studio."""
import asyncio
import base64
import binascii
import copy
import json
import logging
from pathlib import Path
import re
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, Response
from fastapi.staticfiles import StaticFiles
from studio.jobs import WorkSlots, BusyError

HERE = Path(__file__).resolve().parent
MAX_JSON = 96 * 1024 * 1024
MAX_IMAGE = 20 * 1024 * 1024
MAX_PACKAGE = 64 * 1024 * 1024
ID = re.compile(r'[a-f0-9]{32}')
FILES = re.compile(r'(source\.png|textures/[A-Za-z0-9_-]{1,80}\.png)')
LOG = logging.getLogger('rig2d.studio')


class InputError(ValueError):
    pass


def failure(code, message, status=400):
    return JSONResponse({'error': {'code': code, 'message': message}}, status_code=status)


def decorate(snapshot):
    result = copy.deepcopy(snapshot)
    root = f'/api/projects/{result["id"]}/files/'
    result['sourceUrl'] = root + 'source.png'
    for layer in result['asset']['layers']:
        layer['url'] = root + layer['texture']
    return result


async def body(request, fields, required):
    if request.headers.get('content-type', '').split(';')[0] != 'application/json':
        raise InputError('JSON_REQUIRED')
    chunks = bytearray()
    async for chunk in request.stream():
        if len(chunks) + len(chunk) > MAX_JSON:
            raise InputError('BODY_TOO_LARGE')
        chunks.extend(chunk)
    def reject_constant(value):
        raise InputError('NONFINITE_JSON')
    try:
        result = json.loads(chunks, parse_constant=reject_constant)
    except (ValueError, UnicodeError, RecursionError) as error:
        raise InputError('INVALID_JSON') from error
    if not isinstance(result, dict) or set(result) - set(fields) or not set(required) <= set(result):
        raise InputError('INVALID_FIELDS')
    return result


def decode(value, limit):
    if not isinstance(value, str):
        raise InputError('INVALID_FILE_TYPE')
    if len(value) > (limit + 2) // 3 * 4:
        raise InputError('FILE_TOO_LARGE')
    try:
        data = base64.b64decode(value, validate=True)
    except (ValueError, binascii.Error) as error:
        raise InputError('INVALID_BASE64') from error
    if not data:
        raise InputError('EMPTY_FILE')
    if len(data) > limit:
        raise InputError('FILE_TOO_LARGE')
    return data


def loopback_host(value):
    return re.fullmatch(r'(localhost|127\.0\.0\.1)(:[0-9]{1,5})?', value) is not None


def create_app(store=None, data_root=None, allowed_hosts=None):
    if store is None:
        from studio.storage import Storage
        store = Storage(Path(data_root or HERE.parent / '.studio-data'))
    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
    slots = WorkSlots(2)
    app.state.store = store

    @app.middleware('http')
    async def boundary(request, call_next):
        host = request.headers.get('host', '')
        allowed = host in allowed_hosts if allowed_hosts is not None else loopback_host(host)
        if not allowed:
            return failure('LOCAL_ONLY', '请从本机打开编辑器。', 403)
        if request.method not in ('GET', 'HEAD') and request.headers.get('origin') != 'http://' + host:
            return failure('SAME_ORIGIN_REQUIRED', '请在编辑器页面中完成操作。', 403)
        try:
            response = await call_next(request)
        except InputError as error:
            status = 415 if str(error) == 'JSON_REQUIRED' else 413 if 'LARGE' in str(error) else 400
            return failure(str(error), '请检查图片、资产包或输入内容。', status)
        response.headers['X-Content-Type-Options'] = 'nosniff'
        response.headers['Referrer-Policy'] = 'no-referrer'
        response.headers['Cache-Control'] = 'no-store'
        return response

    async def invoke(method, *args):
        try:
            return await slots.run(method, *args)
        except BusyError:
            return failure('BUSY', '正在处理上一张图片，请稍候。', 429)
        except FileNotFoundError:
            return failure('NOT_FOUND', '没有找到这份角色资产。', 404)
        except ValueError as error:
            code = str(error)
            if 'REVISION' in code or 'CONFLICT' in code:
                return failure('REVISION_CONFLICT', '角色已更新，请重新加载后再保存。', 409)
            return failure('INVALID_ASSET', '图片或角色数据不完整，请检查后重试。')
        except OSError:
            return failure('STORAGE_ERROR', '保存未完成，请检查可用空间后重试。', 503)
        except Exception as error:
            LOG.error('角色处理失败：%s', type(error).__name__)
            return failure('PROCESSING_ERROR', '角色处理未完成，请重新加载后再试。', 500)

    @app.get('/api/health')
    def health():
        return {'status': 'ok', 'mode': 'front_mesh'}

    @app.get('/api/contract')
    def contract():
        return json.loads((HERE / 'contract.json').read_text(encoding='utf-8'))

    @app.get('/api/demo')
    async def demo():
        from studio.demo import example
        return await invoke(example)

    @app.post('/api/import')
    async def import_image(request: Request):
        data = await body(request, ('image', 'name'), ('image',))
        name = data.get('name', '我的恋人')
        if not isinstance(name, str) or not 1 <= len(name.strip()) <= 80:
            raise InputError('INVALID_NAME')
        result = await invoke(store.create, decode(data['image'], MAX_IMAGE), name.strip())
        return result if isinstance(result, Response) else decorate(result)

    @app.get('/api/projects/{key}')
    async def project(key: str):
        if not ID.fullmatch(key):
            return failure('NOT_FOUND', '没有找到这份角色资产。', 404)
        result = await invoke(store.get, key)
        return result if isinstance(result, Response) else decorate(result)

    @app.post('/api/projects/{key}/build')
    async def build(key: str, request: Request):
        if not ID.fullmatch(key):
            return failure('NOT_FOUND', '没有找到这份角色资产。', 404)
        data = await body(request, ('revision', 'definition'), ('revision', 'definition'))
        if type(data['revision']) is not int or data['revision'] < 0 or not isinstance(data['definition'], dict):
            raise InputError('INVALID_REVISION_OR_DEFINITION')
        result = await invoke(store.build, key, data['revision'], data['definition'])
        return result if isinstance(result, Response) else decorate(result)

    @app.get('/api/projects/{key}/export')
    async def export(key: str):
        if not ID.fullmatch(key):
            return failure('NOT_FOUND', '没有找到这份角色资产。', 404)
        result = await invoke(store.export, key)
        if isinstance(result, Response):
            return result
        return Response(result, media_type='application/zip',
                        headers={'Content-Disposition': 'attachment; filename="character.rig2d"'})

    @app.post('/api/restore')
    async def restore(request: Request):
        data = await body(request, ('package',), ('package',))
        result = await invoke(store.restore, decode(data['package'], MAX_PACKAGE))
        return result if isinstance(result, Response) else decorate(result)

    @app.get('/api/projects/{key}/files/{path:path}')
    async def file(key: str, path: str):
        if not ID.fullmatch(key) or not FILES.fullmatch(path):
            return failure('NOT_FOUND', '没有找到这份图片。', 404)
        result = await invoke(store.file, key, path)
        if isinstance(result, Response):
            return result
        data = result.read_bytes() if isinstance(result, Path) else result
        return Response(data, media_type='image/png')

    web = HERE / 'web'
    if (web / 'index.html').exists():
        app.mount('/', StaticFiles(directory=web, html=True), name='studio')
    return app
