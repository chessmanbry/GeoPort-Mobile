import asyncio
import sys
import unittest
from pathlib import Path
from unittest.mock import AsyncMock
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'src'))
from device_controller import DeviceController, coordinates
from main import create_app


class Lifecycle(unittest.IsolatedAsyncioTestCase):
    def controller(self):
        c = DeviceController.__new__(DeviceController)
        c.stack = AsyncMock()
        c.simulation = AsyncMock()
        c.udid = 'test'
        c.position = [1, 2]
        c.error = None
        return c

    async def test_clear_before_closing(self):
        c = self.controller()
        events = []
        c.simulation.clear.side_effect = lambda: events.append('clear')
        c.stack.aclose.side_effect = lambda: events.append('close')
        await c._disconnect()
        self.assertEqual(events, ['clear', 'close'])
        self.assertIsNone(c.stack)

    async def test_failed_clear_preserves_connection_for_retry(self):
        c = self.controller()
        c.simulation.clear.side_effect = OSError('unplugged')
        stack = c.stack
        with self.assertRaises(OSError):
            await c._disconnect()
        stack.aclose.assert_not_called()
        self.assertIs(c.stack, stack)

    async def test_set_reuses_session_and_updates_only_after_success(self):
        c = self.controller()
        stack = c.stack
        await c._set(25, 121)
        self.assertIs(c.stack, stack)
        c.simulation.set.side_effect = OSError('lost transport')
        with self.assertRaises(OSError):
            await c._set(26, 122)
        self.assertEqual(c.position, [25, 121])

    async def test_unplug_releases_stale_session(self):
        from unittest.mock import patch
        c = self.controller()
        with patch('device_controller.list_devices', new=AsyncMock(return_value=[])):
            state = await c._status()
        self.assertFalse(state['connected'])
        self.assertIn('USB', state['error'])


class Validation(unittest.TestCase):
    def test_coordinates(self):
        for lat, lng in [(91,0),(0,181),(float('nan'),0),(0,float('inf')),(True,0)]:
            with self.assertRaises(ValueError): coordinates(lat,lng)
        self.assertEqual(coordinates('-25.1','121.5'),(-25.1,121.5))

    def test_http_error_and_origin(self):
        from unittest.mock import Mock
        controller = Mock()
        controller.call.side_effect = RuntimeError('device disconnected')
        client = create_app(controller).test_client()
        response = client.post('/api/set',json={'lat':1,'lng':2})
        self.assertEqual(response.status_code,400)
        self.assertEqual(response.json['error'],'device disconnected')
        controller.reset_mock()
        self.assertEqual(client.post('/api/clear',json={},headers={'Origin':'https://example.com'}).status_code,403)
        controller.call.assert_not_called()
        self.assertEqual(client.get('/').status_code,200)

if __name__ == '__main__': unittest.main()
