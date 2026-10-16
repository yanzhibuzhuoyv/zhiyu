import base64
import unittest
from unittest.mock import patch
from fastapi.testclient import TestClient
from studio.api import create_app


class FakeStore:
    def __init__(self):
        self.calls = []
        self.snapshot = {'id': 'a' * 32, 'revision': 1, 'width': 8, 'height': 8,
                         'name': '羽', 'definition': {},
                         'asset': {'format': 'rig2d-planar-1', 'layers': [
                             {'id': 'face', 'texture': 'textures/face.png'}]}}

    def create(self, raw, name):
        self.calls.append(('create', raw, name)); return self.snapshot

    def get(self, key):
        self.calls.append(('get', key))
        if key != self.snapshot['id']: raise FileNotFoundError('missing')
        return self.snapshot

    def build(self, key, revision, definition):
        self.calls.append(('build', key, revision, definition))
        if revision != self.snapshot['revision']: raise ValueError('REVISION_CONFLICT')
        return dict(self.snapshot, revision=revision + 1)

    def export(self, key): return b'PK_TEST_ZIP'

    def restore(self, raw): self.calls.append(('restore', raw)); return self.snapshot

    def file(self, key, path):
        self.calls.append(('file', key, path))
        if path not in ('source.png', 'textures/face.png'): raise FileNotFoundError('missing')
        return b'PNG_TEST'


class StudioApiTest(unittest.TestCase):
    def setUp(self):
        self.store = FakeStore()
        self.client = TestClient(create_app(store=self.store, allowed_hosts={'testserver'}))
        self.origin = {'Origin': 'http://testserver'}
        self.key = 'a' * 32

    def test_health_and_contract_are_local_and_advertise_actual_planar_mode(self):
        self.assertEqual(200, self.client.get('/api/health').status_code)
        contract = self.client.get('/api/contract').json()
        self.assertEqual('front_mesh', contract['mode'])
        self.assertFalse(contract['trueSideViews'])

    def test_import_decodes_image_and_returns_versioned_texture_urls_without_mutating_store(self):
        response = self.client.post('/api/import', json={'image': base64.b64encode(b'IMAGE').decode(), 'name': '羽'}, headers=self.origin)
        self.assertEqual(200, response.status_code)
        self.assertIn(f'/api/projects/{self.key}/files/textures/face.png', response.json()['asset']['layers'][0]['url'])
        self.assertEqual([('create', b'IMAGE', '羽')], self.store.calls)
        self.assertNotIn('url', self.store.snapshot['asset']['layers'][0])

    def test_malformed_base64_or_unknown_json_keys_do_not_reach_the_store(self):
        for payload in ({'image': 'bad!'}, {'image': 'SU1H', 'path': 'C:/private'}, {'image': 7}, []):
            with self.subTest(payload=payload):
                self.assertEqual(400, self.client.post('/api/import', json=payload, headers=self.origin).status_code)
        self.assertEqual([], self.store.calls)

    def test_cross_origin_and_non_loopback_host_requests_are_rejected_before_changes(self):
        for headers in ({'Origin': 'https://evil.example'}, {'Host': 'evil.example', **self.origin}):
            self.assertEqual(403, self.client.post('/api/import', json={'image': 'SU1H'}, headers=headers).status_code)
        self.assertEqual([], self.store.calls)

    def test_write_requires_json_and_same_origin_not_form_or_unchecked_origin(self):
        self.assertEqual(415, self.client.post('/api/import', data={'image': 'SU1H'}, headers=self.origin).status_code)
        self.assertEqual(403, self.client.post('/api/import', json={'image': 'SU1H'}).status_code)
        self.assertEqual([], self.store.calls)

    def test_nonfinite_and_invalid_json_are_typed_errors(self):
        for body in (b'{bad', b'{"image":"SU1H","name":NaN}', b'{"image":"SU1H","name":7}'):
            self.assertEqual(400, self.client.post('/api/import', content=body,
                headers={**self.origin, 'Content-Type': 'application/json'}).status_code)
        self.assertEqual([], self.store.calls)

    def test_unknown_project_and_path_traversal_do_not_reveal_a_file(self):
        self.assertEqual(404, self.client.get('/api/projects/' + 'b' * 32).status_code)
        self.assertEqual(404, self.client.get('/api/projects/invalid').status_code)
        self.assertEqual(404, self.client.get(f'/api/projects/{self.key}/files/%2e%2e%2fsecret.txt').status_code)

    def test_build_passes_expected_revision_and_reports_conflict(self):
        body = {'revision': 1, 'definition': {'parts': []}}
        r = self.client.post(f'/api/projects/{self.key}/build', json=body, headers=self.origin)
        self.assertEqual(200, r.status_code)
        self.assertEqual(2, r.json()['revision'])
        body['revision'] = 0
        self.assertEqual(409, self.client.post(f'/api/projects/{self.key}/build', json=body, headers=self.origin).status_code)

    def test_invalid_build_revision_is_rejected_before_builder(self):
        for revision in (None, True, -1, '1'):
            self.assertEqual(400, self.client.post(f'/api/projects/{self.key}/build',
                json={'revision': revision, 'definition': {}}, headers=self.origin).status_code)
        self.assertEqual(400, self.client.post(f'/api/projects/{self.key}/build',
            content=b'{"revision":Infinity,"definition":{}}',
            headers={**self.origin, 'Content-Type': 'application/json'}).status_code)

    def test_bundle_restore_and_export_use_managed_bytes(self):
        restored = self.client.post('/api/restore', json={'package': base64.b64encode(b'ZIP').decode()}, headers=self.origin)
        self.assertEqual(200, restored.status_code)
        exported = self.client.get(f'/api/projects/{self.key}/export')
        self.assertEqual(b'PK_TEST_ZIP', exported.content)
        self.assertEqual('application/zip', exported.headers['content-type'])
        self.assertIn('attachment', exported.headers['content-disposition'])

    def test_source_and_texture_files_are_png_and_not_cached_across_revisions(self):
        r = self.client.get(f'/api/projects/{self.key}/files/source.png')
        self.assertEqual(200, r.status_code)
        self.assertEqual('image/png', r.headers['content-type'])
        self.assertEqual('no-store', r.headers['cache-control'])

    def test_unexpected_worker_failure_is_a_typed_error_without_private_details_and_can_retry(self):
        with patch.object(self.store, 'get', side_effect=RuntimeError('PRIVATE_IMAGE_PATH')):
            with self.assertLogs('rig2d.studio', level='ERROR') as logs:
                r = self.client.get(f'/api/projects/{self.key}')
        self.assertEqual(500, r.status_code)
        self.assertEqual('PROCESSING_ERROR', r.json()['error']['code'])
        self.assertNotIn('PRIVATE_IMAGE_PATH', r.text)
        self.assertNotIn('PRIVATE_IMAGE_PATH', '\n'.join(logs.output))
        self.assertEqual(200, self.client.get(f'/api/projects/{self.key}').status_code)


if __name__ == '__main__': unittest.main()
