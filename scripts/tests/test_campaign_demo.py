"""Startup readiness checks without starting servers or touching a real database."""
import importlib.util
import pathlib
import unittest
from unittest.mock import MagicMock, patch

script = pathlib.Path(__file__).resolve().parents[1] / 'campaign-demo.py'
spec = importlib.util.spec_from_file_location('campaign_demo', script)
demo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(demo)


class ReadinessTests(unittest.TestCase):
    def test_failed_backend_is_reported_before_waiting_for_timeout(self):
        process = MagicMock()
        process.poll.return_value = 1
        with patch.object(demo.urllib.request, 'urlopen') as request:
            with self.assertRaisesRegex(RuntimeError, 'exited during startup'):
                demo.wait('http://127.0.0.1:8087/actuator/health', process=process)
            request.assert_not_called()

    def test_demo_seed_endpoint_is_checked_with_gateway_header(self):
        process = MagicMock()
        process.poll.return_value = None
        response = MagicMock()
        response.__enter__.return_value.status = 200
        with patch.object(demo.urllib.request, 'urlopen', return_value=response) as request:
            demo.wait('http://127.0.0.1:8087/api/v1/campaign/public/event/demo-business-gathering',
                      headers={'X-Campaign-Gateway': 'synthetic-test-key'}, process=process)
            sent = request.call_args.args[0]
            self.assertTrue(sent.full_url.endswith('/event/demo-business-gathering'))
            self.assertEqual(sent.get_header('X-campaign-gateway'), 'synthetic-test-key')


if __name__ == '__main__':
    unittest.main()
