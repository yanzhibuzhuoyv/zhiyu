"""Run the independent studio on loopback; no Android or cloud service is started."""
import argparse
from pathlib import Path
import uvicorn
from studio.api import create_app


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=8808)
    parser.add_argument('--data-root', type=Path, default=Path(__file__).resolve().parent.parent / '.studio-data')
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error('port must be between 1024 and 65535')
    uvicorn.run(create_app(data_root=args.data_root), host='127.0.0.1', port=args.port, log_level='warning')


if __name__ == '__main__':
    main()
