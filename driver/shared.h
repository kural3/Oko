// Общие определения для DLL виртуальной камеры, утилиты установки и программы на ПК.
#pragma once
#include <stdint.h>
#include <guiddef.h>

// CLSID источника камеры {7D3F2A61-5C84-4B9E-A1D2-63E0F4B8C925}
static const GUID CLSID_Camera2PCSource =
    {0x7d3f2a61, 0x5c84, 0x4b9e, {0xa1, 0xd2, 0x63, 0xe0, 0xf4, 0xb8, 0xc9, 0x25}};
#define CAMERA2PC_CLSID_STR L"{7D3F2A61-5C84-4B9E-A1D2-63E0F4B8C925}"
#define CAMERA2PC_FRIENDLY_NAME L"Oko Camera"
#define CAMERA2PC_LEGACY_NAME L"Camera2PC"

// Разделяемая память, через которую программа на ПК передаёт кадры в камеру.
// Создаёт её DLL внутри службы Frame Server (у неё есть право на Global\),
// программа на ПК открывает существующую.
#define CAMERA2PC_SHM_NAME L"Global\\Camera2PC_Frame"
#define CAMERA2PC_MAGIC 0x43325043u  // 'C2PC'
#define CAMERA2PC_MAX_PIXELS (1920u * 1920u)
#define CAMERA2PC_HEADER_SIZE 64u
#define CAMERA2PC_SHM_SIZE (CAMERA2PC_HEADER_SIZE + CAMERA2PC_MAX_PIXELS * 4u)

// Протокол записи (seqlock): seq += 1 (нечётный) -> данные -> поля -> seq += 1 (чётный)
struct Camera2PCHeader {
    uint32_t magic;     // 0
    uint32_t version;   // 4
    uint32_t seq;       // 8
    uint32_t width;     // 12
    uint32_t height;    // 16
    uint32_t bpp;       // 20: 3 = BGR24, 4 = BGRA32 (сверху вниз)
    uint64_t tick;      // 24: GetTickCount64() в момент записи
    uint64_t consumerTick;  // 32: камера отметила, что её смотрят (GetTickCount64)
    uint32_t reserved[6];
};
static_assert(sizeof(Camera2PCHeader) == CAMERA2PC_HEADER_SIZE, "header size");
