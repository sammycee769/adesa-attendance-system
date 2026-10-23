import io
import json
import unittest
from unittest.mock import patch

import check_office_network as probe


class Response:
    def __init__(self, status=200, body=None, headers=()):
        self.status = status
        self.body = json.dumps(body or {}).encode()
        self.headers = headers

    def read(self, limit):
        return self.body[:limit]

    def getheaders(self):
        return self.headers


class Connection:
    def __init__(self, response):
        self.response = response
        self.headers = []
        self.closed = False

    def putrequest(self, method, path):
        self.method, self.path = method, path

    def putheader(self, name, value):
        self.headers.append((name, value))

    def endheaders(self, body):
        self.body = body

    def getresponse(self):
        return self.response

    def close(self):
        self.closed = True


class ProbeTests(unittest.TestCase):
    def test_repeated_header_lines_reach_transport_without_merging(self):
        connection = Connection(Response())
        client = probe.Client()
        with patch.object(probe.http.client, 'HTTPSConnection', return_value=connection) as factory:
            client.request('GET', probe.DIAGNOSTIC, extra_headers=[
                ('X-Forwarded-For', '105.127.8.52'), ('X-Forwarded-For', '198.51.100.23')])
        self.assertEqual(connection.headers[-2:], [
            ('X-Forwarded-For', '105.127.8.52'), ('X-Forwarded-For', '198.51.100.23')])
        self.assertEqual(factory.call_args.args, (probe.HOST,))
        self.assertTrue(factory.call_args.kwargs['context'].check_hostname)
        self.assertTrue(connection.closed)

    def test_redirect_or_unauthorized_body_is_not_disclosed(self):
        for status in [302, 401, 403, 500]:
            with self.subTest(status=status):
                connection = Connection(Response(status, {'message': 'sensitive-password-and-token'}))
                with patch.object(probe.http.client, 'HTTPSConnection', return_value=connection):
                    with self.assertRaises(probe.ProbeError) as error:
                        probe.Client().request('GET', probe.DIAGNOSTIC)
                self.assertNotIn('sensitive-password-and-token', str(error.exception))
                self.assertTrue(connection.closed)

    def test_cookies_are_kept_in_memory_and_rotation_replaces_them(self):
        client = probe.Client()
        connections = [Connection(Response(headers=[('Set-Cookie', 'JSESSIONID=first; Secure; HttpOnly')])),
                       Connection(Response(headers=[('Set-Cookie', 'JSESSIONID=second; Secure; HttpOnly')])),
                       Connection(Response(headers=[('Set-Cookie', 'JSESSIONID=; Max-Age=0')]))]
        with patch.object(probe.http.client, 'HTTPSConnection', side_effect=connections):
            client.request('GET', '/api/v1/auth/csrf')
            client.request('POST', '/api/v1/auth/login', {'email': 'admin@example.test', 'password': 'fixture'})
            self.assertEqual(client.cookies['JSESSIONID'], 'second')
            client.request('POST', '/api/v1/auth/logout')
        self.assertIn(('Cookie', 'JSESSIONID=first'), connections[1].headers)
        self.assertNotIn('JSESSIONID', client.cookies)

    def test_probe_failure_still_logs_out_without_echoing_password(self):
        client = probe.Client()
        def request(method, path, body=None, extra_headers=()):
            if path == '/api/v1/auth/login':
                return {'mustChangePassword': False}
            if path == probe.DIAGNOSTIC:
                raise probe.ProbeError('Diagnostic refused')
            return {}
        with patch.object(probe.sys.stdin, 'isatty', return_value=True), \
                patch('builtins.input', side_effect=['personal', 'admin@example.test']), \
                patch.object(probe.getpass, 'getpass', return_value='private-fixture-password'), \
                patch.object(probe, 'Client', return_value=client), \
                patch.object(client, 'csrf', return_value=[('X-XSRF-TOKEN', 'fixture')]), \
                patch.object(client, 'request', side_effect=request) as requests, \
                patch('sys.stdout', new_callable=io.StringIO) as output:
            with self.assertRaises(probe.ProbeError):
                probe.main()
            self.assertNotIn('private-fixture-password', output.getvalue())
            self.assertEqual(requests.call_args.args[:2], ('POST', '/api/v1/auth/logout'))
        self.assertEqual(client.cookies, {})


if __name__ == '__main__':
    unittest.main()
