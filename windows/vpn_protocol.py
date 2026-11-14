import base64
import binascii
import json

from vpn_settings import MAX_BYTES

ACK = b'{"answer":{"message_ack":"ok"}}'


def pack(kind, data, name=""):
    if kind not in ("text", "file") or not 0 < len(data) <= MAX_BYTES:
        raise ValueError("Размер файла или текста должен быть от 1 байта до 32 КБ.")
    contents = {"kind": kind, "name": name, "data": base64.b64encode(data).decode("ascii")}
    return json.dumps({"offer": {"message": json.dumps(contents)}}).encode("utf-8")


class ProtocolError(Exception):
    """Invalid message from the connected peer."""


class MessageFormatError(ProtocolError):
    pass


class PayloadEncodingError(ProtocolError):
    pass


class PayloadSizeError(ProtocolError):
    pass


class AcknowledgementError(ProtocolError):
    pass


def decode_json(message):
    try:
        return json.loads(message)
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        raise MessageFormatError("Некорректный формат сообщения.") from error


def unpack(message):
    try:
        envelope = decode_json(message)
        contents = decode_json(envelope["offer"]["message"])
        kind, name, encoded = contents["kind"], contents["name"], contents["data"]
    except (TypeError, KeyError) as error:
        raise MessageFormatError("В сообщении отсутствуют обязательные поля.") from error
    if kind not in ("text", "file") or not isinstance(name, str) or not isinstance(encoded, str):
        raise MessageFormatError("Получены данные в неподдерживаемом формате.")
    try:
        data = base64.b64decode(encoded.encode("ascii"), validate=True)
        if not 0 < len(data) <= MAX_BYTES:
            raise PayloadSizeError("Некорректный размер полученных данных.")
        return kind, name, data.decode("utf-8") if kind == "text" else data
    except (UnicodeError, binascii.Error) as error:
        raise PayloadEncodingError("Некорректная кодировка полученных данных.") from error


def check_ack(message):
    if decode_json(message) != {"answer": {"message_ack": "ok"}}:
        raise AcknowledgementError("Получено неверное подтверждение.")
