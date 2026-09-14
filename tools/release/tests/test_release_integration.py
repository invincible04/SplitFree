"""Cross-helper release flow with real ZIP/tar/hash logic and fake Git/SDK/API boundaries."""
import argparse
import importlib.util
from pathlib import Path
import socket
import sys
import unittest
from unittest.mock import patch


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(file))
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


artifact_fixture = load('bridge_artifact_fixture', 'test_artifacts.py')
upload_fixture = load('bridge_upload_fixture', 'test_github_release.py')


class ReleaseIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.fixture = artifact_fixture.ReleaseTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.network = patch.object(socket, 'create_connection', side_effect=AssertionError('Real network forbidden'))
        self.network.start()
        self.addCleanup(self.network.stop)

    def finalized_bundle(self):
        a = artifact_fixture.a
        f = self.fixture
        args = f.final_args()
        with patch.object(a, 'run', side_effect=f.runner):
            a.finalize(args)
        return upload_fixture.release.load_bundle(args.output, artifact_fixture.META['tag'], artifact_fixture.META['commit'])

    def test_validate_prepare_finalize_upload_and_noop_retry(self):
        a = artifact_fixture.a
        f = self.fixture
        args = argparse.Namespace(tag=artifact_fixture.META['tag'], commit=artifact_fixture.META['commit'],
                                  repository=upload_fixture.REPOSITORY, output=f.root / 'validated.json')
        with patch.object(a, 'run', side_effect=f.runner):
            a.validate(args)
        bundle = self.finalized_bundle()
        self.assertEqual(set(bundle.files), set(upload_fixture.ORDER))
        api = upload_fixture.FakeGitHub(bundle)
        release_id = upload_fixture.release.upload_bundle(api, bundle)
        self.assertEqual(len(api.uploaded), 6)
        self.assertEqual(upload_fixture.release.upload_bundle(api, bundle), release_id)
        self.assertEqual(len(api.uploaded), 6, 'retry must not re-upload matching files')
        self.assertTrue(api.release_list[0]['draft'])
        self.assertEqual(bundle.info['certificateSha256'], artifact_fixture.CERT)

    def test_publication_is_refused_for_a_completed_published_bundle(self):
        bundle = self.finalized_bundle()
        api = upload_fixture.FakeGitHub(bundle)
        upload_fixture.release.upload_bundle(api, bundle)
        api.release_list[0]['draft'] = False
        writes = len(api.uploaded)
        with self.assertRaisesRegex(upload_fixture.release.ReleaseError, 'Published release'):
            upload_fixture.release.upload_bundle(api, bundle)
        self.assertEqual(len(api.uploaded), writes)


if __name__ == '__main__':
    unittest.main()
