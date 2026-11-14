"""Single-device USB session for pymobiledevice3 11.30.0."""
import asyncio
import math
import threading
import sys
from contextlib import AsyncExitStack

from pymobiledevice3.usbmux import list_devices
from pymobiledevice3.lockdown import create_using_usbmux
from pymobiledevice3.remote.userspace_tunnel import UserspaceRsdTunnel
from pymobiledevice3.services.mobile_image_mounter import auto_mount
from pymobiledevice3.exceptions import AlreadyMountedError
from pymobiledevice3.services.dvt.instruments.dvt_provider import DvtProvider
from pymobiledevice3.services.dvt.instruments.location_simulation import LocationSimulation


def coordinates(lat, lng):
    if isinstance(lat, bool) or isinstance(lng, bool):
        raise ValueError('座標必須是數字')
    lat, lng = float(lat), float(lng)
    if not math.isfinite(lat) or not math.isfinite(lng) or not (-90 <= lat <= 90 and -180 <= lng <= 180):
        raise ValueError('緯度須介於 -90～90，經度須介於 -180～180')
    return lat, lng


class DeviceController:
    def __init__(self):
        # Match pymobiledevice3 CLI on Windows (Python 3.13).
        self.loop = (asyncio.SelectorEventLoop() if sys.platform == "win32"
                     else asyncio.new_event_loop())
        self.thread = threading.Thread(target=self.loop.run_forever, daemon=True)
        self.lock = asyncio.Lock()
        self.stack = None
        self.simulation = None
        self.udid = None
        self.position = None
        self.error = None
        self.thread.start()

    def call(self, operation, *args):
        return asyncio.run_coroutine_threadsafe(self._dispatch(operation, *args), self.loop).result()

    async def _dispatch(self, operation, *args):
        async with self.lock:
            try:
                # Bounds device I/O while keeping all operations serialized on one loop.
                return await asyncio.wait_for(getattr(self, '_' + operation)(*args), 180)
            except Exception as exc:
                self.error = str(exc) or type(exc).__name__
                raise RuntimeError(self.error) from exc

    async def _devices(self):
        result = []
        for device in await list_devices():
            if device.connection_type != 'USB':
                continue
            client = await create_using_usbmux(serial=device.serial, connection_type='USB')
            try:
                result.append(client.short_info)
            finally:
                await client.close()
        return result

    async def _connect(self, udid):
        if not udid:
            raise ValueError('請選擇 USB 裝置')
        if self.stack:
            await self._disconnect()
        stack = AsyncExitStack()
        try:
            client = await create_using_usbmux(serial=udid, connection_type='USB')
            try:
                version = client.product_version
                if int(version.split('.')[0]) != 26:
                    raise ValueError('此測試版本目前僅開放 iOS 26；其他版本尚未驗證')
                if not await client.get_developer_mode_status():
                    raise ValueError('請在手機設定中啟用開發者模式，重新啟動並確認後再連線')
                try:
                    await auto_mount(client)
                except AlreadyMountedError:
                    pass
            finally:
                await client.close()
            rsd = await stack.enter_async_context(UserspaceRsdTunnel(serial=udid, remotepairing_fallback=False))
            dvt = await stack.enter_async_context(DvtProvider(rsd))
            simulation = await stack.enter_async_context(LocationSimulation(dvt))
        except BaseException:
            await stack.aclose()
            raise
        self.stack, self.simulation, self.udid = stack, simulation, udid
        self.position, self.error = None, None
        return self._snapshot()

    def _snapshot(self):
        return dict(connected=self.stack is not None, udid=self.udid, position=self.position, error=self.error)

    async def _status(self):
        # USB removal is visible without issuing a location-changing command.
        if self.stack:
            present = any(d.serial == self.udid and d.connection_type == 'USB' for d in await list_devices())
            if not present:
                await self._release()
                self.error = 'USB 已斷線；無法確認手機是否已清除模擬位置。請重新連線後按停止定位。'
        return self._snapshot()

    async def _set(self, lat, lng):
        lat, lng = coordinates(lat, lng)
        if not self.simulation:
            raise ValueError('請先連接手機')
        await self.simulation.set(lat, lng)
        self.position, self.error = [lat, lng], None
        return self._snapshot()

    async def _clear(self):
        if not self.simulation:
            raise ValueError('請先連接手機，才能清除模擬位置')
        await self.simulation.clear()
        self.position, self.error = None, None
        return self._snapshot()

    async def _release(self):
        stack = self.stack
        self.stack = self.simulation = self.udid = self.position = None
        if stack:
            await stack.aclose()

    async def _disconnect(self):
        if self.simulation:
            # On failure keep the session so the user can retry clear.
            await self._clear()
        await self._release()
        return self._snapshot()

    def close(self):
        try:
            self.call('disconnect')
        finally:
            self.loop.call_soon_threadsafe(self.loop.stop)
            self.thread.join(timeout=5)
