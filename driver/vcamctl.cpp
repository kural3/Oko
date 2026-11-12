// vcamctl — установка/удаление/проверка виртуальной камеры Oko Camera.
//   vcamctl install <путь к Camera2PCCam.dll>   (от администратора)
//   vcamctl uninstall <путь к Camera2PCCam.dll> (от администратора)
//   vcamctl list    — список камер в системе
//   vcamctl test    — открыть камеру Oko Camera и прочитать несколько кадров

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mfreadwrite.h>
#include <mferror.h>
#include <stdio.h>
#include <string>

#include "shared.h"

static const GUID IID_IMFVirtualCamera_X = {0x1c08a864, 0xef6c, 0x4c75, {0xaf, 0x59, 0x5f, 0x2d, 0x68, 0xda, 0x95, 0x63}};

// IMFVirtualCamera (mfvirtualcamera.h, Windows SDK) — объявлен вручную
struct IMFVirtualCameraX : public IMFAttributes {
    virtual HRESULT STDMETHODCALLTYPE AddDeviceSourceInfo(LPCWSTR) = 0;
    virtual HRESULT STDMETHODCALLTYPE AddProperty(const void* key, ULONG type, const BYTE* data, ULONG cb) = 0;
    virtual HRESULT STDMETHODCALLTYPE AddRegistryEntry(LPCWSTR, LPCWSTR, DWORD, const BYTE*, ULONG) = 0;
    virtual HRESULT STDMETHODCALLTYPE Start(IMFAsyncCallback* cb) = 0;
    virtual HRESULT STDMETHODCALLTYPE Stop() = 0;
    virtual HRESULT STDMETHODCALLTYPE Remove() = 0;
    virtual HRESULT STDMETHODCALLTYPE GetMediaSource(IMFMediaSource** src) = 0;
    virtual HRESULT STDMETHODCALLTYPE SendCameraProperty(REFGUID, ULONG, ULONG, void*, ULONG, void*, ULONG, ULONG*) = 0;
    virtual HRESULT STDMETHODCALLTYPE CreateSyncEvent(REFGUID, ULONG, ULONG, HANDLE, void**) = 0;
    virtual HRESULT STDMETHODCALLTYPE CreateSyncSemaphore(REFGUID, ULONG, ULONG, HANDLE, LONG, void**) = 0;
    virtual HRESULT STDMETHODCALLTYPE Shutdown() = 0;
};

typedef HRESULT(STDAPICALLTYPE* PFN_MFCreateVirtualCamera)(int type, int lifetime, int access, LPCWSTR friendlyName,
                                                           LPCWSTR sourceId, const GUID* categories,
                                                           ULONG categoryCount, IMFVirtualCameraX** out);

static void PrintHr(const char* what, HRESULT hr) {
    wchar_t* msg = nullptr;
    FormatMessageW(FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
                   nullptr, hr, 0, (LPWSTR)&msg, 0, nullptr);
    printf("%s: 0x%08lX %ls\n", what, (unsigned long)hr, msg ? msg : L"");
    if (msg) LocalFree(msg);
}

static HRESULT CreateVCam(IMFVirtualCameraX** out, const wchar_t* name = CAMERA2PC_FRIENDLY_NAME) {
    HMODULE lib = LoadLibraryW(L"mfsensorgroup.dll");
    if (!lib) return HRESULT_FROM_WIN32(GetLastError());
    auto fn = (PFN_MFCreateVirtualCamera)GetProcAddress(lib, "MFCreateVirtualCamera");
    if (!fn) {
        printf("MFCreateVirtualCamera не найдена — нужна Windows 11.\n");
        return E_NOTIMPL;
    }
    // SoftwareCameraSource=0, Lifetime_System=1, Access_AllUsers=1
    return fn(0, 1, 1, name, CAMERA2PC_CLSID_STR, nullptr, 0, out);
}

static HRESULT CallDllExport(const wchar_t* dll, const char* name) {
    HMODULE m = LoadLibraryW(dll);
    if (!m) return HRESULT_FROM_WIN32(GetLastError());
    typedef HRESULT(__stdcall * PFN)();
    auto fn = (PFN)GetProcAddress(m, name);
    HRESULT hr = fn ? fn() : E_NOTIMPL;
    FreeLibrary(m);
    return hr;
}

static int Install(const wchar_t* dll) {
    HRESULT hr = CallDllExport(dll, "DllRegisterServer");
    if (FAILED(hr)) {
        PrintHr("Регистрация DLL", hr);
        return 1;
    }
    IMFVirtualCameraX* cam = nullptr;
    hr = CreateVCam(&cam);
    if (FAILED(hr)) {
        PrintHr("MFCreateVirtualCamera", hr);
        return 1;
    }
    hr = cam->Start(nullptr);
    if (FAILED(hr)) {
        PrintHr("Запуск камеры", hr);
        cam->Release();
        return 1;
    }
    cam->Release();
    printf("Камера Oko Camera установлена.\n");
    return 0;
}

static int Uninstall(const wchar_t* dll) {
    // снимаем и камеру со старым именем (Camera2PC), если она осталась от прошлой версии
    for (const wchar_t* name : {CAMERA2PC_FRIENDLY_NAME, CAMERA2PC_LEGACY_NAME}) {
        IMFVirtualCameraX* cam = nullptr;
        HRESULT hr = CreateVCam(&cam, name);
        if (SUCCEEDED(hr)) {
            hr = cam->Remove();
            if (FAILED(hr) && wcscmp(name, CAMERA2PC_FRIENDLY_NAME) == 0) PrintHr("Удаление камеры", hr);
            cam->Release();
        } else {
            PrintHr("MFCreateVirtualCamera", hr);
        }
    }
    if (dll) {
        HRESULT hr2 = CallDllExport(dll, "DllUnregisterServer");
        if (FAILED(hr2)) PrintHr("Отмена регистрации DLL", hr2);
    }
    printf("Камера Oko Camera удалена.\n");
    return 0;
}

