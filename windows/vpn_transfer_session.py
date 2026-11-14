import logging

import wormhole
from twisted.internet import defer, reactor
from wormhole.cli.public_relay import MAILBOX_RELAY
from wormhole.errors import WrongPasswordError

from vpn_files import save_config
from vpn_protocol import ACK, MessageFormatError, ProtocolError, check_ack, unpack
from vpn_settings import APP_ID, TIMEOUT

log = logging.getLogger(__name__)


class Transfer:
    def __init__(self, events, relay=MAILBOX_RELAY):
        self.events = events
        self.relay = relay
        self.pending = None

    @defer.inlineCallbacks
    def exchange(self, data, code, target):
        channel = wormhole.create(APP_ID, self.relay, reactor)
        try:
            if data is None:
                channel.set_code(code)
            else:
                if code is None:
                    channel.allocate_code(4)
                else:
                    channel.set_code(code)
                self.events.put(("code", (yield channel.get_code())))
            yield channel.get_verifier()
            if data is None:
                payload = unpack((yield channel.get_message()))
                if target is None:
                    self.events.put(("payload", payload))
                else:
                    kind, _, content = payload
                    if kind != "file":
                        raise MessageFormatError("Ожидался файл.")
                    save_config(target, content)
                channel.send_message(ACK)
            else:
                channel.send_message(data)
                check_ack((yield channel.get_message()))
        finally:
            try:
                yield channel.close().addTimeout(4, reactor)
            except Exception as error:
                log.warning("channel_close_failed type=%s", type(error).__name__)

    def start(self, data, code, target):
        log.info("transfer_started direction=%s", "receive" if data is None else "send")
        self.pending = self.exchange(data, code, target)
        self.pending.addTimeout(TIMEOUT, reactor)
        self.pending.addCallbacks(self.done, self.failed)

    def done(self, _):
        log.info("transfer_completed")
        self.events.put(("done", None))

    def failed(self, failure):
        log.info("transfer_ended type=%s", failure.type.__name__)
        if failure.check(defer.CancelledError):
            message = "Передача остановлена."
        elif failure.check(defer.TimeoutError):
            message = "Время ожидания истекло. Начните новую передачу."
        elif failure.check(WrongPasswordError):
            message = "Коды не совпали. Начните новую передачу."
        elif failure.check(FileExistsError):
            message = "Файл уже существует. Выберите другое имя."
        elif failure.check(OSError):
            message = "Не удалось записать файл или подключиться к серверу."
        elif failure.check(ProtocolError):
            message = str(failure.value)
        else:
            message = "Передача прервалась. Проверьте интернет и повторите с новым кодом."
        self.events.put(("error", message))

    def cancel(self):
        if self.pending is not None:
            self.pending.cancel()
