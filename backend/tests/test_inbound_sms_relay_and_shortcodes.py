"""Synthetic relay boundary controls; these are not production or carrier receipts."""
import hmac
import json
import time
from unittest.mock import AsyncMock

import pytest
from fastapi.testclient import TestClient
from app.main import app
from app.core.config import settings
from app.core.redis import redis_manager
from app.core.limiter import limiter
from app.schemas.rpa import ErrorResponse
from app.services.otp_vault import otp_vault_service

PHONE = "09198765432"
OTHER = "09121112233"
SECRET = "backend-synthetic-secret-2026"

@pytest.fixture(autouse=True)
def isolated_settings(monkeypatch):
    monkeypatch.setattr(settings, "SMS_FORWARDER_SECRET", SECRET)
    monkeypatch.setattr(settings, "ENVIRONMENT", "development")
    redis_manager._in_memory_store.clear()
    redis_manager._streams.clear()
    if hasattr(limiter, "_call_records"):
        limiter._call_records.clear()
    if hasattr(limiter, "_storage"):
        limiter._storage.reset()

@pytest.fixture
def client():
    return TestClient(app)

def envelope(phone=PHONE, code="79345", timestamp=None, key=SECRET):
    timestamp = int(time.time() * 1000) if timestamp is None else timestamp
    payload = f"BP1#{phone}#{timestamp}#{code}"
    return payload + "#" + hmac.new(key.encode(), payload.encode("ascii"), "sha256").hexdigest()[:32]

def scoped(phone=PHONE):
    return hmac.new(SECRET.encode(), f"barpro-driver-v1:{phone}".encode(), "sha256").hexdigest()

def sms(**changes):
    payload = {"phone": PHONE, "text": "کد ورود: 79345", "sender": "+9830002128002150", "timestamp": int(time.time()*1000)}
    return {**payload, **changes}

def gateway(client, text=None, origin=PHONE, headers=None, path="/api/v1/otp/sms-gateway"):
    return client.post(path, json={"from": origin, "text": text or envelope()}, headers=headers if headers is not None else {"X-OTP-Webhook-Token": SECRET})

@pytest.mark.parametrize("path", ["/api/v1/otp/sms-gateway", "/api/v1/rpa/sms-inbound-relay", "/api/v1/rpa/sms-gateway/webhook"])
def test_missing_gateway_auth_cannot_write_otp(client, path):
    assert gateway(client, headers={}, path=path).status_code == 401
    assert not redis_manager._in_memory_store

def test_empty_gateway_configuration_fails_closed(client, monkeypatch):
    monkeypatch.setattr(settings, "SMS_FORWARDER_SECRET", "")
    assert gateway(client, headers={}).status_code in (401, 503)

def test_query_secret_cannot_authenticate_gateway(client):
    assert gateway(client, headers={}, path="/api/v1/otp/sms-gateway?secret="+SECRET).status_code == 401

@pytest.mark.parametrize("header", ["X-OTP-Webhook-Token", "X-Webhook-Token", "X-Forwarder-Secret", "X-Gateway-Secret"])
def test_signed_fresh_gateway_delivery_and_duplicate(client, header):
    text = envelope()
    response = gateway(client, text, headers={header:SECRET})
    assert response.status_code == 200
    assert response.json()["success"] and response.json()["otp_detected"]
    raw = next(value for key,value in redis_manager._in_memory_store.items() if key == f"rpa:otp:{PHONE}")
    assert json.loads(raw[0])["otp"] == "79345"
    replay = gateway(client,text,headers={header:SECRET})
    assert replay.status_code == 200 and replay.json()["is_duplicate"]
    assert redis_manager._in_memory_store[f"rpa:otp:{PHONE}"] == raw