static HRESULT FindCamera(IMFActivate** found, bool print) {
    IMFAttributes* attrs = nullptr;
    HRESULT hr = MFCreateAttributes(&attrs, 1);
    if (FAILED(hr)) return hr;
    attrs->SetGUID(MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE, MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID);
    IMFActivate** devices = nullptr;
    UINT32 count = 0;
    hr = MFEnumDeviceSources(attrs, &devices, &count);
    attrs->Release();
    if (FAILED(hr)) return hr;
    hr = MF_E_NOT_FOUND;
    for (UINT32 i = 0; i < count; i++) {
        wchar_t* name = nullptr;
        UINT32 len = 0;
        devices[i]->GetAllocatedString(MF_DEVSOURCE_ATTRIBUTE_FRIENDLY_NAME, &name, &len);
        if (print) printf("  %ls\n", name ? name : L"?");
        if (found && !*found && name && wcsncmp(name, CAMERA2PC_FRIENDLY_NAME, wcslen(CAMERA2PC_FRIENDLY_NAME)) == 0) {
            *found = devices[i];
            devices[i]->AddRef();
            hr = S_OK;
        }
        CoTaskMemFree(name);
        devices[i]->Release();
    }
    CoTaskMemFree(devices);
    if (!found) hr = S_OK;
    return hr;
}

static int Test() {
    IMFActivate* act = nullptr;
    HRESULT hr = FindCamera(&act, false);
    if (FAILED(hr)) {
        PrintHr("Камера Oko Camera не найдена", hr);
        return 1;
    }
    IMFMediaSource* src = nullptr;
    hr = act->ActivateObject(__uuidof(IMFMediaSource), (void**)&src);
    if (FAILED(hr)) {
        PrintHr("Активация камеры", hr);
        return 1;
    }
    IMFSourceReader* reader = nullptr;
    hr = MFCreateSourceReaderFromMediaSource(src, nullptr, &reader);
    if (FAILED(hr)) {
        PrintHr("SourceReader", hr);
        return 1;
    }
    IMFMediaType* type = nullptr;
    reader->GetCurrentMediaType(MF_SOURCE_READER_FIRST_VIDEO_STREAM, &type);
    UINT32 w = 0, h = 0;
    GUID sub = {};
    if (type) {
        MFGetAttributeSize(type, MF_MT_FRAME_SIZE, &w, &h);
        type->GetGUID(MF_MT_SUBTYPE, &sub);
        type->Release();
    }
    printf("Формат: %ux%u %s\n", w, h, IsEqualGUID(sub, MFVideoFormat_NV12) ? "NV12" : "RGB32/другой");

    int frames = 0;
    LONGLONG first = 0, last = 0;
    double avgY = 0, centerY = 0;
    for (int i = 0; i < 40; i++) {
        DWORD flags = 0;
        LONGLONG ts = 0;
        IMFSample* sample = nullptr;
        hr = reader->ReadSample(MF_SOURCE_READER_FIRST_VIDEO_STREAM, 0, nullptr, &flags, &ts, &sample);
        if (FAILED(hr)) {
            PrintHr("ReadSample", hr);
            break;
        }
        if (!sample) continue;
        if (!frames) first = ts;
        last = ts;
        frames++;
        IMFMediaBuffer* buf = nullptr;
        if (SUCCEEDED(sample->ConvertToContiguousBuffer(&buf))) {
            BYTE* p = nullptr;
            DWORD len = 0;
            if (SUCCEEDED(buf->Lock(&p, nullptr, &len)) && w && h) {
                uint64_t sum = 0;
                for (DWORD k = 0; k < w * h; k += 97) sum += p[k];
                avgY = (double)sum / (w * h / 97);
                centerY = p[(h / 2) * w + w / 2];
                buf->Unlock();
            }
            buf->Release();
        }
        sample->Release();
    }
    double secs = (last - first) / 1e7;
    printf("Получено кадров: %d, %.1f к/с, средняя яркость %.0f, центр %.0f\n", frames,
           secs > 0 ? (frames - 1) / secs : 0.0, avgY, centerY);
    reader->Release();
    src->Shutdown();
    src->Release();
    act->Release();
    return frames > 0 ? 0 : 1;
}

int wmain(int argc, wchar_t** argv) {
    SetConsoleOutputCP(CP_UTF8);
    if (argc < 2) {
        printf("usage: vcamctl install|uninstall <dll> | list | test\n");
        return 2;
    }
    CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    MFStartup(MF_VERSION);
    std::wstring cmd = argv[1];
    int rc = 2;
    if (cmd == L"install" && argc >= 3) rc = Install(argv[2]);
    else if (cmd == L"uninstall") rc = Uninstall(argc >= 3 ? argv[2] : nullptr);
    else if (cmd == L"list") {
        printf("Камеры в системе:\n");
        rc = FAILED(FindCamera(nullptr, true)) ? 1 : 0;
    } else if (cmd == L"test") rc = Test();
    MFShutdown();
    CoUninitialize();
    return rc;
}
