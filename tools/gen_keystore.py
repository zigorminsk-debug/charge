#!/usr/bin/env python3
"""Создаёт постоянный ключ подписи релизов: signing/charge-overlay.p12 (PKCS#12).

Ключ генерируется ОДИН раз и кладётся в репозиторий — GitHub Actions подписывает
им каждую сборку, поэтому новые APK/AAB ставятся поверх уже установленных
(та же подпись + растущий versionCode).

Повторный запуск без --force ничего не перезаписывает.

Запуск:
    python3 -m venv /tmp/venv && /tmp/venv/bin/pip install cryptography
    /tmp/venv/bin/python tools/gen_keystore.py
"""
from __future__ import annotations

import datetime
import os
import secrets
import sys

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SIGNING_DIR = os.path.join(ROOT, "signing")
KEYSTORE = os.path.join(SIGNING_DIR, "charge-overlay.p12")
PROPS = os.path.join(SIGNING_DIR, "signing.properties")

ALIAS = "charge"


def password() -> str:
    alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    return "".join(secrets.choice(alphabet) for _ in range(24))


def main() -> int:
    force = "--force" in sys.argv
    if os.path.isfile(KEYSTORE) and not force:
        print(f"Ключ уже существует: {os.path.relpath(KEYSTORE, ROOT)} — ничего не делаю (--force для перезаписи)")
        return 0

    store_password = password()
    # В PKCS#12 ключ защищён тем же паролем, что и хранилище — так читает JDK/keytool.
    key_password = store_password

    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    subject = issuer = x509.Name(
        [
            x509.NameAttribute(NameOID.COMMON_NAME, "Charge HUD"),
            x509.NameAttribute(NameOID.ORGANIZATIONAL_UNIT_NAME, "ChargeHUD Release"),
            x509.NameAttribute(NameOID.ORGANIZATION_NAME, "ChargeHUD"),
            x509.NameAttribute(NameOID.LOCALITY_NAME, "Minsk"),
            x509.NameAttribute(NameOID.COUNTRY_NAME, "BY"),
        ]
    )
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(issuer)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        # ~32 года: ключ переживёт всё время поддержки приложения
        .not_valid_after(now + datetime.timedelta(days=11680))
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .add_extension(
            x509.KeyUsage(
                digital_signature=True,
                content_commitment=False,
                key_encipherment=True,
                data_encipherment=False,
                key_agreement=False,
                key_cert_sign=False,
                crl_sign=False,
                encipher_only=False,
                decipher_only=False,
            ),
            critical=True,
        )
        .sign(key, hashes.SHA256())
    )

    p12 = pkcs12.serialize_key_and_certificates(
        name=ALIAS.encode("ascii"),
        key=key,
        cert=cert,
        cas=None,
        encryption_algorithm=serialization.BestAvailableEncryption(store_password.encode("utf-8")),
    )

    os.makedirs(SIGNING_DIR, exist_ok=True)
    with open(KEYSTORE, "wb") as fh:
        fh.write(p12)
    os.chmod(KEYSTORE, 0o644)

    with open(PROPS, "w", encoding="utf-8") as fh:
        fh.write("# Параметры постоянной подписи релизов. Файл лежит в репозитории намеренно.\n")
        fh.write("# storeType=PKCS12, алиас и пароли читаются из app/build.gradle.kts.\n")
        fh.write(f"storeFile=charge-overlay.p12\n")
        fh.write(f"storeType=PKCS12\n")
        fh.write(f"keyAlias={ALIAS}\n")
        fh.write(f"storePassword={store_password}\n")
        fh.write(f"keyPassword={key_password}\n")
    os.chmod(PROPS, 0o644)

    # keyPassword в PKCS#12 совпадает с storePassword — так требует формат

    sha = cert.fingerprint(hashes.SHA256()).hex()
    # Отпечаток нужен CI: он сверяет, что APK подписан именно этим ключом
    with open(os.path.join(SIGNING_DIR, "cert-sha256.txt"), "w", encoding="utf-8") as fh:
        fh.write(sha + "\n")
    print(f"Создан {os.path.relpath(KEYSTORE, ROOT)} ({len(p12)} байт)")
    print(f"  alias       : {ALIAS}")
    print(f"  subject     : {cert.subject.rfc4514_string()}")
    print(f"  действителен: {cert.not_valid_before_utc.date()} .. {cert.not_valid_after_utc.date()}")
    print(f"  SHA-256     : {':'.join(sha[i : i + 2] for i in range(0, len(sha), 2))}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
