"use strict";

const BASE = "/data/local/yaos-a11y/runtime";
const DISABLED_FLAG = Memory.allocUtf8String(BASE + "/disabled");
const ACTIVE_PID_FILE = Memory.allocUtf8String(BASE + "/hook-active.pid");

function utf16Pattern(value) {
    const bytes = [];
    for (let index = 0; index < value.length; index++) {
        const code = value.charCodeAt(index);
        bytes.push((code & 0xff).toString(16).padStart(2, "0"));
        bytes.push(((code >> 8) & 0xff).toString(16).padStart(2, "0"));
    }
    return bytes.join(" ");
}

function utf16Bytes(value) {
    const bytes = [];
    for (let index = 0; index < value.length; index++) {
        const code = value.charCodeAt(index);
        bytes.push(code & 0xff, (code >> 8) & 0xff);
    }
    return bytes;
}

const putSecurePattern = utf16Pattern("PUT_secure");
const replacements = [
    {
        pattern: utf16Pattern("enabled_accessibility_services"),
        bytes: utf16Bytes("yaos_blocked_accessibility_key"),
    },
    {
        pattern: utf16Pattern("accessibility_enabled"),
        bytes: utf16Bytes("yaos_access_blocked__"),
    },
];

const libc = Process.getModuleByName("libc.so");
const access = new NativeFunction(libc.getExportByName("access"), "int", ["pointer", "int"]);
const open = new NativeFunction(libc.getExportByName("open"), "int", ["pointer", "int", "int"]);
const write = new NativeFunction(
    libc.getExportByName("write"),
    "int",
    ["int", "pointer", "uint"],
);
const close = new NativeFunction(libc.getExportByName("close"), "int", ["int"]);
const getpid = new NativeFunction(libc.getExportByName("getpid"), "int", []);

const binder = Process.getModuleByName("libbinder.so");
const parcelData = new NativeFunction(
    binder.getExportByName("_ZNK7android6Parcel4dataEv"),
    "pointer",
    ["pointer"],
);
const parcelDataSize = new NativeFunction(
    binder.getExportByName("_ZNK7android6Parcel8dataSizeEv"),
    "uint",
    ["pointer"],
);
const transact = binder.getExportByName(
    "_ZN7android14IPCThreadState8transactEijRKNS_6ParcelEPS1_j",
);

Interceptor.attach(transact, {
    onEnter(args) {
        if (access(DISABLED_FLAG, 0) === 0) return;

        const data = parcelData(args[3]);
        const size = parcelDataSize(args[3]);
        if (data.isNull() || size === 0 || size > 1024 * 1024) return;
        if (Memory.scanSync(data, size, putSecurePattern).length === 0) return;

        replacements.forEach((replacement) => {
            Memory.scanSync(data, size, replacement.pattern).forEach((match) => {
                match.address.writeByteArray(replacement.bytes);
            });
        });
    },
});

const pidText = getpid().toString();
const pidBytes = Memory.allocUtf8String(pidText);
const markerFd = open(ACTIVE_PID_FILE, 513, 438);
if (markerFd >= 0) {
    write(markerFd, pidBytes, pidText.length);
    close(markerFd);
}

console.log("[yaos-a11y] secure settings Binder writes are filtered");