@pytest.mark.parametrize("text", [
    f"BP1#{PHONE}#1728567890000#79345#00000000000000000000000000000000",
    f"BP1#{PHONE}#1728567890000#79345",
    f"BP1#{PHONE}#1728567890000#79345#sig#extra",
    f"bp1#{PHONE}#1728567890000#79345#sig",
])
def test_unverified_bp1_never_reaches_gateway_or_generic_extractor(client,text):
    assert gateway(client,text).status_code in (401,422)
    assert otp_vault_service.extract_utcms_otp(text) is None
    assert not redis_manager._in_memory_store

def test_valid_signature_cannot_change_origin_or_path(client):
    assert gateway(client,origin=OTHER).status_code == 422
    assert gateway(client,path=f"/api/v1/otp/sms-gateway/{OTHER}").status_code == 422

@pytest.mark.parametrize("code", ["HEALTH_CHECK", "TEST"])
def test_probe_without_persistent_receipt_is_retryable_not_otp(client,code):
    response=gateway(client,envelope(code=code))
    assert response.status_code == 503
    assert not redis_manager._in_memory_store and not redis_manager._streams

@pytest.mark.parametrize("offset", [-900, 900])
def test_signed_clock_cannot_be_replaced_by_ingestion_clock(client,offset):
    response=gateway(client,envelope(timestamp=int((time.time()+offset)*1000)))
    assert response.status_code in (410,422)
    assert not redis_manager._in_memory_store

def test_scoped_credential_authorizes_own_phone_only(client):
    headers={"X-Webhook-Token":scoped()}
    own=client.post(f"/api/v1/otp/sms-forwarder/{PHONE}",json=sms(),headers=headers)
    assert own.status_code == 200 and own.json()["success"]
    other=client.post(f"/api/v1/otp/sms-forwarder/{PHONE}",json=sms(phone=OTHER),headers=headers)
    assert other.status_code == 422
    assert f"rpa:otp:{OTHER}" not in redis_manager._in_memory_store

@pytest.mark.parametrize("change", [
    {"driver_phone":OTHER},
    {"data":{"phone":OTHER}},
    {"data":{"driver_phone":OTHER}},
])
def test_conflicting_phone_aliases_fail_before_storage(client,change):
    response=client.post(f"/api/v1/otp/webhook/{PHONE}",json=sms(**change),headers={"X-Webhook-Token":SECRET})
    assert response.status_code == 422
    assert not redis_manager._in_memory_store

def test_driver_header_cannot_override_body_identity(client):
    response=client.post("/api/v1/otp/sms-forwarder",json=sms(),headers={"X-Webhook-Token":SECRET,"X-Driver-Phone":OTHER})
    assert response.status_code == 422

def test_driver_scope_can_supply_missing_body_phone(client):
    payload=sms()
    del payload["phone"]
    response=client.post(f"/api/v1/otp/sms-forwarder/{PHONE}",json=payload,headers={"X-Webhook-Token":scoped()})
    assert response.status_code == 200 and response.json()["success"]

def test_generic_direct_bp1_also_requires_signature(client):
    response=client.post("/api/v1/otp/sms-forwarder",json=sms(text=f"BP1#{PHONE}#123#79345#sig",sender=PHONE),headers={"X-Webhook-Token":SECRET})
    assert response.status_code in (401,422)
    assert not redis_manager._in_memory_store

def test_invalid_gateway_identity_is_not_assigned_to_a_driver(client):
    response=gateway(client,text="کد ورود: 79345",origin="not-a-phone")
    assert response.status_code == 422
    assert not redis_manager._in_memory_store

def test_gateway_storage_failure_is_retryable(client,monkeypatch):
    monkeypatch.setattr(redis_manager,"setex",AsyncMock(side_effect=ConnectionError("synthetic failure")))
    response=gateway(client)
    assert response.status_code == 503
    assert not response.json()["success"]

def test_anonymous_resolve_does_not_create_identity_or_credentials(client):
    response=client.post("/api/v1/otp/resolve-driver",json={"driver_phone":PHONE,"driver_name":"Synthetic Driver","device_id":"synthetic-device"})
    assert response.status_code in (401,404,405)
    assert not redis_manager._in_memory_store

