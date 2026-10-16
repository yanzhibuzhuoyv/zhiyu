"""An explicitly authored synthetic twelve-wing example, never automatic recognition."""
import base64
import io
from pathlib import Path
import numpy as np
from PIL import Image


def example():
    root = Path(__file__).resolve().parent.parent / 'artifacts/sam2-cases-2026-10-13-r1/colored_oc'
    source = root / 'subject.png'
    with Image.open(source) as image:
        width, height = image.size
    parts = []
    names = [f'wing_{index:02}' for index in range(1, 13)] + ['weapon_01', 'back_hair', 'front_hair', 'body']
    for index, ident in enumerate(names):
        raw = (root / 'ground_truth' / (ident + '.mask')).read_bytes()
        assert len(raw) == width * height and all(byte in (0, 1) for byte in raw)
        mask = np.frombuffer(raw, np.uint8).reshape(height, width) * 255
        stream = io.BytesIO()
        Image.fromarray(mask).save(stream, format='PNG')
        role = 'hair' if 'hair' in ident else 'body' if ident == 'body' else 'accessory'
        label = f'翅膀 {index + 1}' if ident.startswith('wing') else {'weapon_01': '武器', 'back_hair': '后发', 'front_hair': '前发', 'body': '身体'}[ident]
        parts.append({'id': ident, 'name': label, 'role': role, 'z': index,
            'mask': base64.b64encode(stream.getvalue()).decode('ascii'),
            'pivot': [256, 285], 'strength': .7})
    return {'image': base64.b64encode(source.read_bytes()).decode('ascii'), 'name': '十二翼 · 练习角色',
        'definition': {'anchors': {'head': [256, 176], 'neck': [256, 349],
            'eyeLeft': [220, 160, 244, 184], 'eyeRight': [268, 160, 292, 184],
            'mouth': [246, 196, 270, 212]}, 'parts': parts},
        'syntheticExample': True}
