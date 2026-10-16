"""Real HTTP/storage/layer/package loop with independent pixel expectations."""
import base64
import copy
import io
from pathlib import Path
import tempfile
import unittest
import numpy as np
from PIL import Image
from fastapi.testclient import TestClient
from studio.api import create_app
from studio.storage import Storage


class StudioIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.store = Storage(Path(self.folder.name))
        self.client = TestClient(create_app(store=self.store, allowed_hosts={'testserver'}))
        self.headers = {'Origin': 'http://testserver'}
        self.pixels = np.full((32, 32, 4), (245, 238, 222, 255), dtype=np.uint8)
        self.pixels[3, 30] = (110, 60, 30, 1)
        self.pixels[4, 30] = (110, 60, 30, 64)
        self.raw = io.BytesIO()
        Image.fromarray(self.pixels).save(self.raw, format='PNG')

    def tearDown(self):
        self.client.close()
        self.folder.cleanup()

    def imported(self):
        response = self.client.post('/api/import', headers=self.headers,
            json={'image': base64.b64encode(self.raw.getvalue()).decode(), 'name': '实际闭环'})
        self.assertEqual(200, response.status_code)
        return response.json()

    def composite(self, snapshot):
        result = Image.new('RGBA', (snapshot['width'], snapshot['height']))
        for layer in snapshot['asset']['layers']:
            response = self.client.get(layer['url'])
            self.assertEqual(200, response.status_code)
            image = Image.open(io.BytesIO(response.content)).convert('RGBA')
            result.alpha_composite(image, tuple(layer['bounds'][:2]))
        return np.asarray(result)

    def test_actual_media_composes_exact_alpha_including_remote_alpha_one_and_sixty_four(self):
        snapshot = self.imported()
        np.testing.assert_array_equal(self.pixels, self.composite(snapshot))
        self.assertFalse(snapshot['asset']['capabilities']['identityVerified'])
        self.assertTrue(snapshot['asset']['capabilities']['reviewRequired'])

    def test_build_export_restore_and_new_process_store_keep_definition_and_current_source(self):
        snapshot = self.imported()
        definition = copy.deepcopy(snapshot['definition'])
        definition['parts'] = [{'id': 'island', 'name': '独立附件', 'role': 'accessory', 'z': 5,
            'polygon': [[28, 1], [31, 1], [31, 6], [28, 6]], 'pivot': [28, 2], 'strength': .15}]
        built = self.client.post(f'/api/projects/{snapshot["id"]}/build', headers=self.headers,
            json={'revision': snapshot['revision'], 'definition': definition})
        self.assertEqual(200, built.status_code)
        snapshot = built.json()
        np.testing.assert_array_equal(self.pixels, self.composite(snapshot))
        package = self.client.get(f'/api/projects/{snapshot["id"]}/export').content
        restored = self.client.post('/api/restore', headers=self.headers,
            json={'package': base64.b64encode(package).decode()})
        self.assertEqual(200, restored.status_code)
        current = restored.json()
        self.assertNotEqual(snapshot['id'], current['id'])
        self.assertEqual(snapshot['definition'], current['definition'])
        self.assertEqual(snapshot['asset']['pixelSha'], current['asset']['pixelSha'])
        reopened = Storage(Path(self.folder.name)).get(snapshot['id'])
        self.assertEqual(2, reopened['revision'])
        self.assertEqual(definition, reopened['definition'])
        np.testing.assert_array_equal(self.pixels, self.composite(current))

    def test_stale_build_and_corrupt_package_leave_published_project_unchanged(self):
        snapshot = self.imported()
        path = f'/api/projects/{snapshot["id"]}/build'
        body = {'revision': 1, 'definition': snapshot['definition']}
        self.assertEqual(200, self.client.post(path, headers=self.headers, json=body).status_code)
        self.assertEqual(409, self.client.post(path, headers=self.headers, json=body).status_code)
        self.assertEqual(400, self.client.post('/api/restore', headers=self.headers,
            json={'package': base64.b64encode(b'PK corrupt').decode()}).status_code)
        current = self.client.get(f'/api/projects/{snapshot["id"]}').json()
        self.assertEqual(2, current['revision'])
        np.testing.assert_array_equal(self.pixels, self.composite(current))


if __name__ == '__main__':
    unittest.main()