def test_error_schema_keeps_runtime_compatibility():
    data=ErrorResponse(error="UNAUTHORIZED",message="Denied",detail="Denied",phone=None,otp_detected=False,is_duplicate=False).model_dump()
    assert {"message","detail","phone","otp_detected","is_duplicate"} <= data.keys()

@pytest.mark.parametrize("sender", ["+9830002128002150","+9850002710040127","+9820007777"])
def test_registered_shortcode_context_survives(sender):
    assert not otp_vault_service.is_ignored_sender(sender)
    assert otp_vault_service.extract_utcms_otp("کد ورود: ۵۹۳۸۱",sender=sender) == "59381"


def test_signed_retry_cannot_refresh_expiry_or_revive_expired_code(client, monkeypatch):
    clock = [1_800_000_000.0]
    monkeypatch.setattr(time, "time", lambda: clock[0])
    text = envelope(timestamp=int((clock[0] - 60) * 1000))
    first = gateway(client, text)
    assert first.status_code == 200
    saved = redis_manager._in_memory_store[f"rpa:otp:{PHONE}"]
    assert saved[1] == clock[0] + 120
    assert json.loads(saved[0])["timestamp"] == "1799999940000"
    clock[0] += 100
    retry = gateway(client, text)
    assert retry.status_code == 200 and retry.json()["is_duplicate"]
    assert redis_manager._in_memory_store[f"rpa:otp:{PHONE}"] == saved
    clock[0] += 21
    assert gateway(client, text).status_code == 410
    assert redis_manager._in_memory_store[f"rpa:otp:{PHONE}"] == saved


def test_scoped_signed_envelope_uses_existing_canonical_credential(client):
    response = gateway(client, envelope(key=scoped()))
    assert response.status_code == 200 and response.json()["otp_detected"]


@pytest.mark.parametrize("timestamp", [False, 0, "", None])
def test_supplied_invalid_gateway_timestamp_cannot_become_an_absent_clock(client, timestamp):
    response = client.post(
        "/api/v1/otp/sms-gateway",
        json={"from": PHONE, "text": "کد ورود: 79345", "timestamp": timestamp},
        headers={"X-Webhook-Token": SECRET},
    )
    assert response.status_code == 422
    assert not redis_manager._in_memory_store and not redis_manager._streams


@pytest.mark.parametrize("alias", ["sender", "from_number"])
def test_plain_gateway_conflicting_origin_aliases_fail_before_storage(client, alias):
    response = client.post(
        "/api/v1/otp/sms-gateway",
        json={"from": PHONE, alias: OTHER, "text": "کد ورود: 79345"},
        headers={"X-Webhook-Token": SECRET},
    )
    assert response.status_code == 422
    assert not redis_manager._in_memory_store and not redis_manager._streams


@pytest.mark.parametrize("marker", ["BP1\u2060#", "BP1\x00#", "B\u200bP1#", "ＢＰ１#", "B\u00a0P1#", "B P1#"])
def test_bp1_format_characters_cannot_reach_plaintext_otp_extraction(client, marker):
    text = f"{marker}{PHONE}#123#79345#forged کد ورود: 79345"
    response = gateway(client, text)
    assert response.status_code in (401, 422)
    assert otp_vault_service.extract_utcms_otp(text) is None
    assert not redis_manager._in_memory_store and not redis_manager._streams


def test_plain_gateway_matching_origin_and_original_timestamp_aliases_remain_compatible(client):
    timestamp = int(time.time() * 1000)
    response = client.post(
        "/api/v1/otp/sms-gateway",
        json={"from": PHONE, "sender": "+98" + PHONE[1:], "text": "کد ورود: 79345", "timestamp": timestamp, "receivedTimestamp": timestamp},
        headers={"X-Webhook-Token": SECRET},
    )
    assert response.status_code == 200 and response.json()["success"]
    assert json.loads(redis_manager._in_memory_store[f"rpa:otp:{PHONE}"][0])["timestamp"] == timestamp


