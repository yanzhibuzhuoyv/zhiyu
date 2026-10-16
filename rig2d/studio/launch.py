"""Start or reuse the local editor, with no dependency installation or model download."""
import argparse
import json
from pathlib import Path
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import webbrowser

ROOT = Path(__file__).resolve().parent.parent


def ready(url):
    try:
        with urllib.request.urlopen(url + 'api/health', timeout=1) as response:
            health = json.load(response)
        with urllib.request.urlopen(url + 'api/contract', timeout=1) as response:
            contract = json.load(response)
        return health.get('status') == 'ok' and contract.get('format') == 'rig2d-planar-1'
    except (OSError, ValueError, urllib.error.URLError):
        return False


def start(port):
    url = f'http://127.0.0.1:{port}/'
    if ready(url):
        return url, False
    with socket.socket() as probe:
        if probe.connect_ex(('127.0.0.1', port)) == 0:
            raise RuntimeError(f'端口 {port} 正由其他服务使用，请换一个端口。')
    logs = ROOT / '.studio-logs'
    logs.mkdir(exist_ok=True)
    stamp = time.strftime('%Y%m%d-%H%M%S') + f'-{time.time_ns() % 1000000000:09d}'
    with (logs / f'{stamp}.stdout.log').open('xb') as stdout, (logs / f'{stamp}.stderr.log').open('xb') as stderr:
        flags = (getattr(subprocess, 'CREATE_NO_WINDOW', 0) |
                 getattr(subprocess, 'CREATE_NEW_PROCESS_GROUP', 0) |
                 getattr(subprocess, 'CREATE_BREAKAWAY_FROM_JOB', 0))
        try:
            process = subprocess.Popen([sys.executable, '-m', 'studio.server', '--port', str(port)],
                cwd=ROOT, stdin=subprocess.DEVNULL, stdout=stdout, stderr=stderr, creationflags=flags)
        except OSError as error:
            raise RuntimeError('无法启动后台工坊，请双击启动器或从终端运行 studio.server。') from error
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        if ready(url):
            state = {'pid': process.pid, 'port': port, 'root': str(ROOT), 'logStamp': stamp}
            (logs / 'server.json').write_text(json.dumps(state, ensure_ascii=False), encoding='utf-8')
            return url, True
        if process.poll() is not None:
            raise RuntimeError(f'角色工坊未启动，请查看 .studio-logs/{stamp}.stderr.log。')
        time.sleep(.2)
    raise RuntimeError('启动尚未完成，请稍后再次运行启动器。')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=8808)
    parser.add_argument('--no-browser', action='store_true')
    options = parser.parse_args()
    if not 1024 <= options.port <= 65535:
        parser.error('port must be 1024..65535')
    try:
        url, started = start(options.port)
    except RuntimeError as error:
        print(str(error), file=sys.stderr)
        return 1
    if not options.no_browser:
        webbrowser.open(url)
    print(json.dumps({'url': url, 'started': started}, ensure_ascii=False))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
