"""GeoPort USB baseline: macOS / Windows 11 preview, iOS 26, pymobiledevice3 11.30.0."""
import argparse
import logging
import signal
import webbrowser
from urllib.parse import urlsplit
from flask import Flask, jsonify, render_template, request
from device_controller import DeviceController


def create_app(controller):
    app = Flask(__name__)

    @app.before_request
    def local_requests_only():
        if request.host.split(':')[0] not in ('127.0.0.1', 'localhost'):
            return jsonify(error='Invalid host'), 403
        if request.method == 'POST':
            origin = request.headers.get('Origin')
            if origin and urlsplit(origin).netloc != request.host:
                return jsonify(error='Invalid origin'), 403
            if not request.is_json:
                return jsonify(error='JSON required'), 415

    @app.get('/')
    def index():
        return render_template('usb.html')

    @app.get('/api/devices')
    def devices():
        return jsonify(controller.call('devices'))

    @app.get('/api/status')
    def status():
        return jsonify(controller.call('status'))

    @app.post('/api/<operation>')
    def operate(operation):
        data = request.get_json()
        if not isinstance(data, dict):
            return jsonify(error='JSON object required'), 400
        if operation == 'connect':
            result = controller.call('connect', data.get('udid'))
        elif operation == 'set':
            result = controller.call('set', data.get('lat'), data.get('lng'))
        elif operation in ('clear', 'disconnect'):
            result = controller.call(operation)
        else:
            return jsonify(error='Unknown operation'), 404
        return jsonify(result)

    @app.errorhandler(Exception)
    def failure(exc):
        from werkzeug.exceptions import HTTPException
        if isinstance(exc, HTTPException):
            return jsonify(error=exc.description), exc.code
        logging.exception('Device operation failed')
        return jsonify(error=str(exc) or type(exc).__name__), 400

    return app


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=54321)
    parser.add_argument('--no-browser', action='store_true')
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO)
    controller = DeviceController()
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    try:
        if not args.no_browser:
            webbrowser.open(f'http://127.0.0.1:{args.port}')
        create_app(controller).run(host='127.0.0.1', port=args.port, debug=False, use_reloader=False)
    finally:
        try:
            controller.close()
        except Exception:
            logging.exception('無法確認定位已清除；請重新連接手機並清除定位')


if __name__ == '__main__':
    main()