@pytest.mark.parametrize("mutation", ["setex", "xadd"])
def test_explicit_storage_refusal_cannot_be_acknowledged(client, monkeypatch, mutation):
    monkeypatch.setattr(redis_manager, mutation, AsyncMock(return_value=False))
    response = gateway(client)
    assert response.status_code == 503 and not response.json()["success"]


def test_failed_stream_write_does_not_install_accepted_duplicate(client, monkeypatch):
    original = redis_manager.xadd
    text = envelope()
    monkeypatch.setattr(redis_manager, "xadd", AsyncMock(side_effect=ConnectionError("synthetic stream failure")))
    assert gateway(client, text).status_code == 503
    monkeypatch.setattr(redis_manager, "xadd", original)
    retry = gateway(client, text)
    assert retry.status_code == 200 and not retry.json()["is_duplicate"]


@pytest.mark.parametrize("text", [
    f"prefix,BP1#{PHONE}#123#79345#sig کد ورود: 79345",
    f"BP1\u200c#{PHONE}#123#79345#sig",
])
def test_embedded_or_unicode_bp1_cannot_escape_generic_validation(client, text):
    assert otp_vault_service.extract_utcms_otp(text) is None
    response = client.post("/api/v1/otp/sms-forwarder", json=sms(text=text, sender=PHONE), headers={"X-Webhook-Token": SECRET})
    assert response.status_code in (401, 422)
    assert not redis_manager._in_memory_store


def test_shared_vault_boundary_rejects_forged_envelope_without_http_auth():
    import asyncio
    from fastapi import HTTPException
    with pytest.raises(HTTPException):
        asyncio.run(otp_vault_service.process_and_store_otp(PHONE, f"BP1#{PHONE}#123#79345#sig", PHONE, int(time.time()*1000)))
    assert not redis_manager._in_memory_store


@pytest.mark.parametrize("headers", [
    [("X-Webhook-Token", SECRET), ("X-Webhook-Token", "wrong")],
    {"X-Webhook-Token": SECRET, "X-OTP-Webhook-Token": "wrong"},
    {"X-Webhook-Token": SECRET, "X-Driver-Phone": "not-a-mobile"},
])
def test_conflicting_or_invalid_headers_are_never_silently_selected(client, headers):
    response = gateway(client, headers=headers)
    assert response.status_code in (401, 422)
    assert not redis_manager._in_memory_store


def test_gateway_rejects_malformed_types_and_oversized_payload(client):
    for payload in (["not-an-object"], {"from": PHONE, "text": 42}):
        response = client.post("/api/v1/otp/sms-gateway", json=payload, headers={"X-Webhook-Token": SECRET})
        assert response.status_code == 400
    response = client.post("/api/v1/otp/sms-gateway", json={"from":PHONE,"text":"x"*70000},headers={"X-Webhook-Token":SECRET})
    assert response.status_code == 413


@pytest.mark.parametrize("timestamp", [True, "nan", "Infinity", 1, int((time.time()+900)*1000)])
def test_plain_http_timestamp_cannot_create_fresh_otp_from_invalid_clock(client, timestamp):
    response=client.post("/api/v1/otp/sms-forwarder",json=sms(timestamp=timestamp),headers={"X-Webhook-Token":SECRET})
    assert response.status_code in (410,422)
    assert not redis_manager._in_memory_store


@pytest.mark.parametrize("alias", ["sender", "from_number"])
def test_signed_origin_aliases_cannot_disagree(client, alias):
    response=client.post("/api/v1/otp/sms-gateway",json={"from":PHONE,"text":envelope(),alias:OTHER},headers={"X-Webhook-Token":SECRET})
    assert response.status_code == 422
    assert not redis_manager._in_memory_store
